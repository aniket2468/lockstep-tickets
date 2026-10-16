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
| Backend unit tests | NOT RUN | — | Backend not created |
| PostgreSQL integration tests | NOT RUN | — | Backend not created |
| Same-seat concurrency repeated | NOT RUN | — | Backend not created |
| Customer-limit concurrency | NOT RUN | — | Backend not created |
| Promo-limit concurrency | NOT RUN | — | Backend not created |
| Checkout/payment idempotency | NOT RUN | — | Backend not created |
| Refund idempotency/concurrency | NOT RUN | — | Backend not created |
| Fresh/upgrade migrations | NOT RUN | — | Migrations not created |
| Authorization/horizontal access | NOT RUN | — | Authentication not created |
| Failure injection/recovery | NOT RUN | — | Application not created |
| Audit atomicity/cardinality | NOT RUN | — | Application not created |
| Frontend tests | NOT RUN | — | Frontend not created |
| Frontend production build | NOT RUN | — | Frontend not created |
| Browser end-to-end tests | NOT RUN | — | Workflows not created |
| Reconciliation assertions | NOT RUN | — | Schema not created |
| Dependency vulnerability scan | NOT RUN | — | Dependencies not selected |
| Container image scan | NOT RUN | — | Images not created |
| Clean Docker Compose startup | NOT RUN | — | Compose not created |
| README clean-run commands | NOT RUN | — | README not created |

The project is **not fully verified** while any release-blocking gate remains
`FAIL`, `BLOCKED`, or `NOT RUN`.
