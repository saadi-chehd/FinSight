# FinSight + BankPilot

University graduation project by two equal collaborators. The application pairs a Spring Boot + PostgreSQL deterministic financial calculation and scenario engine with a Python FastAPI forecasting service (`CM1` models + split-conformal intervals) and an interactive React 19 + Vite **FinSight + BankPilot** dashboard.

## Implemented Capabilities

- **Deterministic Financial Engine & Read APIs (Java / PostgreSQL):** Daily cash-flow roll-forwards with explicit opening cash (`openingDate`, `openingAmount`, `currency`, `endDate`), configurable `LIQUIDITY_WARNING_THRESHOLD`, invoice settlement & overdue aging (`INFLOW` receivables and `OUTFLOW` payables kept separate, never silently netted, with `statusMismatch` detection), and recorded transactions ledger queries.
- **Scenario Engine & Simulation APIs (Java):** Persistent `BASELINE`, `LATE_PAYMENT`, and `NEW_CONTRACT` scenario CRUD plus read-only `REPEATABLE_READ` simulations comparing baseline vs. scenario closing cash and liquidity warnings without mutating ledger records.
- **Cash-Flow Forecasting (`CM1`) & Bridge (Python + Java):** FastAPI `POST /forecast` service implementing `drift_calendar`, `ridge_lag_calendar`, and `naive` models with empirical 80% and 95% split-conformal prediction intervals over rolling past residuals, exposed through the Java `GET /api/smes/{smeId}/forecast` bridge.
- **Interactive React Dashboard (`FinSight + BankPilot`):**
  - **FinSight SME Workspace:** Live backend/database health indicators, explicit opening-cash controls (never silently defaulted to `0.00`, with a clearly labelled one-click FS1 synthetic preset), synthetic data provenance banner, `Cash Flow & Forecast` view (KPIs, interactive daily balance + 80%/95% conformal interval chart, model/horizon diagnostics, roll-forward table + CSV export), `Invoices & Ledger` view (side-by-side receivables/payables cards, 6-bucket aging table, status-mismatch & overdue filtering, transactions ledger + CSV export), and `Scenario Simulator` view (create/delete/simulate scenarios with dual-line comparison chart and daily deltas).
  - **BankPilot Analyst Review:** Human-governed portfolio comparison across SMEs with per-row explicit opening cash inputs, audited liquidity/overdue/forecast indicators, clickable Review Dossier with calculation provenance, analyst checklist & notes, printable/exportable Markdown brief, and an explicit governance boundary (no automated credit score, default probability, or loan decision).

## Pinned versions

- Node 24 / npm 11 (minimum `^20.19.0 || >=22.12.0`); React and React DOM 19.3.1; Vite 8.3.4; Vitest 5.1.2; `lucide-react` 1.80.0. Frontend dependencies are locked in `frontend/package-lock.json`.
- Java 17 (tested with Temurin 17.0.10 / JBR 21 targeting Java 17); Spring Boot 4.1.1.
- Maven Wrapper 3.3.4 downloads Maven 3.10.0. Boot manages Flyway 12.4.0 and PostgreSQL JDBC 42.7.13.
- PostgreSQL 18.6, installed through Homebrew as `postgresql@18` (formula revision 18.6_1). No Docker is required.
- Python 3.14.0; FastAPI 0.143.5 / Uvicorn 0.54.0; resolved runtime dependencies in `forecasting/requirements.txt`.

## Financial calculation & governance rules

- **Explicit opening cash:** Opening cash is a mandatory dated input in the SME's currency and is never silently defaulted to `0.00`. Daily closing equals opening plus recorded inflows minus recorded outflows; both date boundaries are inclusive.
- **Exact 2-decimal monetary precision:** Monetary inputs and outputs use exact 2-decimal strings (`BigDecimal` / `NUMERIC(19,2)` on backend, `BigInt` minor-unit math in frontend), rejecting fractional minor units (`> 2` decimal places).
- **Invoice & scenario separation:** Invoice summaries use linked payments through an explicit as-of date, while overdue uses the due date. Neither invoices nor scenario assumptions mutate stored ledger movements.
- **Configurable liquidity rule:** `LIQUIDITY_WARNING_THRESHOLD` defaults to `0.00` and warns strictly below the buffer (`POTENTIAL_CASH_SHORTFALL` when negative, `BELOW_CONFIGURED_BUFFER` otherwise); `FINANCIAL_MAX_REPORT_DAYS` defaults to `3660`.
- **Conditional-analysis & BankPilot boundary:** Cash-flow reports, scenario simulations, and forecasts are conditional mathematical tools, not solvency proofs or lending decisions. BankPilot serves strictly as a human-governed financial review interface.

