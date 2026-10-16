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
| Backend unit tests | PASS | `JAVA_HOME=... ./mvnw -q test` on 2026-10-16 | Clock policy, 4 exact integer pricing tests, and 2 refund boundary/DST tests passed on Java 21.0.12.1 |
| PostgreSQL integration tests | PASS | Same Maven run using Testcontainers 2.0.5 | 43 integration tests passed against PostgreSQL 18.6; 50 backend tests total |
| Same-seat concurrency repeated | PASS | 20 two-customer rounds coordinated with latches against PostgreSQL | Every round produced exactly one `201`, one `409`, one hold, and one hold item; no timing sleeps |
| Customer-limit concurrency | NOT RUN | — | Customer-limit feature not implemented |
| Promo-limit concurrency | PASS | Two latch-coordinated checkouts for the final global use | Exactly one checkout returned `201`, one returned `409`, and only one payment attempt/redemption was created |
| Checkout/payment idempotency | PASS | 7 checkout PostgreSQL integration tests | Concurrent and sequential same-key replay created one attempt/booking; changed payload was rejected; success, decline, timeout, lookup, exact expiry, immutable price, and mass-assignment cases passed |
| Refund idempotency/concurrency | PASS | 6 refund PostgreSQL integration tests plus 2 policy tests | Same-key replay made one attempt, changed payload was rejected, and concurrent different-key cancellation produced one `201`, one `409`, and one refund item; boundaries, ownership, fee exclusion, retry, and reconciliation passed |
| Fresh migration | PASS | `./mvnw -q test` against clean Testcontainers | Flyway created `lockstep` and applied V1 through V5 to empty PostgreSQL 18.6 databases |
| Upgrade migrations | PASS | Testcontainers test first targets V1 in a separate schema, then runs unrestricted Flyway | Flyway detected V1 and applied V2/V3/V4/V5, reaching schema version 5 |
| Authentication boundary | PASS | 8 MockMvc/PostgreSQL integration tests | Proved login, hashed opaque sessions, expiry, malformed tokens, inactive-account invalidation, generic failures, account/IP throttling, role denial, strict JSON, no-store responses, and request IDs |
| Authenticated event catalog | PASS | 6 MockMvc/PostgreSQL integration tests | Proved authentication, bounded literal search, deterministic pagination, customer-visible state and retirement filtering, safe 404/validation errors, snapshot seat output, and masking of blocks/internal inventory fields |
| Atomic seat holds | PASS | 7 MockMvc/PostgreSQL integration tests | Proved customer-only access, one-to-six unique seats, fixed ten-minute expiry, start-inclusive/end-exclusive sales, one active hold, aggregate limit, mixed-request rollback, cross-event rejection, lazy expiry/reuse, live catalog masking, and repeated contention |
| Database-test fail-closed behavior | PASS | Focused Maven test with Docker socket inaccessible | Maven exited nonzero with 1 Testcontainers error and 0 skipped tests; PostgreSQL coverage can no longer silently disappear |
| Authorization/horizontal access | PASS | Checkout and refund ownership integration cases | An unrelated customer receives safe `404` responses for another customer's hold checkout, booking/refund view, payment reconciliation, and refund reconciliation |
| Failure injection/recovery | NOT RUN | Success/decline/timeout/unknown and lookup pass | Process/database interruption injection and scheduled recovery worker remain unproved |
| Audit atomicity/cardinality | NOT RUN | — | Admin workflows not implemented |
| Frontend tests | PASS | `npm test -- --run` on 2026-10-15 | 1 file and 6 tests passed with Vitest 5.0.5; covered login errors, in-memory token handling, authenticated fetch, encoded search, availability rendering, and expired sessions |
| Frontend lint | PASS | `npm run lint` on 2026-10-15 | ESLint completed with zero warnings |
| Frontend production build | PASS | `npm run build` on 2026-10-15 | TypeScript and Vite production build completed; 17 modules transformed |
| Browser end-to-end tests | NOT RUN | — | Workflows not created |
| Reconciliation assertions | PASS | Payment and refund timeout-to-success lookup integration cases | Pending inventory remained unavailable; payment reconciliation created exactly one ticket, refund reconciliation released exactly one seat, and foreign reconciliation was hidden |
| Frontend dependency audit | PASS | `npm ci` on host and in container build | 233 runtime/development packages audited; 0 vulnerabilities reported |
| Backend dependency vulnerability scan | NOT RUN | — | Dedicated CVE scanner not run yet |
| Container image scan | NOT RUN | — | Images build successfully; dedicated image scanner not run yet |
| Container runtime hardening | PASS | `docker compose exec ... id` and `docker inspect` on 2026-10-15 | Backend UID 100 and frontend UID 101; both root filesystems read-only |
| Live health and frontend headers | PASS | Local `curl` checks on ports 8081 and 3000 | Health was `UP`, info returned 401 without authentication, UI title loaded, and CSP/referrer/content-type/frame/permissions headers were present |
| Docker Compose build and upgrade | PASS | `docker compose up --build --detach` on 2026-10-16 against the preserved volume | Packaged images built; Flyway detected V4 and applied only V5 in 13 ms; PostgreSQL and backend health were `UP`, the UI loaded, then the stack stopped with its volume preserved |
| README clean-run commands | PASS | Commands executed from the new repository on 2026-10-15 | Maven verify, npm install/test/lint/build, Compose build/start, health check, and teardown behavior verified |
| Catalog/auth runtime smoke test | PASS | Compose rebuild plus local health, UI, and unauthenticated catalog requests on 2026-10-15 | Packaged UI served the expected title, health returned `UP`, and `/api/events` returned the stable `AUTHENTICATION_REQUIRED` envelope with HTTP 401 |
| Secret-pattern review | PASS | Focused `rg` scan of tracked source on 2026-10-15 | Matches were limited to configuration field names, redaction code, and non-secret test fixtures; no private keys or provider tokens found |

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
- The first V2 integration run found that Flyway targeted `lockstep` while
  application JDBC connections still searched `public`; explicit Hikari schema
  selection fixed the mismatch and the entire real-PostgreSQL suite passed.
