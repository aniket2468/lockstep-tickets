# Lockstep Tickets

Lockstep Tickets is a correctness-first assigned-seat booking system built with
Spring Boot, React, and PostgreSQL. The current checkpoint includes a secure
authentication boundary, authenticated event search, customer-safe seat
availability, and atomic ten-minute seat holds. Checkout and booking workflows
now include immutable pricing, constrained promo codes, idempotent mock payment,
payment-pending inventory protection, customer-owned reconciliation, and full
or selected-ticket customer cancellation with idempotent mock refunds. Admins
can block/release seats, reschedule/cancel events, issue exceptional refunds,
inspect an append-only audit trail, promote FIFO waitlist offers, and read
transactionally reconciled sales and occupancy reports.

## Prerequisites

- Docker 29+ with Docker Compose 5+
- For host-side development: Java 21 and Node.js 22.12+

No local PostgreSQL installation is required. Development credentials in
`compose.yaml` are intentionally local-only and must not be reused in a shared
or production environment.

## Start the complete application

```bash
docker compose up --build
```

Wait for the database to become healthy and the backend to start, then open:

- Customer UI: <http://localhost:3000>
- Backend health: <http://localhost:8081/actuator/health>

The expected health status is `UP`. Application routes require a database-backed
bearer session, except for the login endpoint.

### Optional demo data

Demo accounts and catalog data are never created by default. To opt in, copy
`.env.example` to the ignored `.env` file, set two unique passwords of at least
12 characters, and set:

```dotenv
LOCKSTEP_SPRING_PROFILES_ACTIVE=demo
LOCKSTEP_DEMO_CUSTOMER_PASSWORD=choose-a-local-password
LOCKSTEP_DEMO_ADMIN_PASSWORD=choose-another-local-password
```

Start Compose, then authenticate using `customer@lockstep.test` or
`admin@lockstep.test` and the password you chose:

```bash
curl --fail-with-body \
  --header 'Content-Type: application/json' \
  --data '{"email":"customer@lockstep.test","password":"YOUR_PASSWORD"}' \
  http://localhost:8081/api/auth/login
```

The response contains a short-lived opaque bearer token. Only its SHA-256 hash
is stored, the response is marked `Cache-Control: no-store`, and disabling an
account invalidates its sessions immediately. Do not put real credentials in
`.env.example`, Compose files, source code, or Git history.

Demo initialization is insert-only and refuses identity collisions. To change
a demo password, delete only the disposable local volume and recreate it; demo
mode never resets, reactivates, or changes the role of an existing account.

The customer UI provides the same sign-in flow, event-name search, event
details, and seat map at <http://localhost:3000>. It deliberately keeps the
access token only in memory, so refreshing or closing the page signs the user
out locally.

Stop the application while preserving local database data:

```bash
docker compose down
```

Delete the local database volume as well:

```bash
docker compose down --volumes
```

## Backend development

Start only PostgreSQL:

```bash
docker compose up -d database
```

Run backend tests with Java 21:

```bash
cd backend
./mvnw verify
```

