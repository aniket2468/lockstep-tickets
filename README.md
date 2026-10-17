# Lockstep Tickets

Lockstep Tickets is a ticket-booking assignment built with Spring Boot, React,
and PostgreSQL. It focuses on correctness under concurrency: a seat cannot be
sold twice, holds expire after 10 minutes, limits are enforced atomically, and
money is stored as integer minor units.

## What is included

- Customer flows: browse and search events, view a seat map, create a fixed
  10-minute hold, apply one promo code, check out with a mock payment provider, view
  bookings, request refunds, and join a waitlist.
- Admin flows: block or release seats, cancel or reschedule events, issue
  exceptional refunds, review sales reports, and inspect the audit trail.
- Reliability controls: PostgreSQL row locks and constraints, idempotency keys,
  explicit payment and refund states, automatic recovery of uncertain payments,
  exact integer allocation, and tests that race concurrent requests against a
  real PostgreSQL database.

## Quick start with Docker

Prerequisites: Docker with Docker Compose.

1. Create the local environment file:

   ```bash
   cp .env.example .env
   ```

2. In `.env`, set `LOCKSTEP_SPRING_PROFILES_ACTIVE=demo` and replace every
   placeholder password with a unique value of at least 12 characters.

   The demo profile is for local evaluation only. It creates the sample events
   and accounts listed below. The `.env` file is ignored by Git.

3. Build and start the application:

   ```bash
   docker compose up --build
   ```

4. Open:

   - Web application: <http://localhost:3000>
   - Backend health check: <http://localhost:8081/actuator/health>

Demo accounts:

- Customer: `customer@lockstep.test` / the value of
  `LOCKSTEP_DEMO_CUSTOMER_PASSWORD`
- Admin: `admin@lockstep.test` / the value of
  `LOCKSTEP_DEMO_ADMIN_PASSWORD`

The demo catalog contains three events with 12 assigned seats each across
Orchestra and Balcony sections. It includes multiple price tiers, accessible
seats, a few pre-blocked seats, early-bird and group pricing, and two promo
codes:

- `SAVE10`: 10% off any demo event, up to three uses per customer.
- `VIP15`: exclusive 15% off `Concurrency Live`; it does not stack with the
  event's automatic discounts.

Payments and refunds succeed by default in the demo. Provider failures are
simulated internally by the integration tests; customers and administrators do
not choose payment-provider outcomes.

Stop the application without deleting its data:

```bash
docker compose down
```

To remove the local PostgreSQL data and start from an empty database:

```bash
docker compose down --volumes
```

That last command permanently deletes this project's local Docker database.

## Run the tests

Backend tests require Java 21 and Docker. Docker is needed because the
integration tests start a real PostgreSQL instance with Testcontainers.

```bash
cd backend
./mvnw test
```

Frontend checks require Node.js 22.12 or newer. The browser tests also require
Google Chrome.

```bash
cd frontend
npm ci
npm test -- --run
npm run lint
npm run build
npm run test:e2e
```

With the Docker Compose application running, execute the real browser-to-
database workflow using the credentials already stored in the ignored `.env`:

```bash
set -a
source ../.env
set +a
LOCKSTEP_LIVE_E2E_CUSTOMER_EMAIL="$LOCKSTEP_DEMO_CUSTOMER_EMAIL" \
LOCKSTEP_LIVE_E2E_CUSTOMER_PASSWORD="$LOCKSTEP_DEMO_CUSTOMER_PASSWORD" \
npm run test:e2e:live
```

The live test books one available `Lockstep Demo` seat, refunds that exact
ticket, and verifies that the seat is available again. It does not expose mock
provider controls or delete the database.

The current verification baseline is 78 backend tests, 14 frontend unit tests,
2 isolated browser tests, and 1 live-stack browser test. The exact commands,
results, concurrency scenarios, and container scan evidence are recorded in
[docs/VERIFICATION.md](docs/VERIFICATION.md).

## Run locally without containerizing the application

Start only PostgreSQL:

```bash
docker compose up -d database
```

Then run the backend:

```bash
cd backend
SPRING_PROFILES_ACTIVE=demo \
DB_URL=jdbc:postgresql://localhost:5432/lockstep \
DB_USERNAME=lockstep_app \
DB_PASSWORD=replace-with-your-local-password \
LOCKSTEP_DEMO_CUSTOMER_PASSWORD=replace-with-a-12-character-password \
LOCKSTEP_DEMO_ADMIN_PASSWORD=replace-with-another-12-character-password \
LOCKSTEP_PAYMENT_RECONCILIATION_ENABLED=true \
./mvnw spring-boot:run
```

Make sure `DB_PASSWORD` matches `LOCKSTEP_DB_PASSWORD` in `.env`.

In a second terminal, run the frontend development server:

```bash
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173>. The development server proxies API calls to the
backend on port 8080.

## Security notes

- No secret or demo password is stored in the repository.
- Demo data is opt-in and fails closed if required demo passwords are missing.
- Login responses do not reveal whether an account exists, and authentication
  attempts are rate limited.
- Browser sessions use a short-lived opaque token in an `HttpOnly`,
  `SameSite=Strict` cookie, so refresh and browser navigation preserve login
  without exposing the credential to JavaScript or browser storage. Unsafe
  cookie-authenticated requests also require a matching CSRF token.
- Signing out revokes the server-side session immediately and clears both
  cookies. Set `LOCKSTEP_AUTH_COOKIE_SECURE=true` whenever the application is
  served over HTTPS; local HTTP development intentionally keeps it `false`.
- Request bodies and forwarded client-IP headers are handled with explicit
  limits and trusted-proxy rules.
- Application containers run as non-root users with read-only filesystems.
- Authorization is enforced by the backend; hiding controls in React is not
  treated as a security boundary.
- Payment and refund outcomes come from the server-side provider boundary and
  cannot be selected or mass-assigned by API clients.

Before a real deployment, use a secret manager, TLS, a restricted database
role, a narrowly configured trusted-proxy network, centralized rate limiting,
and automated dependency and image scanning.

## Project notes

- [NOTES.md](NOTES.md) explains the instructions, product decisions,
  implementation challenges, trade-offs, known gaps, and AI usage.
- [docs/VERIFICATION.md](docs/VERIFICATION.md) is the detailed evidence ledger.

