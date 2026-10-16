# Lockstep Tickets

Lockstep Tickets is a correctness-first assigned-seat booking system built with
Spring Boot, React, and PostgreSQL. The current checkpoint includes a secure
authentication boundary plus authenticated event search and customer-safe seat
availability. Seat holds and booking workflows follow in later incremental
commits.

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
- Backend and frontend containers run as non-root with read-only root filesystems.

See [NOTES.md](NOTES.md) for accepted rules and architecture decisions, and
[`docs/VERIFICATION.md`](docs/VERIFICATION.md) for observed test evidence.

