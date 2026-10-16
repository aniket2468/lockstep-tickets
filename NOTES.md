# Lockstep Tickets — Engineering Notes

Last updated: 2026-10-16

This document is the durable decision log for the assignment. It distinguishes
accepted requirements, implementation choices, and work that has not yet been
proved. When code and this document disagree, that mismatch is a defect.

## Scope

Lockstep Tickets is an assigned-seat booking system for live events. The
delivery target is the smallest end-to-end system that deeply proves inventory,
payment, refund, authorization, and audit correctness with Spring Boot, React,
and PostgreSQL.

The first release will include:

- authenticated event browse/search and live assigned-seat availability;
- atomic, non-extendable ten-minute holds;
- deterministic pricing and idempotent mock checkout;
- mock payment success, decline, timeout, unknown, and status lookup;
- booking view plus full or selected-ticket cancellation;
- customer, event-cancellation, reschedule-window, and exceptional refunds;
- minimal venue/event/price administration;
- audited seat block/release and event cancel/reschedule operations;
- FIFO event waitlisting with optional section and quantity preferences;
- transactional sales and occupancy queries; and
- real-PostgreSQL integration and concurrency tests.

Explicit non-goals are general admission, real payment or notification
providers, ticket transfer/resale, multi-event orders, seat swaps,
multi-currency, tax calculation, dynamic pricing, a drag-and-drop venue editor,
websocket scale-out, and sophisticated analytics. These are cut because they
do not improve proof of the assignment's core invariants within the time box.

## Accepted Product Rules

- Inventory is assigned-seat only. A physical seat is snapshotted into an
  event's inventory so later venue edits cannot rewrite history.
- An order contains one event and one to six tickets.
- A customer has at most one active hold per event. Active holds and confirmed,
  non-cancelled tickets count toward the 12-ticket customer/event limit.
- A hold gets one fixed deadline ten minutes after creation. It is not extended
  by cart activity.
- Prices are computed by the server and frozen at hold creation. The order is
  tier price, early-bird adjustment, group adjustment, then one promo. An
  exclusive promo cannot combine with early-bird or group adjustments.
- A promo may have global and per-customer limits. Use is consumed only by a
  confirmed booking. Customer cancellation never restores use; event
  cancellation does.
- Money is stored in integer minor units with an ISO currency. No binary
  floating-point arithmetic is used.
- Customer refunds are 100% at seven days or more before the event, 50% from
  48 hours through seven days, and 0% under 48 hours. Equality receives the
  more favorable band.
- A flat per-order service fee is not refundable for customer cancellation. It
  is refundable for event cancellation, the 14-day reschedule window, or an
  authorized exceptional refund.
- Event cancellation refunds every amount actually charged, exactly once.
- A reschedule keeps bookings and opens a full-refund window through the instant
  14 days after the announcement.
- Waitlist order is FIFO by event, optionally filtered by section and quantity.
  An offer lasts 15 minutes and never purchases automatically.
- An admin cannot block a held or sold seat. Every override has a bounded,
  non-blank reason and one audit record in the same transaction.
- Referenced business data is retired rather than deleted.

## Architecture and Data Model

The backend uses thin HTTP controllers and request/response DTOs, application
services for use cases and transaction boundaries, pure domain policies for
state transitions and calculations, and explicit persistence adapters. React
uses feature modules behind one typed API boundary. Flyway owns the schema;
automatic ORM schema creation is disabled outside tests as well.

Key tables are `account`, `venue`, `section`, `seat`, `event`, `event_seat`,
`price_tier`, `promo_code`, `customer_event_limit`, `hold`, `hold_item`,
`booking`, `ticket`, `payment_attempt`, `refund`, `refund_item`,
`promo_redemption`, `waitlist_entry`, `admin_audit`, and `outbox_event`.
Immutable price and allocation components live on hold items, tickets, and the
booking so edits to pricing rules cannot change transaction history.

PostgreSQL constraints are the final barrier for unique event-seat inventory,
one sold ticket per event seat, idempotency keys, normalized promo codes,
non-negative money, timestamp ordering, non-blank override reasons, and
non-cascading transactional history.

