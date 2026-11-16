# PostgreSQL milestone verification — 18 October 2026

Historical record of the database milestone. Subsequent calculation-engine checks are in [financial-engine-verification.md](financial-engine-verification.md).

## Delivered

PostgreSQL 18.6 (Homebrew formula revision `18.6_1`) runs manually on localhost:5432. No login service is enabled. There are separate databases/roles for development and tests, SCRAM password authentication, environment-based credentials, JDBC persistence, versioned Flyway SQL, repeatable schema comments, and actual PostgreSQL integration tests.

Spring Boot stays at 4.1.1 / Java 17; the packaged JAR contains Flyway core/PostgreSQL module 12.4.0 and PostgreSQL JDBC 42.7.13. Frontend and Python versions are unchanged.

## Results

| Check | Exact observed result |
| --- | --- |
| Homebrew install | Successful: PostgreSQL 18.6 installed and default cluster initialized |
| Manual startup | `server started`; `pg_isready`: accepting connections |
| Server configuration | `listen_addresses=localhost`; password encryption SCRAM-SHA-256 |
| Authentication rules | No errors in `pg_hba_file_rules` after reload |
| Role privileges | Both app roles: superuser, create database, create role, replication, bypass RLS all false; stored password format SCRAM |
| Password/isolation script | 9 PASS lines: each role correct password accepted, wrong/missing passwords rejected, cross-database connection denied; .env ignored and mode 0600 |
| First backend `verify` | BUILD SUCCESS; 21 tests, 0 failures, 0 errors, 0 skipped |
| Final offline `verify` after test-guard update | BUILD SUCCESS; 21 tests, 0 failures, 0 errors, 0 skipped |
| Test breakdown | Context 1; financial persistence/constraints 18; migrations 2 |
| Migration behavior | Fresh schema: 3 migrations; unchanged rerun: 0; edited repeatable copy: 1; edited versioned copy: validation rejected as expected |
| Development migration history | V1 financial records, V2 scenario inputs, repeatable schema comments: all successful |
| Actual reads/writes | SME insert, exact BigDecimal transaction round trip, date/instant reads, invoice status/payment links, both scenario input kinds passed on PostgreSQL |
| Invalid data | Java precision/amount checks and database amounts, enums, required fields, dates, references, ownership, currency/direction, and scenario-shape checks passed |
| Empty business tables | Both development and test databases: 0 SMEs, 0 invoices, 0 transactions, 0 scenarios, 0 inputs after fixture cleanup |
| HTTP smoke check | Java liveness, database readiness, Python liveness, Vite → Java proxy, frontend HTML all passed; application processes stopped afterward |
| Frontend regression | Vite production build passed |
| Python regression | pip check: no broken requirements found |
| Python helper syntax | `compileall -q scripts` passed |
| Manual stop/restart | Stop successful; readiness returned no response while stopped; restart successful and accepting connections |
| Login-service check | `brew services list`: postgresql@18 status `none` |
| Git | No commits or remotes created; project sources remain untracked, matching the previous foundation state |

The final database tests were run after the manual restart. Test fixtures are small explicit persistence fixtures, not generated application datasets. Fresh migration schemas and modified migration copies were disposable and removed; repository migration files were not changed by tests.

## Exact operational commands used

Commands below were run at the repository root; real passwords are never embedded. Provisioning SQL and authentication rules are reviewable in the named helpers. File-editing commands are represented by the resulting repository source, not repeated as a generated shell transcript.

Installation and initial startup/inspection:

```sh
HOMEBREW_NO_AUTO_UPDATE=1 brew install postgresql@18
cat /opt/homebrew/var/postgresql@18/pg_hba.conf
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
/opt/homebrew/opt/postgresql@18/bin/psql -X -d postgres -c 'SELECT version();' -c 'SHOW listen_addresses;' -c 'SHOW password_encryption;'
```

Provisioning and auth inspection:

