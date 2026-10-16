# Lockstep Tickets — Engineering Notes

Last updated: 2026-10-16

This is the short engineering record for the assignment. It explains what I
was asked to build, how unclear rules were resolved, the important decisions I
made, the problems I encountered, and what remains incomplete.

## What I was instructed to build

The supplied brief requested a Spring Boot, React, and PostgreSQL ticketing
system with both a customer site and an admin back office.

The important business rules were:

- a seat must never be sold twice;
- a hold lasts exactly 10 minutes;
- one order can contain at most 6 seats;
- one customer can hold or own at most 12 seats for an event;
- prices, promotions, refunds, waitlists, admin actions, reports, and audit
  history must follow explicit rules;
- concurrency tests must use real PostgreSQL, including two customers racing
  for the same seat;
- the README must contain exact run instructions; and
- the submission must honestly document decisions, ambiguities, gaps, next
  steps, and AI assistance.

## Clarifications I received

The stakeholder reply resolved the main product ambiguities:

- Events use assigned seats only.
- Holds have a fixed 10-minute lifetime. Seats in active holds and confirmed
  bookings both count toward customer limits.
- Event-day boundaries use the venue's local time zone.
- Money is stored as integer minor units.
- Blocking a held or sold seat is rejected.
- The mock payment provider supports success, decline, timeout, unknown, and a
  later status lookup.
- Customer refunds are 100% at least 7 days before the event, 50% from 48 hours
  to 7 days, and 0% under 48 hours.
- The service fee is not refunded for a customer cancellation, but it is
  refunded for an event cancellation or an eligible reschedule refund.
- A reschedule keeps existing bookings and gives customers 14 days to request
  a full refund, including the fee.
- Promotions are applied after tier, early-bird, and group adjustments. Only
  one promotion may be used, and exclusive promotions do not stack with other
  automatic adjustments.
- Promotion usage is consumed only after confirmation. Customer cancellation
  does not restore it; event cancellation does.
- A waitlist offer lasts 15 minutes.

The stakeholder also asked for clear answers to five design questions:
global lock ordering, payment reconciliation, refund failure handling, exact
integer allocation, and authoritative time/DST behavior. Those answers are
captured below.

## Scope delivered

Customer APIs and UI cover event discovery, seat availability, holds, promo
quotes, checkout, booking history, policy refunds, payment reconciliation, and
waitlist entry.

Admin APIs and UI cover seat block/release overrides, event cancellation and
rescheduling, exceptional refunds, reports, and audit history. Venue, seat,
event, tier, and promotion data are created through Flyway/demo fixtures rather
than a complete generic admin CRUD interface; this is a deliberate scope cut.

The backend uses Java 21, Spring Boot, JDBC, Flyway, and PostgreSQL. The frontend
uses React, TypeScript, and Vite. Docker Compose provides a reproducible local
environment.

The opt-in demo profile provides three events and 36 event seats across two
sections, with multiple tiers, accessible and blocked inventory, automatic
discounts, and the `SAVE10` and `VIP15` promotion scenarios.

## Decisions I made

### 1. Preventing double sales

PostgreSQL is the final authority for inventory. Mutating operations follow a
shared lock hierarchy: event first, then customer/event counters and inventory,
followed by the operation-specific promo, booking, payment, refund, or waitlist
rows. Rows within a class are locked in stable ID order. Database constraints
provide a second layer of protection.

A confirmed booking and an active hold cannot independently own the same
seat/event inventory row. Concurrency tests repeatedly race two customers for
one seat and require exactly one winner.

### 2. Holds and limits

Hold expiry is calculated from an injected server-side clock. Client clocks are
never trusted. A request may contain at most 6 seats, and an atomic per-customer
event counter enforces the 12-seat combined hold/confirmed limit.

Expired normal holds release inventory. A reservation involved in an uncertain
payment is tracked separately so inventory is not released while payment may
still have succeeded.

### 3. Payment timeout and reconciliation

Every checkout uses an idempotency key. A definite decline releases the hold.
An unknown or timed-out payment becomes `PAYMENT_PENDING`; it does not create a
duplicate charge and does not make the seat available to another customer.

Provider outcomes are controlled inside the provider adapter. Public checkout,
refund, and admin request bodies cannot select success, decline, timeout, or
lookup results. Normal demo calls succeed; integration tests register isolated
one-shot provider scenarios directly in-process to prove failure handling.

The mock provider exposes status lookup, and the booking owner can trigger a
reconciliation endpoint. Provider success confirms the booking once; provider
failure releases inventory once. A bounded background worker also checks stale
pending payments every 30 seconds. It covers both unknown provider results and
the crash window where a provider result was stored before the booking state was
finalized. Actual process-kill and database-interruption tests remain future work.

### 4. Refund failure handling

A refund request first becomes `REQUESTED`, and its tickets become
`CANCELLATION_PENDING`. The related seats remain unavailable until the provider
confirms success. A declined or unknown refund remains unavailable and can be
retried or reconciled. This avoids selling a seat while the customer might
still receive a refund.

Refund requests are idempotent. The booking owner can reconcile an uncertain
customer refund. Broader administrator retry tooling remains a known gap.

### 5. Exact money allocation

All money uses integer minor units. Percentage refunds use floor division per
line, and any remaining minor units are distributed with the largest-remainder
method and a stable seat-ID tie-break. This makes allocation exact,
deterministic, and repeatable.

### 6. Authoritative time and DST

Business time comes from an injected backend `Clock`. Event policy boundaries
are calculated using the venue's IANA time zone. DST gaps and overlaps use the
Java time-zone rules instead of browser-local time or manually chosen offsets.

The database stores instants in `timestamptz`; the venue time-zone identifier is
stored separately for local policy decisions.

### 7. Pricing and promotions

