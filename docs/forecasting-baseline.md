# Milestone 4 — Cash-flow forecasting baseline

Verified on **25 October 2026**. This is an offline, fixed-rule experiment on the existing synthetic dataset, not evidence of real-world SME forecast accuracy. No financial engine, data, schema, credential, dependency, HTTP API, dashboard, or advanced ML model was changed or added.

## Source inspection and unchanged financial meaning

Reviewed README, the financial-engine and synthetic-data design/verification documents, existing Flyway SQL, transaction/invoice models, Java calculation/read code, the synthetic exporter/manual loader, and tests.

Source is the existing **FS1 / seed 20261018** export under `data/synthetic/FS1-20261018/`. It contains three fictional USD SMEs, each with 731 consecutive calendar observations from 2024-01-01 through 2025-12-31. The experiment reads the existing CSVs without generating, loading, or modifying data. Their manifest checksums are verified before evaluation; all five input CSV hashes and the manifest hash are recorded in `results.json`.

The target comes from `daily_cash_flow.csv`, exported by `SyntheticExporter` using the existing Java `FinancialCalculator`:

```text
closing[d] = opening[d] + inflow[d] - outflow[d]
opening[d+1] = closing[d]
```

Opening on the first date is the dated input in `opening_balances.csv`. Inflow/outflow are nonnegative magnitudes, empty days carry the balance, and negative closing values remain valid. An invoice is not cash until a linked payment transaction exists; linked payments are already part of transactions and must not be added again. No raw invoice or transaction field enters a baseline predictor.

The separate existing synthetic loader regenerates the named seed and atomically inserts/verifies SME, invoice, and transaction rows only when explicitly invoked. It never runs on ordinary startup. For this milestone, only its **read-only `verify`** command was run; no `generate` or `load` command was used. It confirmed the development dataset still matches the existing engine and canonical seed.

The Python loader enforces the inspected FS1 column contract, synthetic labels, currency, checksums, dates/counts, exact monetary minor units, opening inputs, and row-by-row roll-forward. It rejects gaps, duplicates, out-of-order dates, changed headers, or inconsistent cash rows rather than redefining/filling the series. Dataset validation can inspect complete files; the predictors receive only an origin-bounded closing-balance prefix.

## Target and forecast origin

For each SME separately, forecast **closing cash on date `t+h`**, where `h` is 7 or 30 **calendar days**. This is a point forecast of the endpoint balance, not average cash over the interval, cumulative inflow, or a seven/thirty-day path metric.

Origin `t` is immediately after that day's closing observation is available. Inputs contain only that SME's closing balances dated on/before `t`. The synthetic availability assumption is that each dated closing observation is released at its day-end; the dataset does not model ingestion delays. Database `created_at` is a later load timestamp, not evidence of historical availability, and is not used.

Evaluation uses rolling origins: as 2025 passes, already observed 2025 closings may inform later origins. This is not a single fixed-origin forecast of the whole year. No target at `t+h` is observed by the predictor at `t`.

## Baseline definitions, fixed before evaluation

### Naïve persistence

```text
y_hat(t+h | t) = y(t)
```

Predict the most recent known closing balance for either horizon. It uses one observation and fits no parameter.

### Weekly repetition / seasonal baseline

Repeat the last complete observed seven-day closing-balance pattern. The exact multi-step rule is:

```text
k = ceil(h / 7)
y_hat(t+h | t) = y(t+h-7*k)
```

All reference dates fall in `[t-6, t]`. This is an explicit recursive extension of one-week seasonality, not access to actual future observations.

- **7 days:** reference is `t`; weekly and naïve predictions are mathematically identical. A tie is expected and is not evidence of discovered seasonality.
- **30 days:** reference is `t-5`. Using the actual closing one week before the target (`t+23`) would leak future data and is prohibited. The last observed weekly pattern is repeated instead.

The rule is computable for both requested horizons with the available history. It is a deliberately weak benchmark for a cumulative cash level: it has no trend, monthly payroll/rent effects, or business-event awareness, and weekly repetition need not be appropriate for balance levels. Its poor 30-day result is reported as measured; it is not silently replaced with drift, a different target, future transactions, or another model.

The weekly baseline requires all seven consecutive observations ending at the origin even when a specific endpoint uses fewer distinct values. If any SME lacks them, that origin is explicitly excluded from **both baselines, both horizons, and every SME** to preserve one common comparison cohort. There is no naïve fallback or imputation. If no common eligible origins remain, evaluation fails. The canonical dataset has zero such exclusions. Gaps inside a nominal daily series are input-contract errors, not filled values.

## Chronological split and exact counts

All boundaries are inclusive except that test begins the day after train ends. The fixed calendar split was chosen before computing results. There is no shuffle, random split, validation tuning, or test-driven model selection.

| Partition | Dates | Rows per SME | Rows across 3 SMEs |
| --- | --- | ---: | ---: |
| Initial history / train | 2024-01-01–2024-12-31 | 366 | 1098 |
| Observed holdout / test | 2025-01-01–2025-12-31 | 365 | 1095 |
| Shared forecast origins | 2024-12-31–2025-12-01 | 336 | 1008 SME-origin pairs |