- That run also found direct `Instant` parameters were ambiguous to the
  PostgreSQL driver. Persistence boundaries now bind explicit SQL timestamps.
- Debug test output revealed generated record strings could expose login
  passwords and returned bearer tokens. Both DTO string representations now
  redact secrets, and the token response is marked `Cache-Control: no-store`.
- Security review found unbounded BCrypt login work, destructive demo-account
  upserts, silently skipped database tests, customer-readable actuator info,
  and overstated production documentation. Account/IP throttles, insert-only
  collision checks, fail-closed Testcontainers, admin actuator rules, and
  corrected scope statements now have regression evidence.
- The first expanded frontend test run retained prior rendered DOM between
  cases because this Vitest setup did not auto-clean. Explicit cleanup restored
  isolation; all six behavior tests, lint, and the production build then passed.
- The first V3 migration attempt lacked the composite `(event_id, id)` unique
  key required by its same-event seat foreign key. PostgreSQL rolled back the
  migration; V3 now creates that key before the hold tables.
- The first full V3 suite retained two migration assertions pinned to schema
  version 2. Updating the regression expectations to version 3 restored the
  complete pass; no functional failure was suppressed.
- The first V4 migration run applied the schema successfully but retained two
  migration assertions pinned to version 3. They now expect version 4.
- The first checkout edge-test run attempted to manufacture expiry by setting
  `expires_at` at/before `created_at`; PostgreSQL correctly rejected the invalid
  fixture. The test now models an old hold while preserving the schema invariant.
- The first refund fixture reused unique booking-reference and checkout-key
  values when a boundary test created two bookings. UUID-derived fixture values
  restored isolation; no production constraint was weakened.
- The first explicit refund key-reuse assertion looked for a top-level error
  code even though the established safe API envelope nests it under `error`.
  The observed response was the intended `409`; the assertion now validates
  `error.code`, and the complete suite is rerun rather than suppressing it.
