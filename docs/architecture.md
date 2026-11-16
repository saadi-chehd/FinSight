# Architecture

## Implemented

Browser → Vite development server → Spring Boot → PostgreSQL 18.
Python FastAPI remains independent, exposing only health and API docs. Java–Python business integration is deferred.

The Vite `/api` proxy avoids opening broad CORS access. Servers bind to loopback by default; these unauthenticated development applications are not production deployments.

Spring JDBC provides a small explicit SQL persistence boundary with `JdbcClient`; no ORM or entity generation is introduced. `FinancialRecordRepository` inserts SMEs and actual transaction records and reads an amount by SME and record ID. Validation rejects fractional minor units before PostgreSQL could round them. There are no public financial CRUD routes.

Flyway is the only schema initializer. `database/migrations/*.sql` is copied by Maven into `db/migration` in the JAR. Boot runs migrations before completing startup. Development uses `finsight` / `finsight_app`; tests use `finsight_test` / `finsight_test_app`. Both roles own their own database for local migration permissions and have no superuser/role creation/database creation/replication/bypass-RLS privileges. A production deployment would separate migration ownership from runtime privileges.

Credentials come from environment variables exported from ignored `.env`. Vite exposes only `VITE_` variables; never prefix database secrets with `VITE_`. The test configuration overrides both JDBC and Flyway credentials after checking an exact loopback test URL and test-role name.

`/api/health` remains process liveness. `/api/health/database` performs a live `SELECT 1`; it does not assess SME data completeness or model readiness.

## Deterministic engine

Within the existing backend, `FinancialReadRepository` reads SME-scoped records and `FinancialReportService` uses one read-only REPEATABLE READ snapshot per report. `FinancialCalculator` is a pure BigDecimal engine; immutable inputs/results live in `FinancialModels`. It calculates daily closing cash, invoice settlement summaries, and configurable daily liquidity warnings. It never writes calculated values, changes recorded invoice status, calls Python, or turns invoices into assumed cash receipts. Opening cash is an explicit dated input, so no schema change is needed.

See [financial-engine.md](financial-engine.md) for formulas, currency/precision, date boundaries, status discrepancies, and warning interpretation. Existing health endpoints and financial write repository behavior are preserved. No financial HTTP route has been added.

## Boundaries for future milestones

Scenario execution, forecast integration, and banker workflows remain deferred. Python will own future feature preparation, predictions, and chronological evaluation. Scenario inputs remain stored assumptions, separate from actual transactions. Recorded invoice status is retained; the engine computes a separate effective status without updating it.

## Explicit synthetic tooling

`com.finsight.synthetic` contains plain generator/exporter/loader/CLI classes with no Spring component, startup hook, or automatic seeding. `scripts/synthetic_data.sh` selects a separate main from the existing JAR; no additional deployed service is added. Standard JDK code generates labelled CSV artifacts from a fixed seed; the manual loader uses existing JDBC tables and verifies them through the financial engine in one transaction. No schema or dependency changes are needed.

No datasets are seeded by ordinary startup, setup, or migrations. The default fictional dataset has been explicitly loaded through the manual CLI. Tests use a separate reserved seed namespace in the guarded test database, preserve unrelated fixtures, and remove their own data. See [synthetic-data.md](synthetic-data.md). There are no dashboards or automatic loan decisions.
