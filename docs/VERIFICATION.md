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
| Documentation whitespace check | PASS | `awk '/[[:blank:]]+$/ { ... }' README.md NOTES.md docs/*.md` on 2026-10-16 | Exit code 0; no trailing whitespace |
| Backend unit tests | PASS | `JAVA_HOME=... ./mvnw -q test` on 2026-10-16 | Clock policy, 4 exact integer pricing tests, and 2 refund boundary/DST tests passed on Java 21.0.12.1 |
| PostgreSQL integration tests | PASS | Same Maven run using Testcontainers 2.0.5 | 59 integration tests passed against PostgreSQL 18.6; 66 backend tests total |
| Same-seat concurrency repeated | PASS | 20 two-customer rounds coordinated with latches against PostgreSQL | Every round produced exactly one `201`, one `409`, one hold, and one hold item; no timing sleeps |
| Customer-limit concurrency | NOT RUN | Sequential hold, checkout, refund, and waitlist boundary cases pass | A dedicated concurrent race at the aggregate 12-ticket boundary has not been run |
| Promo-limit concurrency | PASS | Two latch-coordinated checkouts for the final global use | Exactly one checkout returned `201`, one returned `409`, and only one payment attempt/redemption was created |
| Checkout/payment idempotency | PASS | 7 checkout PostgreSQL integration tests | Concurrent and sequential same-key replay created one attempt/booking; changed payload was rejected; success, decline, timeout, lookup, exact expiry, immutable price, and mass-assignment cases passed |
| Refund idempotency/concurrency | PASS | 7 refund PostgreSQL integration tests plus 2 policy tests | Same-key replay made one attempt, changed payload was rejected, and concurrent different-key cancellation produced one `201`, one `409`, and one refund item; customer and reschedule boundaries, ownership, fee exclusion, retry, and reconciliation passed |
| Admin inventory/event contention | PASS | 9 admin PostgreSQL integration tests | Ten latch-coordinated block-vs-hold rounds always produced one owner; held/sold blocks, draft lifecycle boundaries, unresolved-payment cancellation, invalid reasons, wrong roles, and duplicate operations left no success audit |
| Waitlist eligibility/concurrency | PASS | 6 waitlist/report PostgreSQL integration tests | Sold-out-only enrollment, section/quantity FIFO, one active entry, 15-minute exact expiry, explicit claim, bypass rejection, 12-ticket skipping, event cancellation, and ten promotion-vs-hold races passed |
| Reconciled admin reports | PASS | Sales and occupancy integration scenario under repeatable-read transactions | Gross 1100 - succeeded refund 1000 = net 100; all 3 seats reconciled exactly across blocked, offered, and unallocated buckets; customer access was denied |
| Fresh migration | PASS | Focused and full Testcontainers migration runs | Flyway created `lockstep` and applied V1 through V7 to empty PostgreSQL 18.6 databases |
| Upgrade migrations | PASS | Testcontainers test first targets V1 in a separate schema, then runs unrestricted Flyway | Flyway detected V1 and applied V2/V3/V4/V5/V6/V7, reaching schema version 7 |
| Authentication boundary | PASS | 8 MockMvc/PostgreSQL integration tests | Proved login, hashed opaque sessions, expiry, malformed tokens, inactive-account invalidation, generic failures, account/IP throttling, role denial, strict JSON, no-store responses, and request IDs |
| Authenticated event catalog | PASS | 6 MockMvc/PostgreSQL integration tests | Proved authentication, bounded literal search, deterministic pagination, customer-visible state and retirement filtering, safe 404/validation errors, snapshot seat output, and masking of blocks/internal inventory fields |
| Atomic seat holds | PASS | 7 MockMvc/PostgreSQL integration tests | Proved customer-only access, one-to-six unique seats, fixed ten-minute expiry, start-inclusive/end-exclusive sales, one active hold, aggregate limit, mixed-request rollback, cross-event rejection, lazy expiry/reuse, live catalog masking, and repeated contention |
| Database-test fail-closed behavior | PASS | Focused Maven test with Docker socket inaccessible | Maven exited nonzero with 1 Testcontainers error and 0 skipped tests; PostgreSQL coverage can no longer silently disappear |
| Authorization/horizontal access | PASS | Checkout, refund, admin, waitlist, and report integration cases | Unrelated customers receive safe `404` for owned resources, customers receive `403` on admin/report operations, and rejected requests create no success audit |
| Failure injection/recovery | NOT RUN | Success/decline/timeout/unknown and lookup pass | Process/database interruption injection and scheduled recovery worker remain unproved |
| Audit atomicity/cardinality | PASS | Admin/waitlist PostgreSQL integration tests and V6 triggers | Successful overrides/promotions created one audit row; rejected operations created none; database triggers rejected audit update and delete; event/inventory/waitlist outbox writes share their operation transaction |
| Frontend workflow tests | PASS | `npm test -- --run` on 2026-10-16 | 1 file and 11 tests passed with Vitest 5.0.5; covered role routing, memory-only tokens, stable errors, 401 logout, encoded/stale search, seat selection, server quotes, idempotency headers, sold-out waitlisting, booking cancellation, offer claim, admin reports/override, explicit-offset time validation, and audit output |
| Frontend stale-response protection | PASS | Controlled unresolved-search test with two successive queries | The first request's `AbortSignal` became aborted, the newer empty result rendered, and the stale promise could not replace it |
| Frontend lint | PASS | `npm run lint` on 2026-10-16 | Type-aware ESLint completed with zero warnings; unsafe form-value coercion and effect-triggered state resets were corrected rather than suppressed |
| Frontend production build | PASS | `npm run build` on 2026-10-16 | TypeScript 6 and Vite 8 production build completed; 21 modules transformed |
| Browser workflow tests | PASS | `npm run test:e2e` using Playwright 1.64.1 and local Chrome on 2026-10-16 | 2 tests passed: an accessible customer seat-to-confirmation flow and a 390×844 admin reconciled-report flow; API responses were deterministically browser-mocked, so live-backend browser E2E remains a separate gap |
| Reconciliation assertions | PASS | Payment and refund timeout-to-success lookup integration cases | Pending inventory remained unavailable; payment reconciliation created exactly one ticket, refund reconciliation released exactly one seat, and foreign reconciliation was hidden |
| Frontend dependency audit | PASS | Playwright dependency install plus container `npm ci` | 236 runtime/development packages audited; npm reported 0 vulnerabilities |
| Backend dependency vulnerability scan | NOT RUN | — | Dedicated CVE scanner not run yet |
| Container image scan | NOT RUN | — | Images build successfully; dedicated image scanner not run yet |
| Container runtime hardening | PASS | `docker compose exec ... id` and `docker inspect` on 2026-10-15 | Backend UID 100 and frontend UID 101; both root filesystems read-only |
| Live health and frontend headers | PASS | Local `curl` checks on ports 8081 and 3000 | Health was `UP`, waitlist and report routes returned `401` without authentication, UI title loaded, and prior CSP/referrer/content-type/frame/permissions header checks remain recorded |
| Docker Compose build and upgrade | PASS | `docker compose up --build --detach` on 2026-10-16 against the preserved volume | Initial run detected V6 and applied only V7 in 12 ms; a final source rebuild validated all 8 migrations at schema version 7. PostgreSQL and backend health were `UP`, the UI loaded, unauthenticated waitlist/report access returned `401`, then the stack stopped with its volume preserved |
| README clean-run commands | PASS | Commands executed from the new repository on 2026-10-15 | Maven verify, npm install/test/lint/build, Compose build/start, health check, and teardown behavior verified |
| Catalog/auth runtime smoke test | PASS | Compose rebuild plus local health, UI, and unauthenticated catalog requests on 2026-10-15 | Packaged UI served the expected title, health returned `UP`, and `/api/events` returned the stable `AUTHENTICATION_REQUIRED` envelope with HTTP 401 |
| Secret-pattern review | PASS | Focused `rg` scan of source on 2026-10-16 | Matches were limited to password variable names, redaction code, and deliberate test-only credentials; no private keys or provider tokens found |

