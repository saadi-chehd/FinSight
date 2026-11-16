# FinSight + BankPilot

University graduation project by two equal collaborators. The backend includes PostgreSQL persistence and a deterministic financial calculation engine. React still displays only foundation health status, and Python still exposes only health/docs.

Daily cash-flow roll-forwards, invoice summaries, and configurable daily liquidity warnings are available as internal Java methods. An explicitly generated, labelled synthetic dataset is available for three fictional SMEs. An offline naïve/weekly closing-cash baseline experiment is available. No scenario execution, advanced ML, dashboards, financial HTTP APIs, or loan decisions are implemented.

## Pinned versions

- Node 22.18.0 / npm 10.9.3; React and React DOM 19.3.1; Vite 8.3.4. Frontend dependencies are locked in `frontend/package-lock.json`.
- Java 17 (tested with Temurin 17.0.10); Spring Boot 4.1.1. Keep Java 17 patched for ongoing development.
- Maven Wrapper 3.3.4 downloads Maven 3.10.0. Boot manages Flyway 12.4.0 and PostgreSQL JDBC 42.7.13.
- PostgreSQL 18.6, installed through Homebrew as `postgresql@18` (formula revision 18.6_1). No Docker is required.
- Python 3.14.0; FastAPI 0.143.5 / Uvicorn 0.54.0; resolved runtime dependencies in `forecasting/requirements.txt`.

## Financial calculation rules

Opening cash is a required dated Java input in the SME's currency. Daily closing equals opening plus recorded inflows minus recorded outflows; both date boundaries are inclusive. Invoice summaries use linked payments through an explicit as-of date, while overdue uses the due date. Neither invoices nor scenario assumptions are automatically converted into cash movements.

`LIQUIDITY_WARNING_THRESHOLD` defaults to `0.00` and warns strictly below the buffer; `FINANCIAL_MAX_REPORT_DAYS` defaults to 3660. These optional environment settings are documented in `.env.example`; existing `.env` is preserved. No schema change or migration is needed.

See [formulas, assumptions, and interpretation](docs/financial-engine.md) and [engine verification](docs/financial-engine-verification.md). The engine is inside the existing Java backend; it has no new HTTP routes or UI.

## Synthetic data (manual only)

The default FS1 seed `20261018` covers 2024-01-01 through 2025-12-31: 3 fictional USD SMEs, 942 invoices, 2514 transactions (932 linked payments), 3 dated opening inputs, and 2193 daily rows. It is synthetic software exercise data, not evidence of real SME behavior.

After building the JAR, generate the artifacts and explicitly load/verify with local credentials exported:

```sh
./scripts/synthetic_data.sh generate --seed 20261018 --output data/synthetic/FS1-20261018
./scripts/synthetic_data.sh load --seed 20261018
./scripts/synthetic_data.sh verify --seed 20261018
```

Generation needs no database. Loading is atomic and reruns verify identical rows without duplication; it refuses inconsistent existing data. No automatic startup seeder, schema change, or extra dependency is added. The default seed is already explicitly loaded on this machine. Openings stay in a dated CSV and are supplied to the financial engine, not inserted as transactions.

See [synthetic dataset design and limitations](docs/synthetic-data.md), [data artifacts](data/README.md), and [verification](docs/synthetic-data-verification.md). End-of-dataset payment status is not a historical forecasting feature.

## Milestone 4: offline cash-flow forecasting baseline

The separate Python experiment forecasts each fictional SME's closing cash 7 and 30 calendar days ahead. Initial history is 2024; the holdout is 2025. Both models use 336 common rolling origins per SME (2024-12-31 through 2025-12-01), without future-data inputs, fitting, or scaling.

```sh
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_baseline
```

These commands need no database or third-party experiment packages. Results are under `forecasting/results/fs1-closing-balance-baseline/`; original synthetic files and the financial engine are unchanged. Weekly repetition equals naïve at 7 days and is explicitly recursively extended at 30 days. Its measured 30-day errors were worse than naïve on this synthetic holdout.

