# Synthetic-data milestone verification — 18 October 2026

## Outcome

FS1 seed **20261018** was generated, exported, explicitly loaded into development PostgreSQL, reloaded without duplicates, and verified read-only. Ordinary application startup did not seed extra records. All records/artifacts are labelled synthetic; no real SME behavior or predictive performance is claimed.

No schema/Flyway SQL, engine implementation, existing test source, dependency version, credential, Git commit, or remote changed. The original 53 tests remain intact.

## Backend test results

Before implementation: **53 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

Complete final backend suite: **64 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| FinSightApplicationTests | 1 | 0 | 0 | 0 |
| FinancialDatabaseTests | 18 | 0 | 0 | 0 |
| MigrationTests | 2 | 0 | 0 | 0 |
| FinancialCalculatorTests | 25 | 0 | 0 | 0 |
| FinancialEngineIntegrationTests | 7 | 0 | 0 | 0 |
| SyntheticGeneratorTests (new) | 7 | 0 | 0 | 0 |
| SyntheticDataLoadingTests (new) | 4 | 0 | 0 | 0 |
| Total | 64 | 0 | 0 | 0 |

New generator tests verify the fixed golden fingerprint, equal-seed byte-identical exports, different-seed variation, exact coverage/counts/manifest hashes, payment/status consistency, on-time/late/split coverage, synthetic labels, precision, invalid data, and refusal to overwrite edited artifacts.

New PostgreSQL tests verify successful loading, exact stored row counts, financial-engine reconciliation, repeat-load ID preservation/no duplicates, preservation of unrelated SMEs, refusal of altered or partial namespaces, and full rollback after an injected post-insert validation failure. Tests use reserved seed 900000001 in the guarded `finsight_test` database and clean their own rows.

## Actual default-seed artifacts and database state

| Item | Observed count |
| --- | ---: |
| Synthetic SMEs | 3 |
| Dated external opening inputs | 3 |
| Calendar days per SME | 731 |
| Invoices | 942 |
| Cash transactions, including linked payments | 2514 |
| Linked payment transactions (subset) | 932 |
| Unlinked cash transactions | 1582 |
| Derived daily rows | 2193 |
| PAID invoices at 2025-12-31 | 593 |
| PARTIALLY_PAID invoices at cutoff | 186 |
| UNPAID invoices at cutoff | 163 |
| Fully settled on/before due | 307 |
| Fully settled late | 286 |
| Invoices with multiple observed payments | 153 |
| Outstanding invoices overdue at cutoff | 316 |
| Stored status inconsistent with linked payments at cutoff | 0 |
| Scenario / scenario-input rows | 0 / 0 |

Each company has 314 invoices. Transaction counts are 1100 retail, 565 agency, and 849 workshop. Opening/closing values in USD are 5000.00 → 96934.82, 12000.00 → 131432.78, and 8000.00 → 149213.52. All three default profiles have zero negative closing dates; this dataset is not a cash-shortfall stress dataset.

Development now contains exactly the 3 synthetic SMEs, 942 invoices, and 2514 transactions. The five business tables in the test database are empty after cleanup. Opening values remain in the dated CSV/seed definition, not a new database table or duplicated cash movement.

The default canonical fingerprint is:

```text
d6751da8f13f5af6352ef126c637e14a6f67116a65ad665b8a628cd2cfa88e44
```

Python standard-library CSV/SHA-256/Decimal checks independently verified all five manifest hashes, all invoice payment totals/statuses/ownership/directions, and every one of the 2193 daily rows against transaction totals and prior-day closing. A summary is in ignored `.cache/synthetic-independent-check.json`. No Python libraries were installed.

## Commands actually used

From repository root, with local PostgreSQL already running:

```sh
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
set -a
source .env
set +a
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/synthetic-baseline.log 2>&1
```

An intermediate `-DskipTests package` compiled and packaged the new manual CLI; this was a compilation check, not a passing test run. Then generation and the complete suite ran:

```sh
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" -DskipTests package > .cache/synthetic-compile.log 2>&1
./scripts/synthetic_data.sh generate --seed 20261018 --output data/synthetic/FS1-20261018
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/synthetic-tests.log 2>&1
```

Explicit development load, duplicate-free reload, read-only verification, and identical-artifact regeneration:

```sh
./scripts/synthetic_data.sh load --seed 20261018 > .cache/synthetic-load.log 2>&1
./scripts/synthetic_data.sh load --seed 20261018 > .cache/synthetic-reload.log 2>&1
./scripts/synthetic_data.sh verify --seed 20261018 > .cache/synthetic-verify.log 2>&1
./scripts/synthetic_data.sh generate --seed 20261018 --output data/synthetic/FS1-20261018 > .cache/synthetic-regenerate.log 2>&1
python3 scripts/smoke_check.py
```

The first load reported `Inserted and verified atomically.` Both reload and verify reported `Verified existing dataset; no rows inserted or overwritten.` The logical-key mapping stayed at local SME IDs 1, 2, and 3; database identities are not part of portable artifacts and may differ on another machine.

## Preservation and limits

- Saved SHA-256 hashes confirm every preexisting Flyway SQL file, engine source, and original test source is unchanged. No migration was added.
- No new Maven/Python/frontend dependencies or system software were installed.
- All five existing HTTP startup smoke checks passed after explicit seeding: Java health, database readiness, Python health, Vite → Java proxy, and frontend HTML. A subsequent SQL inspection showed the same 3/942/2514 rows and zero status mismatches. No automatic seeding occurred during startup.
- `.env` remains ignored and owner-only; generated credentials are absent from Git-visible sources/data. No commit or remote was created.
- PostgreSQL remains manually running. Smoke-check application processes were stopped.

No build, export, load, or test failures remained. Expected corrupt-input, altered-artifact, partial-namespace, and injected-failure rejections are passing tests, not unresolved failures. The complete offline Maven build emitted no warning/error lines.

Unverified/out of scope: concurrent-writer stress, real SME calibration, visual browser rendering, CSV import of edited arbitrary data, persistent opening-balance storage, forecasting experiments, generalization/accuracy, financial HTTP APIs, and dashboards. Load/verify regenerate the seed; they do not import CSV edits. Current cutoff payment status is not a safe historical forecasting feature. All design assumptions and right-censoring limitations are documented in [synthetic-data.md](synthetic-data.md).

Stopped after this milestone for review.
