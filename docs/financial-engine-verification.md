# Financial engine verification — 18 October 2026

Historical engine milestone record. Later synthetic-data tests and loaded-data counts are recorded in [synthetic-data-verification.md](synthetic-data-verification.md).

## Outcome

Implemented deterministic daily cash-flow roll-forward, invoice settlement/overdue summaries, and a configurable daily liquidity warning rule inside the existing Java backend. No schema or Flyway SQL changes, dependency additions, financial HTTP routes, deployed services, forecasts, dashboards, application datasets, commits, or remotes were introduced.

Opening cash is an explicit required dated Java input, not a stored balance or assumed zero. Calculation rules and limitations are in [financial-engine.md](financial-engine.md).

## Exact backend test results

Baseline before implementation: **21 tests; 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

Final full offline Maven verify: **53 tests; 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `FinSightApplicationTests` | 1 | 0 | 0 | 0 |
| `FinancialDatabaseTests` | 18 | 0 | 0 | 0 |
| `MigrationTests` | 2 | 0 | 0 | 0 |
| `FinancialCalculatorTests` | 25 | 0 | 0 | 0 |
| `FinancialEngineIntegrationTests` | 7 | 0 | 0 | 0 |
| Total | 53 | 0 | 0 | 0 |

The new pure tests cover exact decimals, daily inflow/outflow/net/closing, inclusive dates, empty days, leap day, negative opening/closing, strict buffer equality, liquidity warning kinds, required/invalid inputs, duplicate IDs, monetary overflow, invoice partial/complete/excess payments, due-date and payment cutoffs, cancelled/future invoices, separate receivable/payable buckets, status discrepancies, and invalid configuration.

The seven new PostgreSQL tests exercise the injected read-only report service using actual JDBC records: SME isolation, date filtering, multiple movements, empty days, negative closing, configured buffer 10.00, linked partial payments through an explicit date, stored status preservation, invoice exclusion from cash flow, due-date boundaries, overpayments/cancellation, invalid inputs, and overflow without extra writes. They use the existing guarded test database and remove their fixtures.

## Commands executed

From the repository root, with local PostgreSQL already running:

```sh
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
set -a
source .env
set +a
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/financial-baseline.log 2>&1
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/financial-engine-build.log 2>&1
python3 scripts/smoke_check.py
```

Read-only post-test inspection:

```sh
PGPASSWORD="$DB_PASSWORD" /opt/homebrew/opt/postgresql@18/bin/psql -X -w -h 127.0.0.1 -U "$DB_USER" -d finsight -Atc 'SELECT (SELECT count(*) FROM smes), (SELECT count(*) FROM invoices), (SELECT count(*) FROM financial_transactions), (SELECT count(*) FROM scenarios), (SELECT count(*) FROM scenario_inputs), (SELECT count(*) FROM flyway_schema_history WHERE success);'
PGPASSWORD="$TEST_DB_PASSWORD" /opt/homebrew/opt/postgresql@18/bin/psql -X -w -h 127.0.0.1 -U "$TEST_DB_USER" -d finsight_test -Atc 'SELECT (SELECT count(*) FROM smes), (SELECT count(*) FROM invoices), (SELECT count(*) FROM financial_transactions), (SELECT count(*) FROM scenarios), (SELECT count(*) FROM scenario_inputs), (SELECT count(*) FROM flyway_schema_history WHERE success);'
```

Both databases returned `0|0|0|0|0|3`: no rows in the five business tables and the same three successful migrations. No generated application dataset or development fixture was inserted.

## Preservation and startup verification

- PostgreSQL readiness: accepting local connections.
- Existing 21 context/persistence/migration tests passed unchanged.
- Saved SHA-256 hashes of every existing migration matched after implementation; no new migration file exists.
- Packaged Java startup and all five smoke checks passed: Java liveness, database readiness, Python liveness, Vite → Java proxy, and frontend HTML. The smoke helper stopped its application processes afterward.
- Credentials remain in ignored root `.env`, without overwriting it. New optional warning/report-length settings use documented defaults and appear in `.env.example`.
- No system software was installed or upgraded for this milestone. PostgreSQL remains manually running, with no login-service change.

No build or test failures occurred in the final implementation run. The offline build emitted no Maven warning/error lines. Expected validation exceptions and invalid-data rejections are passing test outcomes, not unresolved failures.

## Limits

The engine is an internal Java interface, not a financial REST API or dashboard. Browser visual rendering, simultaneous-writer concurrency behavior, performance/load, persistent opening-balance evidence, historical cancellation/reversal reconstruction, and real-company reconciliation are unverified or out of scope. PostgreSQL snapshot isolation is configured, but no concurrent-writer stress test was performed. No model performance, predictive accuracy, insolvency/default determination, or lending outcome is claimed.

Calculation results are conditional on caller-supplied opening cash, current recorded transactions/links, explicit dates, and the configured threshold. An overdue invoice or negative modeled cash balance is review evidence, not proof of insolvency or loan default.