The first origin is the final train observation, forecasting into the holdout. The final origin is `test_end - 30 days`, so both horizons are available at every origin. Both models and all SMEs use exactly these 336 origins, with no exclusions. No parameter fitting or scaling uses either partition; training serves as the initial observed prefix.

| Horizon | Actual target dates | Evaluated pairs per SME/model | Pooled pairs per model |
| --- | --- | ---: | ---: |
| 7 days | 2025-01-07–2025-12-08 | 336 | 1008 |
| 30 days | 2025-01-30–2025-12-31 | 336 | 1008 |

All actual targets are in the holdout. The shorter horizon deliberately stops at 8 December because the origins must also support a complete 30-day target. Therefore this is not evaluation of every one of the 365 test labels. There are **4032 prediction rows**: 336 origins × 3 SMEs × 2 horizons × 2 models.

## Leakage controls

- A predictor's only data argument is immutable `History`, ending exactly at its origin. It never receives the full series, transaction rows, invoice states, future closings, or generator parameters.
- Requests for a history date after the origin raise `FutureDataError`. Missing dates raise `MissingHistoryError`; no future backfill or observed-future recursion is allowed.
- Both model predictions are computed before the evaluator looks up the target actual. The audit CSV includes each input reference date, origin, horizon, target date, prediction, and actual.
- End-of-dataset invoice payment status, future payment schedules, final balances, and whole-period statistics are not features. The manifest seed/hashes are provenance only, not predictor inputs.
- There is no scaling, feature selection, statistical fit, parameter estimation, or hyperparameter search. Decimal normalization is fixed schema precision, not a fitted scaler.
- Tests perturb future values and verify that earlier predictions remain unchanged. Changing values strictly after the last origin changes scores but leaves every prediction unchanged. Later observations can affect forecasts only after they become known at later origins.

These controls validate this synthetic date-availability protocol; they do not establish historical point-in-time correctness of a real bank ingestion system.

## Metric definitions and aggregation

For each horizon/model/SME, with `e_i = prediction_i - actual_i`:

```text
MAE  = sum(abs(e_i)) / N
RMSE = sqrt(sum(e_i^2) / N)
```

Both metrics are in USD. Overall rows pool all 1008 errors for that horizon/model, recomputing RMSE from pooled squared errors. They do **not** average the three SME RMSE values. Equal counts give each SME equal sample weight; no turnover or balance-size weighting/normalization is used, and mixed-currency pooling is rejected.

The experiment uses Python `Decimal` with precision 80 for error/metric arithmetic, never floating-point money. Predictions and actuals retain exact cents. Metric artifacts serialize six decimal places using ROUND_HALF_UP; this is presentation of means/square roots, not alteration of financial observations.

`metrics.csv`/JSON include differences against naïve (`model - naive`, positive means worse) and percentage improvement (`100*(naive-model)/naive`, negative means worse). If naïve error is zero, percentage improvement is null/blank rather than a fabricated percentage.

## Actual synthetic holdout results

A = fictional retail, B = fictional agency, C = fictional workshop. The following are the measured six-decimal results from the published `metrics.csv`, all in USD:

| Horizon | SME | Naïve MAE | Naïve RMSE | Weekly MAE | Weekly RMSE |
| --- | --- | ---: | ---: | ---: | ---: |
| 7 days | FS1-20261018-A | 1394.519821 | 1765.955125 | 1394.519821 | 1765.955125 |
| 7 days | FS1-20261018-B | 2664.837589 | 3338.981145 | 2664.837589 | 3338.981145 |
| 7 days | FS1-20261018-C | 2396.454732 | 2990.488905 | 2396.454732 | 2990.488905 |
| 7 days | ALL | 2151.937381 | 2781.511369 | 2151.937381 | 2781.511369 |
| 30 days | FS1-20261018-A | 3921.773601 | 4507.589325 | 4581.444167 | 5163.023213 |
| 30 days | FS1-20261018-B | 6128.518482 | 7454.321146 | 7140.270417 | 8499.538388 |
| 30 days | FS1-20261018-C | 6833.750238 | 7555.169726 | 7993.341815 | 8784.187413 |
| 30 days | ALL | 5628.014107 | 6657.473366 | 6571.685466 | 7660.742131 |

At 7 days both models tie by construction. At 30 days, weekly repetition has higher MAE and RMSE for every SME. Pooled MAE rises **16.767395%** (943.671359 USD) and RMSE rises **15.069813%** (1003.268765 USD) versus naïve. Under this defined experiment, naïve is the stronger of these two 30-day benchmarks. This is not a real-world accuracy claim or statistical significance result.

## Tests, runtime, and preservation

