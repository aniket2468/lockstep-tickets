# Verification Ledger

Allowed statuses are `PASS`, `FAIL`, `BLOCKED`, and `NOT RUN`. `PASS` requires
an executed command with observed output. This ledger is updated at every
checkpoint and must include failures rather than hiding them.

Environment at project start: macOS workspace with Java 21.0.12.1, Node
22.16.0, npm 10.9.2, Docker 29.5.2, and Docker Compose 5.1.3. The PostgreSQL
server image/version will be recorded when the local environment is created.

| Gate | Status | Command or evidence | Observation |
| --- | --- | --- | --- |
| Accepted rules documented | PASS | Manual comparison to supplied assignment and stakeholder reply on 2026-10-15 | Accepted answers and five required design decisions are recorded in `NOTES.md` |
| Documentation whitespace check | PASS | `awk '/[[:blank:]]+$/ { ... }' NOTES.md docs/*.md` on 2026-10-15 | Exit code 0; no trailing whitespace |
| Backend unit tests | PASS | `JAVA_HOME=... ./mvnw verify -q` on 2026-10-15 | 1 clock-policy test passed on Java 21.0.12.1 |
| PostgreSQL integration tests | PASS | Same Maven verification using Testcontainers 2.0.5 | 3 integration tests passed against PostgreSQL 18.6: connectivity/Flyway, health authorization, and absence of a generated user |
| Same-seat concurrency repeated | NOT RUN | — | Seat-hold feature not implemented |
| Customer-limit concurrency | NOT RUN | — | Customer-limit feature not implemented |
| Promo-limit concurrency | NOT RUN | — | Promo feature not implemented |
| Checkout/payment idempotency | NOT RUN | — | Checkout feature not implemented |
| Refund idempotency/concurrency | NOT RUN | — | Refund feature not implemented |
| Fresh migration | PASS | `./mvnw verify -q` and clean `docker compose up --build --detach` | Flyway created `lockstep` and applied V1 to empty PostgreSQL 18.6 databases |
| Upgrade migrations | NOT RUN | — | No prior domain schema exists yet |
| Authorization/horizontal access | NOT RUN | — | Baseline default-deny/health exception passes; complete identity and ownership matrix awaits authentication slice |
| Failure injection/recovery | NOT RUN | — | Transactional workflows not implemented |
| Audit atomicity/cardinality | NOT RUN | — | Admin workflows not implemented |
| Frontend tests | PASS | `npm test -- --run` on 2026-10-15 | 1 file and 1 test passed with Vitest 5.0.5 |
| Frontend lint | PASS | `npm run lint` on 2026-10-15 | ESLint completed with zero warnings |
| Frontend production build | PASS | `npm run build` on 2026-10-15 | TypeScript and Vite production build completed; 16 modules transformed |
| Browser end-to-end tests | NOT RUN | — | Workflows not created |
| Reconciliation assertions | NOT RUN | — | Transactional domain schema not created |
| Frontend dependency audit | PASS | `npm ci` on host and in container build | 233 runtime/development packages audited; 0 vulnerabilities reported |
| Backend dependency vulnerability scan | NOT RUN | — | Dedicated CVE scanner not run yet |
| Container image scan | NOT RUN | — | Images build successfully; dedicated image scanner not run yet |
| Container runtime hardening | PASS | `docker compose exec ... id` and `docker inspect` on 2026-10-15 | Backend UID 100 and frontend UID 101; both root filesystems read-only |
| Live health and frontend headers | PASS | Local `curl` checks on ports 8081 and 3000 | Health was `UP`, info returned 403, UI title loaded, and CSP/referrer/content-type/frame/permissions headers were present |
| Clean Docker Compose startup | PASS | `docker compose up --build --detach` on 2026-10-15 | Database became healthy; backend and frontend started after PostgreSQL 18 mount correction |
| README clean-run commands | PASS | Commands executed from the new repository on 2026-10-15 | Maven verify, npm install/test/lint/build, Compose build/start, health check, and teardown behavior verified |

The project is **not fully verified** while any release-blocking gate remains
`FAIL`, `BLOCKED`, or `NOT RUN`.

## Resolved Failure History

Failures remain recorded here even after their regression gates pass:

- The first npm resolution rejected TypeScript 7 because typed ESLint supports
  versions below 6.1. TypeScript 6.0.3 was selected; peer checks were not
  bypassed.
- The first frontend run found missing explicit Vitest imports, CSS type
  declarations, and test-aware Vite config typing. Each was corrected before
  the full frontend gate passed.
- The first backend integration run found Spring Boot 4's Flyway auto-
  configuration is now in `spring-boot-starter-flyway`, and also exposed an
  unwanted generated development account. The starter and explicit
  `UserDetailsServiceAutoConfiguration` exclusion are regression-tested.
- The first Compose startup found PostgreSQL 18 requires the data volume at
  `/var/lib/postgresql`, not the older `/var/lib/postgresql/data`. The empty
  test volume was removed and recreated after correcting the mount.
- The next Compose attempt found an unrelated existing container on host port
  8080. Nothing external was stopped; this project now uses configurable host
  port 8081 while retaining container port 8080.