See [formulas, assumptions, and interpretation](docs/financial-engine.md), [engine verification](docs/financial-engine-verification.md), [API contract](docs/api-contract.md), and [forecasting models (`CM1`)](docs/forecasting-models.md).

## Synthetic data (manual only)

The default FS1 seed `20261018` covers `2024-01-01` through `2025-12-31`: 3 fictional USD SMEs (`FS1-20261018-A`: `5000.00`, `FS1-20261018-B`: `12000.00`, `FS1-20261018-C`: `8000.00`), 942 invoices, 2514 transactions (932 linked payments), 3 dated opening inputs, and 2193 daily rows. It is synthetic software exercise data, not evidence of real SME behavior.

After building the JAR, generate the artifacts and explicitly load/verify with local credentials exported:

```sh
./scripts/synthetic_data.sh generate --seed 20261018 --output data/synthetic/FS1-20261018
./scripts/synthetic_data.sh load --seed 20261018
./scripts/synthetic_data.sh verify --seed 20261018
```

Generation needs no database. Loading is atomic and reruns verify identical rows without duplication; it refuses inconsistent existing data. No automatic startup seeder, schema change, or extra dependency is added. Openings stay in a dated CSV and are supplied explicitly to the financial engine or via the UI's FS1 Synthetic Preset button, not inserted as transactions.

See [synthetic dataset design and limitations](docs/synthetic-data.md), [data artifacts](data/README.md), and [verification](docs/synthetic-data-verification.md).

## Cash-flow forecasting (`CM1`)

The Python service (`forecasting/app/main.py`) exposes `POST /forecast` using `drift_calendar`, `ridge_lag_calendar`, and `naive` models with split-conformal 80% & 95% intervals (`forecasting/experiments/cash_flow_models.py` and `forecasting/experiments/cash_flow_baseline.py`):

```sh
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_baseline
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_models
```

See [forecasting baseline](docs/forecasting-baseline.md) and [forecasting models (`CM1`)](docs/forecasting-models.md).

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

Terminal 2 — Python (from repository root):

```sh
set -a
source .env
set +a
forecasting/.venv/bin/python -m uvicorn forecasting.app.main:app --host 127.0.0.1 --port "${FORECAST_PORT:-8000}"
```

Terminal 3 — React:

```sh
npm --prefix frontend run dev
```

Open `http://127.0.0.1:5173`. Vite proxies `/api` to Java; no browser-side database credentials are used. Ctrl+C stops each application. If ports change, update `.env` and `VITE_BACKEND_TARGET` consistently. `vite preview` serves the static build without the development API proxy.

## Additional checks

With root `.env` exported:

```sh
python3 scripts/verify_local_database.py
npm --prefix frontend test
npm --prefix frontend run build
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m pip check
python3 scripts/smoke_check.py
```

The auth script verifies password enforcement and development/test isolation using local PostgreSQL on port 5432. The smoke check starts all three applications on temporary loopback ports, checks Java liveness, database readiness, Python liveness, Vite → Java proxy, frontend HTML, SME listing, forecast 404 handling, and live Java → Python forecast bridge, then stops only its processes. Logs are under ignored `.cache/`.

## Repository

- `frontend/`: React 19 + Vite FinSight & BankPilot web dashboard and Vitest unit/component tests.
- `backend/`: Spring MVC, JDBC persistence, pure financial & scenario calculations, forecast bridge, Flyway integration, and unit/PostgreSQL tests.
- `forecasting/`: FastAPI `/forecast` & `/health` service, `CM1` forecasting models, conformal interval calibration, and offline evaluation experiments/tests.
- `database/migrations/`: authoritative versioned/repeatable SQL, packaged into the Java JAR.
- `data/`: clearly labelled synthetic CSVs, opening inputs, checksum manifest, and provenance policy.
- `scripts/`: local provisioning, authentication verification, synthetic data CLI, and startup smoke checks.
- `docs/`: [schema](docs/database-schema.md), [database operations](docs/database.md), [architecture](docs/architecture.md), [API contract](docs/api-contract.md), [financial engine](docs/financial-engine.md), and [forecasting models](docs/forecasting-models.md).

Both collaborators rotate implementation and review across components. Work happens on `main` synced with `origin/main` (`saadi-chehd/FinSight`). Do not commit `.env`, databases, dependencies, or logs.
