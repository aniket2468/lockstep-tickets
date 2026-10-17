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
| Backend unit tests | PASS | `JAVA_HOME=... ./mvnw test` rerun on 2026-10-19 | 15 unit/policy/security tests passed on Java 21.0.12.1, including trusted-proxy parsing and declared/chunked-style JSON size limits |
| PostgreSQL integration tests | PASS | Same Maven run using Testcontainers 2.0.5 | 64 integration tests passed against PostgreSQL 18.6; 79 backend tests total with 0 failures, errors, or skips |
| Same-seat concurrency repeated | PASS | 20 two-customer rounds coordinated with latches against PostgreSQL | Every round produced exactly one `201`, one `409`, one hold, and one hold item; no timing sleeps |
| Customer-limit concurrency | PASS | 20 coordinated PostgreSQL races from 10 confirmed tickets with two concurrent two-seat requests | Every round produced exactly one success and one `CUSTOMER_EVENT_LIMIT_EXCEEDED`; the final aggregate remained exactly 12 |
| Promo-limit concurrency | PASS | Two latch-coordinated checkouts for the final global use | Exactly one checkout returned `201`, one returned `409`, and only one payment attempt/redemption was created |
| Checkout/payment idempotency | PASS | 8 checkout PostgreSQL integration tests | Concurrent and sequential same-key replay created one attempt/booking; changed payload was rejected; success, decline, timeout, manual and scheduled lookup, exact expiry, immutable price, and mass-assignment cases passed |
| Refund idempotency/concurrency | PASS | 7 refund PostgreSQL integration tests plus 2 policy tests | Same-key replay made one attempt, changed payload was rejected, and concurrent different-key cancellation produced one `201`, one `409`, and one refund item; customer and reschedule boundaries, ownership, fee exclusion, retry, and reconciliation passed |
| Admin inventory/event contention | PASS | 9 admin PostgreSQL integration tests | Ten latch-coordinated block-vs-hold rounds always produced one owner; held/sold blocks, draft lifecycle boundaries, unresolved-payment cancellation, invalid reasons, wrong roles, and duplicate operations left no success audit |
| Waitlist eligibility/concurrency | PASS | 6 waitlist/report PostgreSQL integration tests | Sold-out-only enrollment, section/quantity FIFO, one active entry, 15-minute exact expiry, explicit claim, bypass rejection, 12-ticket skipping, event cancellation, and ten promotion-vs-hold races passed |
| Reconciled admin reports | PASS | Sales and occupancy integration scenario under repeatable-read transactions | Gross 1100 - succeeded refund 1000 = net 100; all 3 seats reconciled exactly across blocked, offered, and unallocated buckets; customer access was denied |
| Fresh migration | PASS | Focused and full Testcontainers migration runs | Flyway created `lockstep` and applied V1 through V8 to empty PostgreSQL 18.6 databases |
| Upgrade migrations | PASS | Testcontainers test first targets V1 in a separate schema, then runs unrestricted Flyway | Flyway detected V1 and applied V2 through V8, reaching schema version 8 |
| Authentication boundary | PASS | Full backend suite plus focused 10-test authentication rerun on 2026-10-19 | Proved hashed opaque sessions, `HttpOnly`/`SameSite=Strict`/configurable `Secure` cookies, cookie and bearer authentication, CSRF rejection for unsafe cookie requests, database revocation and cookie clearing on logout, expiry, malformed tokens, inactive-account invalidation, generic failures, proxy rules, throttling, request limits, role denial, strict JSON, no-store responses, and request IDs |
| Authenticated event catalog | PASS | 6 MockMvc/PostgreSQL integration tests | Proved authentication, bounded literal search, deterministic pagination, customer-visible state and retirement filtering, safe 404/validation errors, snapshot seat output, and masking of blocks/internal inventory fields |
| Atomic seat holds | PASS | 9 MockMvc/PostgreSQL integration tests | Proved customer-only access, one-to-six unique seats, fixed ten-minute expiry, start-inclusive/end-exclusive sales, one active hold, aggregate limit, mixed-request rollback, cross-event rejection, lazy expiry/reuse, owned active-hold recovery without cross-customer disclosure, admin denial, live catalog masking, repeated same-seat contention, and repeated customer-limit contention |
| Database-test fail-closed behavior | PASS | Focused Maven test with Docker socket inaccessible | Maven exited nonzero with 1 Testcontainers error and 0 skipped tests; PostgreSQL coverage can no longer silently disappear |
| Authorization/horizontal access | PASS | Checkout, refund, admin, waitlist, and report integration cases | Unrelated customers receive safe `404` for owned resources, customers receive `403` on admin/report operations, and rejected requests create no success audit |
| Scheduled payment recovery | PASS | Bounded PostgreSQL integration scenario with stale and fresh attempts | Two-item batches resolved stored-success, lookup-success, and lookup-decline results; unknown remained protected and was deferred; fresh work was ignored |
| Process/database interruption | NOT RUN | State-level crash window is covered | No test currently kills the application process or interrupts PostgreSQL mid-operation |
| Audit atomicity/cardinality | PASS | Admin/waitlist PostgreSQL integration tests and V6 triggers | Successful overrides/promotions created one audit row; rejected operations created none; database triggers rejected audit update and delete; event/inventory/waitlist outbox writes share their operation transaction |
| Frontend workflow tests | PASS | `npm test -- --run` rerun on 2026-10-19 | 1 file and 16 tests passed with Vitest 5.0.5; covered secure cookie requests, customer/admin refresh restoration, browser-history cache restoration, active-checkout recovery without browser storage, CSRF-authenticated explicit logout, local handling of expired sessions, role routing, stable errors, encoded/stale search, seat selection, server quotes, idempotency headers, absence of provider-outcome controls, sold-out waitlisting, booking cancellation, offer claim, admin reports/override, explicit-offset time validation, and audit output |
| Provider simulation boundary | PASS | Strict JSON checkout test plus frontend request assertions on 2026-10-16 | Public customer/admin DTOs no longer accept payment, refund, or lookup outcomes; normal calls use internal success and failure scenarios are registered only inside integration tests |
| Demo catalog fixture | PASS | `DemoProfileIntegrationTest` on 2026-10-16 | Opt-in demo startup created 2 hashed accounts, 3 events, 36 event seats, 2 sections, multiple tiers, blocked/accessible examples, and 2 promotions without destructive updates |
| Frontend stale-response protection | PASS | Controlled unresolved-search test with two successive queries | The first request's `AbortSignal` became aborted, the newer empty result rendered, and the stale promise could not replace it |
| Frontend lint | PASS | `npm run lint` rerun on 2026-10-19 | Type-aware ESLint completed with zero warnings |
| Frontend production build | PASS | `npm run build` rerun on 2026-10-19 | TypeScript 6 and Vite 8 production build completed; 21 modules transformed |
| Browser workflow tests | PASS | `npm run test:e2e` using Playwright 1.64.1 and local Chrome on 2026-10-19 | 2 fast isolated tests passed: an accessible customer seat-to-confirmation flow and a 390×844 admin reconciled-report flow; the separate live-stack gate is recorded below |
| Live-stack browser workflow | PASS | Live Playwright runs against the rebuilt Compose frontend, Spring backend, and PostgreSQL on 2026-10-19 | The booking/refund workflow created a real hold, refreshed on the checkout screen, rediscovered the owned hold, completed payment, refunded the ticket, and proved seat reuse. Separate focused customer and admin scenarios proved both roles survive refresh and leaving/returning with browser Back; credentials came from the ignored `.env` |
| Reconciliation assertions | PASS | Payment and refund timeout-to-success lookup integration cases | Pending inventory remained unavailable; payment reconciliation created exactly one ticket, refund reconciliation released exactly one seat, and foreign reconciliation was hidden |
| Frontend dependency audit | PASS | Clean container `npm ci` rerun on 2026-10-19 | 238 dependency packages installed and 239 packages including the root project audited; npm reported 0 vulnerabilities |
| Backend dependency vulnerability scan | PASS | Trivy 0.74.0 against exported rebuilt backend image on 2026-10-16; fixed HIGH/CRITICAL gate with `--ignore-unfixed --exit-code 1` | Alpine 3.24.2: 0; packaged application JAR: 0. Initial scan found and blocked 3 CRITICAL Tomcat and 5 HIGH Jackson findings before version overrides |
| Container image scan | PASS | Trivy 0.74.0 against exported rebuilt frontend image on 2026-10-16; same gate | Alpine 3.23.4: 0. Initial scan found and blocked 43 HIGH findings before runtime package upgrades |
| Container runtime hardening | PASS | `docker compose exec ... id` and `docker inspect` on 2026-10-16 | Backend UID 100 and frontend UID 101; both root filesystems read-only |
| Live health and frontend headers | PASS | Local `curl` checks on ports 8081 and 3000 on 2026-10-16 | Both health paths were `UP`; CSP/referrer/content-type/frame/permissions headers were present; nginx and direct backend independently rejected a 70 KB login with `413`, and the backend returned the stable redacted error envelope |
| Docker Compose build and upgrade | PASS | `docker compose up --build --detach` rerun on 2026-10-19 against the preserved volume | Both application images rebuilt, all three services started, PostgreSQL remained healthy, and backend health was `UP` |
| README clean-run commands | PASS | Commands executed from the new repository on 2026-10-15 | Maven verify, npm install/test/lint/build, Compose build/start, health check, and teardown behavior verified |
| Catalog/auth runtime smoke test | PASS | Compose rebuild plus local health, UI, and unauthenticated catalog requests on 2026-10-15 | Packaged UI served the expected title, health returned `UP`, and `/api/events` returned the stable `AUTHENTICATION_REQUIRED` envelope with HTTP 401 |
| Secret-pattern review | PASS | Focused `rg` scan of source on 2026-10-16 | Matches were limited to password variable names, redaction code, and deliberate test-only credentials; no private keys or provider tokens found |