See [definitions, exact splits/counts, per-SME metrics, commands, tests, and limitations](docs/forecasting-baseline.md). This host's Intel Java 17 executable could not run; the documented backend verification used an existing native JBR 21 runtime with the Java 17 compilation target preserved. No runtime was installed or permanently reconfigured.

## Database setup

On this machine, PostgreSQL and both databases/roles are already provisioned. Credentials are in ignored root `.env` with owner-only permissions. Do not copy `.env.example` over it, rerun provisioning, or print its secrets.

On a fresh collaborator machine, follow [database setup](docs/database.md). Installation uses Homebrew and is an explicit system change. Run from the repository root. To start this machine's existing server manually:

```sh
mkdir -p .cache
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
```

To stop it manually:

```sh
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -m fast -w stop
```

No automatic login service is enabled. PostgreSQL persists data under `/opt/homebrew/var/postgresql@18`, outside Git. `pg_ctl status` checks whether it is already running; do not start a second instance.

## Install project dependencies and verify

After PostgreSQL setup and `.env` creation, run from the repository root:

```sh
set -a
source .env
set +a
npm --prefix frontend ci --cache .cache/npm
python3 -m venv forecasting/.venv
forecasting/.venv/bin/python -m pip install -r forecasting/requirements.txt --cache-dir .cache/pip
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify
```

`npm ci` uses the lockfile; `venv` isolates Python packages. Maven installs its pinned tool/dependencies into local caches, compiles Java, tests against real `finsight_test`, and creates an executable JAR. Tests fail if the server or test credentials are missing; no H2 substitution or silently skipped database tests are used.

Flyway applies SQL migrations automatically at Java startup and test startup. Versioned files are immutable once applied; add a new `V3__...sql` for later changes. The repeatable comments migration reruns only when its checksum changes. No separate global Flyway install is needed.

## Run applications

Open three terminals at the repository root. Spring/Python commands use exported environment variables; Vite reads root `.env` and exposes only public `VITE_` settings.

Terminal 1 — Java (requires running PostgreSQL):

```sh
set -a
source .env
set +a
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Terminal 2 — Python:

```sh
set -a
source .env
set +a
cd forecasting
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port "${FORECAST_PORT:-8000}"
```

Terminal 3 — React:

```sh
npm --prefix frontend run dev
```

Open http://127.0.0.1:5173. Vite proxies `/api` to Java; no browser-side database credentials are used. Ctrl+C stops each application. If ports change, update `.env` and `VITE_BACKEND_TARGET` consistently. `vite preview` serves the static build without the development API proxy.

## Additional checks

With root `.env` exported:

```sh
python3 scripts/verify_local_database.py
npm --prefix frontend run build
forecasting/.venv/bin/python -m pip check
python3 scripts/smoke_check.py
```

The auth script verifies password enforcement and development/test isolation using local PostgreSQL on port 5432. The smoke check starts all three applications on temporary loopback ports, checks Java liveness, database readiness, Python liveness, Vite → Java proxy, and frontend HTML, then stops only its processes. It can apply pending development migrations. Logs are under ignored `.cache/`.

With applications already running, individual checks are:

```sh
curl --fail http://127.0.0.1:8080/api/health
curl --fail http://127.0.0.1:8080/api/health/database
curl --fail http://127.0.0.1:8000/health
```

## Repository

- `frontend/`: React/Vite foundation page.
- `backend/`: Spring MVC, JDBC persistence, pure financial calculations, Flyway integration, and unit/PostgreSQL tests.
- `forecasting/`: FastAPI health service plus separate standard-library offline baseline experiment/tests/results; no advanced model libraries installed.
- `database/migrations/`: authoritative versioned/repeatable SQL, packaged into the Java JAR.
- `data/`: clearly labelled synthetic CSVs, opening inputs, checksum manifest, and provenance policy.
- `scripts/`: local provisioning, authentication verification, and startup smoke checks.
- `docs/`: [schema](docs/database-schema.md), [database operations](docs/database.md), [architecture](docs/architecture.md), [API](docs/api-contract.md), and [database milestone verification](docs/database-verification.md).

Both collaborators rotate implementation and review across components. Git remains on `main` with no commit or remote. Do not commit `.env`, databases, dependencies, or logs. The next milestone requires review and approval.