## State Machines

- Event: `DRAFT -> PUBLISHED -> ON_SALE -> SALES_CLOSED -> COMPLETED`.
  `PUBLISHED`, `ON_SALE`, or `SALES_CLOSED` may transition to `CANCELLED`.
  Rescheduling is a history record, not a durable event state; the existing
  sale state is preserved after dates and refund-window metadata are updated.
- Hold: `ACTIVE -> CONSUMED | EXPIRED | RELEASED`.
- Payment: `INITIATED -> SUCCEEDED | DECLINED | UNKNOWN`. A timeout is observed
  by the client but stored as `UNKNOWN` until provider status lookup resolves it.
- Booking: `PAYMENT_PENDING -> CONFIRMED | PAYMENT_FAILED | PAYMENT_UNKNOWN`;
  confirmed booking summaries are derived as `CONFIRMED`,
  `PARTIALLY_CANCELLED`, or `CANCELLED` from ticket state.
- Ticket: `CONFIRMED -> CANCELLATION_PENDING -> REFUNDED`. A failed or unknown
  refund remains `CANCELLATION_PENDING`; it is never returned to sale until the
  refund is known to have succeeded.
- Refund: `REQUESTED -> SUCCEEDED | FAILED | UNKNOWN`. A safe retry uses the
  same idempotency key and first performs status lookup for ambiguous outcomes.
- Waitlist: `WAITING -> OFFERED -> CLAIMED`, with `WAITING|OFFERED -> CANCELLED`
  and `OFFERED -> EXPIRED`.

All transitions go through named application/domain operations. Controllers do
not assign persistence states directly. Forbidden transitions must leave state,
inventory, money, audit, and outbox rows unchanged.

## Five Critical Design Decisions

### 1. Global lock order

Every mutating flow acquires only the locks it needs, always in this order:

1. event row, then customer/event limit row ordered by customer ID;
2. event-seat rows ordered by event-seat ID;
3. promo row, then promo/customer usage row;
4. booking, ticket, payment, and refund rows ordered by ID;
5. waitlist rows ordered by enqueue sequence; and
6. audit/outbox inserts (which require no prior row lock).

Hold acquisition takes the event, customer/event limit, and seats. Checkout
uses the same prefix, then promo and booking data. Admin block/release takes the
event then seats. Event cancellation takes one event, processes bookings in
bounded ID-ordered batches, and each batch follows the same order. Customer and
admin refunds take the event, limit row when ticket ownership changes, seats,
then booking/tickets/refunds. Waitlist promotion takes the event, any candidate
customer limit row, seats, then the earliest eligible waitlist row. No flow is
allowed to acquire an earlier class after a later one. Bounded PostgreSQL lock
timeouts become stable retryable `503` responses; money-changing calls are not
blindly retried.

### 2. Payment, expiry, and reconciliation

Checkout first validates and snapshots an unexpired hold in a short transaction
and creates one payment attempt under a customer-scoped idempotency key. It
then calls the local mock without database locks. Seats remain owned by that
hold while the result is `UNKNOWN`; they are not eligible for normal expiry,
sale, or waitlist promotion until reconciliation resolves the attempt. This is
an explicit `PAYMENT_PENDING` inventory condition, not an extension of the
customer-editable hold.

The synchronous success path finalizes the booking in a second short
transaction. If success is known but local persistence is uncertain, a
scheduled reconciliation worker and an admin-triggerable reconciliation use
mock payment status lookup and the same idempotency key. They either finalize
exactly one booking or, if inventory ownership cannot be proven, create an
idempotent compensating full refund. Therefore an ordinary expired hold is
never honored, while inventory reserved before payment remains protected until
the ambiguous money outcome is resolved.

### 3. Refund failures and seat availability

