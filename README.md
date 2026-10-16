# Lockstep Tickets

Lockstep Tickets is a correctness-first assigned-seat booking system built with
Spring Boot, React, and PostgreSQL. The current checkpoint establishes the
secure, reproducible project foundation; booking workflows are added in later
incremental commits.

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

The expected health response is `{"status":"UP"}`. All non-health backend
routes are denied until their authenticated API slices are implemented.

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

The integration tests use Testcontainers and therefore require Docker.
Flyway runs automatically and owns all schema changes. Hibernate automatic
schema creation is not used.

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
- Actuator exposes only redacted health and info endpoints.
- The application is stateless and all application routes currently default to
  deny. Authentication is introduced with the first user-facing API slice.
- Backend and frontend containers run as non-root with read-only root filesystems.

See [NOTES.md](NOTES.md) for accepted rules and architecture decisions, and
[`docs/VERIFICATION.md`](docs/VERIFICATION.md) for observed test evidence.