Verification status is intentionally conservative. `NOT RUN` identifies an
explicitly documented scope or resilience gap and is not a passing result;
`FAIL` and `BLOCKED` are release blockers.

## Resolved Failure History

Failures remain recorded here even after their regression gates pass:

- Refreshing checkout originally preserved the database hold but lost its ID
  from React memory. The seat correctly remained unavailable, but the customer
  had no path to pay for it. An authenticated ownership-scoped active-hold read
  now lazily expires stale holds and resumes checkout without storing hold or
  credential data in browser storage. Backend isolation/expiry tests, a
  frontend reload regression test, and the real hold-refresh-pay-refund browser
  workflow all pass.

- A clean Compose rebuild failed with TypeScript `TS2688` because
  `tsconfig.node.json` requested Node types while `@types/node` existed only as
  an optional peer reference. Incremental local output had hidden the missing
  declaration. `@types/node` is now an exact development dependency; forced
  TypeScript compilation, tests, lint, production build, clean container
  `npm ci`, Compose build, and live health all pass.
- The first combined Back-navigation plus booking rerun reached the authenticated
  catalog after both refresh and browser Back, then encountered an unrelated
  pre-existing active demo hold and correctly received
  `ONE_ACTIVE_HOLD_PER_EVENT`. Navigation is now a separate non-mutating live
  scenario so session behavior is verified independently of mutable demo data.