Cancellation marks selected tickets `CANCELLATION_PENDING` and creates a
refund request before calling the mock refund provider without locks. A seat is
released for ordinary sale or waitlist promotion only when the refund is
`SUCCEEDED` and its ticket becomes `REFUNDED`. `FAILED` and `UNKNOWN` keep both
the ticket and seat unavailable, preventing a resale while money ownership is
ambiguous. Reconciliation resolves unknown outcomes; a failed refund may be
retried explicitly with the same idempotency key. Event cancellation also
closes sales, so refunded seats from a cancelled event never become purchasable.

### 4. Exact integer allocation

Every order-level discount or refundable fee is allocated deterministically
across tickets. For an amount `A` and eligible ticket weights `w[i]`, each
ticket first receives `floor(A * w[i] / sum(w))`. Remaining minor units are
assigned one at a time by largest fractional remainder, breaking ties by
ascending event-seat ID. A zero-weight case uses equal weights with the same
tie-break. Allocations are persisted, never recomputed from current rules.

The fixed promo discount is capped at the eligible subtotal before allocation.
The service fee uses the same allocation only for event cancellation,
reschedule-window cancellation, or an exceptional refund; its persisted
customer-cancellation refundable allocation is zero. Partial cancellation sums
the selected tickets' stored refundable components, so disjoint partial
cancellations plus the remainder equal the original refundable amount exactly.
Cumulative successful refund constraints and locked refund rows prevent
over-refunding.

### 5. Authoritative time and daylight saving

An injected application `Clock` produces authoritative instants for domain
decisions and tests. PostgreSQL stores UTC instants and transaction code passes
one captured instant through the complete use case; database time is used only
for operational metadata, never as a competing policy clock. Hold expiry is
exclusive: it is valid only while `now < expiresAt`. Sales and promo intervals
are start-inclusive and end-exclusive.

Event and announcement timestamps are stored as resolved instants alongside
the venue's IANA zone. User-entered local times must include an explicit offset
when a DST overlap is ambiguous and are rejected when they fall in a DST gap.
Refund bands compare elapsed instants to the resolved event-start instant, so a
"48 hours" boundary always means exactly 48 elapsed hours even across DST.

## Security Model

- Passwords use an adaptive password hash; secrets and demo credentials are
  never committed. Demo data exists only under an explicit demo profile.
- Authentication establishes account ID and role. Customer/admin IDs, prices,
  states, refund amounts, and audit actors supplied by clients are ignored or
  rejected; resource ownership is resolved server-side.
- Endpoint authorization covers unauthenticated, wrong-role, inactive-account,
  and admin cases now; each owned-resource slice must add unrelated-customer
  tests. Unauthorized attempts produce no business mutation or success audit.
- JSON parsing rejects unknown fields and coercion. DTO bounds, body limits,
  allowlisted CORS, rate limiting on abuse-prone routes, request IDs, safe error
  bodies, and redacted structured logs reduce the exposed attack surface.
- SQL uses bound parameters. Containers run as non-root. A production release
  must add startup guards for demo/debug settings and use a separately managed,
  restricted database role. Audit history will be append-only to the application.
- Dependency and container vulnerability results will be recorded honestly;
  unexplained critical findings block a readiness claim.

## Decision Log