Run the backend on the host:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/lockstep
export DB_USERNAME=lockstep_app
export DB_PASSWORD=lockstep-local-only
./mvnw spring-boot:run
```

The integration tests use Testcontainers and therefore require Docker. Maven
verification fails rather than silently skipping PostgreSQL tests when Docker
is unavailable. Flyway runs automatically and owns all schema changes.
Hibernate automatic schema creation is not used.

## Frontend development

```bash
cd frontend
npm ci
npm test -- --run
npm run lint
npm run build
npm run dev
```

The Vite development server is available at <http://localhost:5173> and proxies
`/api` and `/actuator` requests to the backend on port 8080.

## Configuration and security

- Runtime database configuration comes from `DB_URL`, `DB_USERNAME`, and
  `DB_PASSWORD`; the application has no embedded password fallback.
- Bind-mounting `.env` is unnecessary. Copy `.env.example` to `.env` only when
  overriding local Compose values; `.env` files are ignored by Git.
- Actuator exposes redacted health publicly; other actuator routes require
  authentication and are not exposed beyond the configured allowlist.
- Authentication uses 32-byte opaque bearer tokens with only SHA-256 hashes in
  PostgreSQL, a maximum 24-hour configurable lifetime (30 minutes by default),
  BCrypt password hashes, live active-account checks, exact-origin CORS, and
  server-derived IDs and roles.
- Invalid credentials deliberately do not reveal whether an account exists or
  is inactive. PostgreSQL-backed account and source-IP throttles bound login
  attempts and return `429` plus `Retry-After`. Request bodies reject unknown
  fields and scalar coercion.
- `GET /api/events` and `GET /api/events/{eventId}` require authentication.
  Search is literal and bounded, pagination is bounded, retired/draft/internal
  event states are hidden, and seat inventory is exposed only as `AVAILABLE`
  or `UNAVAILABLE` so blocks and ownership details cannot leak.
- `POST /api/events/{eventId}/holds` accepts one to six unique event-seat IDs
  for customer accounts. Holds use a fixed ten-minute server deadline, permit
  only one active hold per customer/event, enforce the 12-ticket aggregate
  limit, and return `409` without partial allocation when any seat is invalid,
  blocked, or already held.
- Hold quotes are computed only from server-side tier, early-bird, group, promo,
  and flat-fee rules. Aggregate and per-ticket integer-minor-unit allocations
  are persisted at hold creation and validated again before checkout.
- `POST /api/holds/{holdId}/checkout` requires a customer-owned, unexpired hold
  and an `Idempotency-Key` header of 8–100 characters. Its mock body selects
  `SUCCESS`, `DECLINE`, `TIMEOUT`, or `UNKNOWN`; retries with the same key and
  payload never create a second payment attempt, while changed payloads are
  rejected. `POST /api/bookings/{bookingId}/reconcile` resolves customer-owned
  ambiguous payments through the configured mock lookup result.
- Payment calls occur without database row locks. Unknown payments retain
  explicit payment-pending inventory; success creates immutable charged ticket
  components and one transactional outbox event, while decline releases seats.
- `GET /api/bookings` and `GET /api/bookings/{bookingId}` expose only the
  authenticated customer's bookings. `POST /api/bookings/{bookingId}/cancellations`
  accepts one to six unique ticket IDs and an idempotency key. Customer refunds
  are 100% at least seven days before the event, 50% from exactly 48 hours to
  seven days, and 0% below 48 hours; the per-order service fee is excluded.
- Refund provider calls also occur without database row locks. A timeout or
  unknown result keeps tickets in `CANCELLATION_PENDING` and unavailable until
  customer-owned reconciliation succeeds. Declined refunds can be retried with
  the original key, while row locks and persisted refund items prevent duplicate
  or concurrent over-refunds.
- `/api/admin/**` requires the `ADMIN` role. Seat block/release, event
  reschedule/cancellation, and exceptional refund requests require a bounded,
  non-blank reason. Their business mutation, outbox entry where applicable,
  and audit record commit atomically; PostgreSQL rejects audit updates/deletes.
- A block is rejected for held, payment-pending, or sold inventory. Rescheduling
  preserves bookings and opens a 14-day, end-inclusive full-refund window that
  includes allocated service fees. Event cancellation rejects unresolved
  payments, closes sales, restores consumed promo uses, and refunds every
  remaining charged minor unit exactly once, including prior partially refunded
  tickets. Mock refund provider calls occur only after the setup transaction.
- Customers may join `/api/events/{eventId}/waitlist` only when their requested
  quantity is unavailable, optionally within a venue section. There is one
  active entry per customer/event. Admin promotion scans FIFO for the earliest
  currently eligible quantity/section request, skips customers at the 12-ticket
  limit, and creates a 15-minute exclusive offer without purchasing anything.
- Offered seats are hidden from availability and cannot be checked out until
  their owner explicitly claims the offer. Claiming ends the offer and creates
  a fresh, immutable-price ten-minute hold. Exact-boundary expiry, customer
  cancellation, and event cancellation release offers without overlapping
  another seat claim. Promotion is explicitly admin-triggered in this scoped
  release; a background promotion/notification worker is deferred.
- Admin sales reports reconcile immutable gross ticket charges, succeeded and
  pending refunds, and net sales in one repeatable-read transaction. Occupancy
  reports classify every seat exactly once as blocked, sold, payment-pending,
  waitlist-offered, held, or unallocated and return the reconciled total.
- Backend and frontend containers run as non-root with read-only root filesystems.

See [NOTES.md](NOTES.md) for accepted rules and architecture decisions, and
[`docs/VERIFICATION.md`](docs/VERIFICATION.md) for observed test evidence.