- **28 Python tests passed**, 0 failures/errors/skips. Coverage includes date split/origins, 7/30-day alignment, shared cohorts, historical-only inputs, perturbation leakage tests, missing history, negative balances, correct MAE/RMSE/pooling, known linear-fixture results, input contracts, and reproducibility/overwrite protection.
- **64 existing backend tests passed**, 0 failures/errors/skips; BUILD SUCCESS. No existing Java test was modified.
- Separate CLI runs produced byte-identical `predictions.csv`, `metrics.csv`, and `results.json`.
- An independent standard-library Fraction-based audit checked all 4032 prediction values/reference dates/targets and all 16 MAE/RMSE rows against the raw daily closing CSV.
- Read-only original Java synthetic verification passed with unchanged SMEs/invoices/transactions and reconciled engine balances/summaries. No development seeding or data correction was performed.
- Saved hashes confirm existing backend sources/tests, Flyway SQL, synthetic artifacts, FastAPI health code, dependency files, and `.env` remain unchanged.

Environment issue encountered: the first backend command could not start tests because the configured Intel Temurin Java 17 executable returned `Bad CPU type in executable`; PostgreSQL was also stopped. The already-installed ARM64 JetBrains runtime **21.0.8** under PhpStorm was used only via a per-command JAVA_HOME/PATH override, and the existing PostgreSQL cluster was manually started. No software was installed and no permanent Java/project configuration changed. The Java compilation target remains 17 (class-file major version 61). This run validates the suite on JBR 21; running this machine's unusable Java 17 executable remains unverified. There were no experiment/Python test failures, and the successful backend retry passed all 64 tests.

## Commands used

From `/Users/saadichehdlina/Desktop/finsight`, Python 3.14.0 in the existing virtual environment; no pip installation is needed for the standard-library experiment:

```sh
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_baseline
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_baseline --output-dir .cache/forecasting-reproduction
```

The default arguments explicitly mean:

```sh
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_baseline \
  --data-dir data/synthetic/FS1-20261018 \
  --train-end 2024-12-31 --test-end 2025-12-31 \
  --output-dir forecasting/results/fs1-closing-balance-baseline
```

The experiment needs neither database credentials nor a running service. It refuses output inside the immutable synthetic-data directory and refuses to overwrite different existing results. Identical reruns are accepted. Choose a new output directory if code, runtime, split, or input provenance changes. Artifacts contain input/source hashes and Python version but no timestamps, credentials, or absolute machine paths.

Actual backend retry on this host, after confirming PostgreSQL was stopped:

```sh
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
set -a
source .env
set +a
JAVA_HOME='/Applications/PhpStorm.app/Contents/jbr/Contents/Home' MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify
PATH='/Applications/PhpStorm.app/Contents/jbr/Contents/Home/bin':"$PATH" ./scripts/synthetic_data.sh verify --seed 20261018
```

Do not start a second PostgreSQL instance if already running. The JBR override is this host's existing-runtime workaround, not a new required dependency or a request to change the Java 17 target. PostgreSQL is left manually running; no login service was enabled.

Logs: `.cache/forecasting-python-tests.log`, `.cache/forecasting-experiment.log`, `.cache/forecasting-reproduction.log`, `.cache/forecasting-backend-jbr21-tests.log`, and `.cache/forecasting-source-db-verify.log`. The initial environment failure is retained in `.cache/forecasting-backend-tests.log`.

## Changed files

Added:

- `forecasting/__init__.py`, `forecasting/experiments/__init__.py`, `forecasting/tests/__init__.py`: package markers only.
- `forecasting/experiments/baseline_data.py`: validated read-only FS1 data boundary and immutable origin-bounded histories.
- `forecasting/experiments/cash_flow_baseline.py`: fixed baselines, rolling evaluation, metrics, reproducible CLI/artifacts.
- `forecasting/tests/test_baseline_data.py`, `forecasting/tests/test_cash_flow_baseline.py`: 28 tests.
- `forecasting/results/fs1-closing-balance-baseline/predictions.csv`: 4032 auditable forecasts/actuals.
- `forecasting/results/fs1-closing-balance-baseline/metrics.csv`: 16 per-SME/overall metric rows and naïve comparisons.
- `forecasting/results/fs1-closing-balance-baseline/results.json`: protocol, counts, metrics, input/source hashes, runtime.
- `docs/forecasting-baseline.md`: this methodology/result/verification record.

Updated `README.md` to point to the offline experiment. Requirements, backend code/tests, financial definitions, synthetic data, and credentials were preserved. No commit or remote was created.

## Limits and interpretation

- Three fictional SMEs and one generator seed do not represent real SME behavior. Synthetic arithmetic/metric correctness does not validate real-world forecast performance.
- The series are invented, strongly trending, and contain designed weekly/monthly patterns. Weekly repetition of a balance level is intentionally crude; neither baseline models financing, future receipts/payments, or an SME's operations.
- Horizons overlap across rolling origins, so errors are dependent. No confidence interval, significance test, independent-sample claim, or guarantee is made.
- This is an observed-prefix rolling benchmark, not fixed-origin year-ahead forecasting, an unseen-SME test, or production point-in-time backtesting.
- No model tuning, advanced ML, calibrated uncertainty, forecast service endpoint, financial HTTP API, dashboard, or schema change was implemented.
- No lending, default, insolvency, or financial advice is inferred from prediction errors. Stop here for review before extending scope.