The project is **not fully verified** while any release-blocking gate remains
`FAIL`, `BLOCKED`, or `NOT RUN`.

## Resolved Failure History

Failures remain recorded here even after their regression gates pass:

- The first checkpoint-10 lint run rejected implicit `FormData` object
  stringification and synchronous loading resets inside effects. Typed form
  extraction and user-initiated state transitions replaced both patterns; no
  lint rule was disabled. The original six catalog-era tests also omitted the
  now-required account role and correctly failed closed until their fixtures
  were updated and expanded to eleven workflow tests.
- The first Playwright customer assertion retained a locator whose accessible
  name changed from `available` to `selected` after activation. The test now
  re-locates the selected state and preserves the intentional accessible-name
  transition; both Chrome workflows pass.
- The first combined frontend gate let Vitest discover the Playwright spec;
  Playwright itself passed, but Vitest correctly failed on the foreign test
  runtime. Vitest discovery is now explicitly limited to `src/**/*.test.tsx`
  (and `.ts`), keeping the component and browser runners isolated.

- Waitlist counter review exposed an older hold-path bug: a refreshed combined
  active-plus-confirmed total was written back into the active-hold column,
  double-counting existing tickets. Hold and offer creation now increment only
  the active component after the locked recount; cancellation simply retains
  the recounted value.
- Cross-flow review found that event cancellation closed waitlist offers but
  did not release ordinary active holds or recount their limit rows. Event
  cancellation now locks every live claimed seat, releases both hold classes,
  cancels waiting/offered entries, and recounts the already-locked limits.

- The first V7 waitlist run exposed PostgreSQL's inability to infer the type of
  a null optional-section bind used in an `IS NULL` predicate. The query now
  casts that bound value explicitly to UUID; no string interpolation or relaxed
  validation was introduced.
- The next exact-expiry assertion found that expiring an offer and then raising
  the expected `409` in the same transaction rolled back the expiry marker.
  Claim now commits lazy expiry in a separate short transaction before
  validating the claim, so the boundary rejection leaves durable terminal
  state without weakening seat ownership.
- The first promotion-race assertion expected a promotion-style `200` even
  when the competing direct hold correctly won with `201`. The assertion now
  accepts either valid winner/status pair while still requiring one `409` and
  exactly one durable seat owner.

- The first draft-lifecycle regression run exposed that the new state guard was
  attached to seat preparation instead of rescheduling. The call sites were
  corrected; the test now proves draft inventory remains configurable while
  draft reschedule/cancel operations remain forbidden and unaudited.

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
- The first admin contention assertion assumed the block must win, although the
  accepted invariant permits either the block or hold to serialize first. It
  now accepts `200/409` or `409/201` and separately proves exactly one owner.
- The first audit-feed assertion assumed insertion order for rows with the same
  fixed-clock instant, while the API's deterministic secondary order is UUID.
  It now asserts both rows and their database cardinality without inventing a
  temporal order that the stored data does not contain.