- The first browser-history restoration change synchronously entered loading
  state through an effect helper, and the type-aware React lint rule rejected
  it. Initial restoration now uses its already-initialized loading state, while
  history events enter loading state from the event callback; all 15 tests,
  lint, and the production build pass.
- The first authentication-suite rerun used an unsupported multi-matcher
  overload for Spring's header assertion and failed during test compilation.
  The assertion now inspects the complete `Set-Cookie` header collection;
  the full 78-test suite and focused 10-test authentication rerun both pass.
- The first live-E2E lint gate failed because the new config and spec were not
  included in the type-aware TypeScript projects. Both are now compiled and
  linted without a parser exemption. The first browser run then received the
  intended CORS `403` because it used `127.0.0.1` instead of the documented
  `localhost` origin; the live config now uses the allowed origin without
  widening CORS. The next run completed checkout but expected the mocked
  `LS-` reference prefix instead of the real `LT-` prefix. That exact test
  booking was refunded through the normal authenticated API, the locator was
  generalized, and two complete booking/refund/seat-reuse runs passed.
- The first scheduler-isolation change added a test `application.yml`, which
  shadowed rather than merged the main datasource configuration. The full suite
  failed closed while creating its application contexts. The extra resource was
  removed; reconciliation is now explicitly enabled by Compose and the focused
  worker test, while other test contexts keep scheduling disabled.
- The first frontend rerun after removing provider-outcome controls passed 10
  of 11 tests. One session test asserted the authenticated catalog request
  before React had issued it; the production behavior was correct. The test now
  waits for the request explicitly, and the full 11-test suite, lint, and build
  pass without timing assumptions.
- The first release image scan blocked 3 CRITICAL Tomcat, 5 HIGH Jackson, and
  43 HIGH frontend Alpine findings. Spring-managed version overrides moved
  Tomcat to 11.0.25 and Jackson to 3.1.7; the nginx runtime now upgrades its
  installed Alpine packages during the image build. Rebuilt-image scans report
  zero fixed HIGH/CRITICAL findings across both OS layers and the backend JAR.
- The release security review found that nginx made every browser login appear
  to originate from one container address and that Tomcat's form-post setting
  did not bound JSON. A trusted-proxy resolver now uses the rightmost untrusted
  literal only behind configured proxies, while a pre-deserialization filter
  and nginx both enforce 64 KiB. Tests cover spoofed/malformed chains, distinct
  proxied clients, declared and absent content lengths, and exact-limit bodies.
- The first final frontend test/build invocation inherited system Node 18 and
  failed before test discovery because Vite requires Node 22. Re-running under
  the documented Node 22.16.0 passed all 11 tests, lint, and the build. The first
  Playwright attempt was likewise blocked before discovery by sandbox port
  permissions; allowing its local preview port produced 2 passing workflows.

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