The quoted price is snapshotted onto the booking so later price changes do not
rewrite history. Adjustments use a fixed order: tier price, early-bird, group,
then one promotion. Exclusive promotions suppress the automatic adjustments
that the accepted rule says cannot stack.

Global and per-customer promotion counters are locked and consumed only when a
booking is confirmed. Event cancellation restores usage; customer cancellation
does not.

### 8. Reschedules and cancellations

Rescheduling keeps existing bookings and stores the reschedule timestamp used
for the 14-day refund window. Event cancellation creates full refunds including
service fees, releases inventory only after the required state transition, and
restores eligible promotion usage.

### 9. Waitlist behavior

Waitlist entries preserve FIFO order. An offer holds seats for 15 minutes and
cannot bypass the same inventory and customer-limit rules used by normal
booking. Automatic offer dispatch and notifications were not implemented.

### 10. Audit and reporting

Material administrator actions require a reason and write an append-only audit
record with actor, action, target, reason, and before/after data. Reports are
computed from confirmed transactional records rather than a separate mutable
counter.

### 11. Authentication and security

The frontend stores the bearer token in memory rather than persistent browser
storage. Backend roles are the authorization boundary. Login errors are
generic, passwords use BCrypt, repeated authentication attempts are throttled,
and JSON request sizes are bounded.

Forwarded client IPs are accepted only from configured trusted proxies.
Application containers run as non-root with read-only filesystems. Demo
accounts are disabled by default and require explicit local passwords.

## Main challenges and how I tackled them

- **Same-seat races:** application-only checks were unsafe. I moved ownership
  decisions into PostgreSQL transactions with deterministic row locks and
  constraints, then ran the race repeatedly against real PostgreSQL.
- **Aggregate-limit races:** independently counting holds and bookings allowed
  two concurrent requests to observe the same total. A locked aggregate row now
  serializes changes to the 12-seat limit.
- **Event cancellation with active holds:** the initial flow focused on sold
  seats and could leave active holds behind. Cancellation now resolves all
  affected inventory consistently and has a regression test.
- **Uncertain provider results:** treating a timeout as failure could release a
  paid seat. Explicit pending states and idempotent status lookup keep the seat
  protected until the outcome is known.
- **Test controls leaking into the product:** the first UI and API design let a
  customer choose mock payment/refund outcomes. I removed those public fields;
  deterministic failures are now injected only inside backend tests, while the
  normal demo provider succeeds internally.
- **Refund arithmetic:** percentage rounding could lose or create minor units.
  Largest-remainder allocation makes the total exactly match the policy amount.
- **Venue-local dates:** UTC-only comparisons produce errors around midnight and
  daylight-saving changes. Policy calculations now use the venue's stored IANA
  zone and the injected clock.
- **Database portability:** H2 would not exercise the locks and constraints this
  design depends on. Integration tests use Testcontainers with PostgreSQL and
  run the real Flyway migration.
- **Security boundary review:** review found weak areas around demo credentials,
  request-size handling, proxy-derived client IPs, and vulnerable base images.
  I added fail-closed demo setup, bounded JSON requests, explicit trusted-proxy
  handling, non-root/read-only containers, and updated scanned images.
- **Frontend request races:** an older response could overwrite a newer event
  selection. Requests are now cancelled or ignored when stale, with regression
  coverage.
- **Reproducible verification:** local tool versions and running containers can
  hide failures. Commands and results are kept in a separate verification
  ledger, and Docker state is preserved unless a reset is explicitly required.

## Test and security evidence

The latest completed baseline is:

- 78 backend tests with no failures, errors, or skips;
- 11 frontend unit tests passing;
- frontend lint and production build passing;
- 2 Playwright browser scenarios passing;
- repeated real-PostgreSQL same-seat races with exactly one winner;
- runtime checks for health, stable error responses, security headers, JSON
  body limits, non-root users, and read-only container filesystems;
- no known npm audit findings; and
- no fixed HIGH or CRITICAL findings under the final Trivy release gate for the
  backend and frontend images.

This summary is intentionally not a substitute for evidence. The precise
commands, dates, results, and scenarios are in
[docs/VERIFICATION.md](docs/VERIFICATION.md). A check not recorded there as
`PASS` should not be assumed to have passed.

## Known gaps and next steps

- Add process-kill and database-interruption tests around payment and refund
  reconciliation.
- Run browser tests against the live backend and PostgreSQL; current Playwright
  scenarios isolate the UI with controlled API responses.
- Build generic admin CRUD screens and APIs for venues, layouts, events, price
  tiers, and promotions. The required exceptional admin workflows exist, but
  the general management surface is incomplete.
- Automate waitlist offer creation/expiry and outbound notifications.
- Add server-side logout or token revocation if longer-lived sessions are
  introduced.
- Add administrator tooling for retrying failed or unknown refunds.
- Before production, add TLS, managed secrets, a least-privilege database role,
  centralized/distributed throttling, narrow trusted-proxy networks, production
  startup guards, monitoring, backups, and CI dependency/image scans.

## AI and tools disclosure

I used OpenAI Codex (GPT-5 family) throughout the assignment for requirements
analysis, architecture options, scaffolding, migrations, backend and frontend
implementation, test generation, debugging, security review, documentation,
and verification commands.

I did not treat generated code or explanations as proof. I reviewed the changes
in the repository and ran Maven, Vitest, ESLint, the production frontend build,
Playwright, Testcontainers/PostgreSQL tests, npm audit, Docker runtime checks,
and Trivy scans. Material failures and later corrections are retained in the
verification ledger instead of being presented as if the first attempt passed.

The final implementation decisions and any remaining defects are my
responsibility, including being able to explain the transaction boundaries,
state machines, security controls, and known limitations without relying on the
AI conversation.