| ID | Ambiguity | Considered choices | Decision and reason | Impact if changed |
| --- | --- | --- | --- | --- |
| D-001 | Seat inventory | assigned, general admission, both | Assigned only; accepted scope and safest concurrency model | GA adds quantity inventory, separate locking, APIs, and tests |
| D-002 | Limit accounting | bookings only, holds plus bookings, lifetime tickets | Active holds plus confirmed active tickets; protects scarce inventory and accepted by stakeholder | Changes limit query, counter updates, and race tests |
| D-003 | Hold deadline | per-seat, sliding cart, fixed cart | One fixed non-extendable ten-minute group deadline | Changes aggregate and expiry races |
| D-004 | Payment ambiguity | synchronous only, ambiguous with lookup | Success/decline/timeout/unknown plus status lookup; required by stakeholder | Changes inventory reservation, reconciliation, and idempotency |
| D-005 | Price commitment | checkout recalculation, hold snapshot | Immutable server quote at hold | Recalculation needs price-change UX and new conflict behavior |
| D-006 | Refund boundary | calendar days, venue-local duration, elapsed time | Exact elapsed instants; equality gets better band | Calendar-day rules change DST/boundary policy tests |
| D-007 | Fee refunds | always, never, reason-dependent | Not for customer cancellation; full for event, reschedule window, or admin | Changes stored ticket allocations and refund totals |
| D-008 | Promo release | release on any cancellation, never, event-only | Only event cancellation restores use | Changes counters, cancellation transaction, and reconciliation |
| D-009 | Reschedule representation | durable state, history fact | History fact preserving sale state; 14-day full-refund window | Durable state expands event transition/API behavior |
| D-010 | Ambiguous refund inventory | release immediately, retain until resolved | Retain until refund success; prevents money/inventory overlap | Immediate release risks resale before refund outcome |
| D-011 | Waitlist offer | notification, purchase, reserved offer | FIFO 15-minute exclusive offer; never auto-purchase | Notification-only weakens fairness; purchase adds payment risk |
| D-012 | Reports | asynchronous analytics, transactional queries | Transactional queries for assignment volume | Scale would require projections and reconciliation lag |

## Known Gaps and Evidence Status

Audited admin operations, FIFO quantity/section waitlisting, exclusive offer
claiming, and reconciled sales/occupancy reads are implemented, with executed
evidence in `docs/VERIFICATION.md`. Waitlist promotion is admin-triggered for
this scoped release; background promotion and notification delivery remain
deferred. Minimal venue/section/seat/event/price/promo CRUD beyond the required
override flows remains deferred. Explicit server-side logout,
session/throttle-row cleanup, production-profile startup guards, a restricted
production database role, browser end-to-end automation, and backend/container
vulnerability scans remain release gaps. Checkout currently provides customer-triggered reconciliation;
the scheduled recovery worker and compensating-refund path for an unrecoverable
post-payment inventory inconsistency remain future work. HTTPS is an
edge/deployment responsibility; bearer tokens must
never cross plaintext or be stored in browser local storage. Concurrency claims
remain limited to the cases explicitly recorded in the verification ledger.

## AI Assistance Disclosure

OpenAI Codex (GPT-5 family) is being used as a collaborative implementation and
review tool. At this checkpoint it analyzed the supplied assignment and email,
structured the accepted requirements, and drafted this design/decision record,
delivery plan, and verification ledger. The developer must review and be able
to defend every decision before committing. Later assistance, commands run,
test evidence, rejected suggestions, and material developer corrections will
be appended here as work proceeds. No AI-authored result is represented as
independently verified merely because it was generated.

For the project-foundation checkpoint, Codex also drafted the Spring Boot,
React, PostgreSQL, Flyway, Testcontainers, Docker Compose, and security baseline;
queried official/current package information; generated lockfiles/wrappers; and
ran the commands recorded in `docs/VERIFICATION.md`. Generated suggestions were
corrected when execution exposed a TypeScript/tooling peer conflict, Spring Boot
4 module changes, PostgreSQL 18 volume semantics, and a local port collision.
No peer-dependency check or failed test was bypassed. The developer is expected
to review the resulting files and rerun the documented gates before committing.

For the catalog/authentication checkpoint, Codex added the constrained catalog
and opaque-session schema, explicit demo-only fixtures, BCrypt login, live
account/role enforcement, request IDs, stable safe errors, and PostgreSQL-backed
authorization tests. The first real database run exposed an unset JDBC schema;
the datasource now selects `lockstep` explicitly. Test logging also exposed
that generated record strings could contain login secrets, so the login DTOs
now redact passwords and tokens. Both findings are retained in the verification
history rather than being hidden.

A dedicated security review then identified five additional issues. The
checkpoint now has PostgreSQL-backed normalized-account and source-IP login
throttles, insert-only demo identities that fail on collisions, database tests
that fail when Docker is unavailable, admin-only non-health actuator policy,
and documentation that separates implemented controls from production work.