```sh
python3 scripts/setup_local_database.py
python3 scripts/configure_postgres_auth.py
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 reload
/opt/homebrew/opt/postgresql@18/bin/psql -X -d postgres -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolpassword LIKE 'SCRAM-SHA-256%' AS scram_password FROM pg_authid WHERE rolname IN ('finsight_app','finsight_test_app');" -c "SELECT line_number, error FROM pg_hba_file_rules WHERE error IS NOT NULL;"
```

Build, test, and auth checks (each shell exported root .env first):

```sh
set -a
source .env
set +a
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/database-build.log 2>&1
python3 scripts/verify_local_database.py
python3 scripts/smoke_check.py
npm --prefix frontend run build
forecasting/.venv/bin/python -m pip check
python3 -m compileall -q scripts
git check-ignore .env .cache forecasting/.venv frontend/node_modules backend/target
```

Migration/data inspection and packaged dependency check:

```sh
PGPASSWORD="$DB_PASSWORD" /opt/homebrew/opt/postgresql@18/bin/psql -X -w -h 127.0.0.1 -U "$DB_USER" -d finsight -c 'SELECT installed_rank, version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;' -c 'SELECT (SELECT count(*) FROM smes) AS smes, (SELECT count(*) FROM invoices) AS invoices, (SELECT count(*) FROM financial_transactions) AS transactions, (SELECT count(*) FROM scenarios) AS scenarios, (SELECT count(*) FROM scenario_inputs) AS inputs;'
PGPASSWORD="$TEST_DB_PASSWORD" /opt/homebrew/opt/postgresql@18/bin/psql -X -w -h 127.0.0.1 -U "$TEST_DB_USER" -d finsight_test -c 'SELECT (SELECT count(*) FROM smes) AS smes, (SELECT count(*) FROM invoices) AS invoices, (SELECT count(*) FROM financial_transactions) AS transactions, (SELECT count(*) FROM scenarios) AS scenarios, (SELECT count(*) FROM scenario_inputs) AS inputs;'
HOMEBREW_NO_AUTO_UPDATE=1 brew services list
unzip -l backend/target/backend-0.0.1-SNAPSHOT.jar | rg 'BOOT-INF/lib/(flyway|postgresql)|db/migration/'
```

Manual stop/restart and final backend verification:

```sh
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -m fast -w stop
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify > .cache/database-build-final.log 2>&1
git status --short
git remote -v
```

## Warnings, failures, and limits

No build, startup, or test failure remained in this milestone. Rejected invalid writes, rejected passwords, checksum mismatch, and readiness failure during the intentional stop were expected verification outcomes.

The online Maven run emitted nonfatal 401 metadata-discovery warnings for Flyway community support/MavenFeed and a 403 for OW2 snapshot prefixes. Required artifacts resolved, the build passed, and the final offline run passed without these warnings. pip check emitted an unwritable default-cache warning in the restricted shell but still passed; no global cache permissions were changed.

Homebrew installed `icu4c@78` 78.3, OpenSSL 4.0.3, and json-c 0.19; updated ca-certificates 2026-09-25, krb5 1.22.2_2, readline 8.3.6, xz 5.8.4, zstd 1.5.7_1, libunistring 1.5, and gettext 1.0; and cleaned older versions. Installing OpenSSL 4 unlinked the older OpenSSL 3 Homebrew links. The project's Java, frontend, and Python checks passed afterward; unrelated local applications were not tested.

Browser visual rendering, the database-readiness HTTP 503 branch during a live outage, Windows commands, performance/concurrency, and production security/deployment are unverified. No Java–Python business calls, financial calculations, forecasts, synthetic datasets, dashboards, or automatic lending decisions were implemented. No remote or Git commit was created.

PostgreSQL is left manually running for review. Stop it with the documented pg_ctl command; application processes from smoke checks have been stopped. The next milestone requires approval.

## Design references

- [Spring Boot Flyway integration](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [PostgreSQL authentication rules](https://www.postgresql.org/docs/18/auth-pg-hba-conf.html)
- [PostgreSQL numeric semantics](https://www.postgresql.org/docs/18/datatype-numeric.html)