For the authenticated catalog checkpoint, Codex added bounded, parameterized
event search and detail endpoints, customer-visible lifecycle filtering, and an
event-seat response that masks blocks and all internal inventory ownership as
`UNAVAILABLE`. It also replaced the placeholder UI with an accessible login,
catalog search, event detail, and seat map flow. Bearer tokens remain in React
memory only. Six real-PostgreSQL API tests and six frontend behavior tests cover
the new boundary; the complete backend suite contains 24 tests, 23 of which use
PostgreSQL 18.6.

For the atomic hold checkpoint, Codex added the V3 hold and customer/event-limit
schema, event-first and deterministically ordered row locking, a fixed ten-minute
deadline, lazy expiry, all-or-nothing allocation, and live catalog masking.
PostgreSQL GiST exclusion prevents overlapping historical seat-claim intervals
even if application checks regress. Seven hold integration tests include 20
barrier-coordinated same-seat contention rounds without timing sleeps, exact
sale-window boundaries, authorization, rollback, expiry reuse, and limit checks.

For the immutable-pricing and checkout checkpoint, Codex added V4 pricing,
promo, booking, ticket, payment, redemption, and outbox persistence; deterministic
largest-remainder allocation; server-frozen hold quotes; customer-scoped
idempotency; mock success, decline, timeout, unknown, and lookup behavior; and
payment-pending inventory. Seven PostgreSQL checkout tests and four pure pricing
tests cover immutable snapshots, replay safety, expiry and ownership boundaries,
strict request binding, ambiguous-payment reconciliation, and a coordinated
last-promo-use race. Provider observations are committed separately from final
booking state so a retry or reconciliation can resume without charging twice.

For the customer cancellation checkpoint, Codex added V5 refund and attempt
persistence, owned booking views, selected-ticket cancellation, exact instant
refund bands, non-refundable customer service fees, provider outcome
reconciliation, and explicit retry after a decline. Unknown and failed refunds
keep inventory protected until success. Six PostgreSQL integration tests and
two pure policy tests cover ownership, replay and changed-payload behavior,
partial cancellation, exact 7-day/48-hour and DST-crossing boundaries, fee
exclusion, decline/retry, timeout/reconciliation, and coordinated concurrent
over-refund prevention. The work also corrected an earlier inventory query so
confirmed and cancellation-pending tickets cannot be held or shown available.

For the audited admin checkpoint, Codex added V6 reschedule history and
append-only audit persistence, role-protected block/release, reschedule,
event-cancellation, exceptional-refund, and audit APIs, plus outbox records for
inventory/event changes. Event cancellation refuses unresolved payments,
restores consumed promos, and creates remaining-balance refunds before any mock
provider call. A prior partial customer refund is subtracted per ticket so the
cumulative successful total equals the immutable charge, including fees, and
does not decrement customer limits twice. Nine PostgreSQL admin tests include
ten barrier-coordinated block-versus-hold races, concurrent same-key exceptional
refunds, reason/role rejection with zero audit rows, audit mutation rejection,
draft lifecycle enforcement, reschedule fee refunds, event cancellation after
a 50% refund, and pending payment rejection. A seventh refund integration case
proves the 14-day window is inclusive at its exact end and excludes the fee one
second later.

Admin-created refunds that receive an unknown result remain protected and can
be reconciled through the existing owner-visible refund endpoint. A declined
admin-created refund is persisted without releasing inventory, but a dedicated
operator retry endpoint and scheduled recovery worker remain deferred work.

For the waitlist and reporting checkpoint, Codex added V7 waitlist persistence,
a database-visible `WAITLIST_OFFER` claim state, sold-out-only enrollment,
quantity/section-aware FIFO selection, explicit claim into a new ten-minute
hold, customer and event cancellation cleanup, and repeatable-read sales and
occupancy queries. Six PostgreSQL integration tests include ten coordinated
promotion-versus-direct-hold races, exact 15-minute expiry, ownership and role
boundaries, 12-ticket skipping, price-preserving checkout, event-cancellation
cleanup, and exact money/seat reconciliation. The work also corrected an older
customer-limit counter assignment that double-counted confirmed tickets when a
new hold was created. Failed assertions and their corrections remain recorded
in the verification ledger.

