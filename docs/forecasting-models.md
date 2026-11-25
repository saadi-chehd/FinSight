# Cash-Flow Forecasting Models (`CM1`) & Live `/forecast` Endpoint

This document records the first implemented cash-flow forecasting models (`CM1`), their offline rolling-origin evaluation against the Milestone 4 naïve persistence benchmark on the synthetic **FS1 / seed 20261018** dataset, and the live `POST /forecast` HTTP endpoint.

> **Synthetic-only notice:** All evaluation figures below are measured on the three fictional USD SMEs in `data/synthetic/FS1-20261018/`. They verify mathematical, anti-leakage, and software correctness; they are not evidence of real-world SME forecast accuracy or creditworthiness.

## Alignment with Baseline Protocol (`CB1`)

`forecasting/experiments/cash_flow_models.py` follows the exact chronological split, origin set, target definition, and pooling rules established in [forecasting-baseline.md](forecasting-baseline.md):

- **Target:** Daily closing cash balance `y(t+h)` at `h = 7` and `h = 30` calendar days.
- **Train / initial prefix:** `2024-01-01` through `2024-12-31` (366 days per SME).
- **Holdout / test:** `2025-01-01` through `2025-12-31` (365 days per SME).
- **Shared rolling origins:** `2024-12-31` through `2025-12-01` (336 origins per SME, 0 exclusions, 1,008 SME-origin pairs per horizon/model, **6,048 prediction rows** total across 3 models × 2 horizons × 3 SMEs × 336 origins).
- **Anti-leakage controls:** Every model receives only an immutable `History` prefix containing closing-balance observations dated `<= origin`. No future actuals, raw invoices, raw transactions, or end-of-dataset invoice statuses are accessible. Hyperparameters were selected on 2024-only training prefixes prior to 2025 evaluation.
- **Naïve benchmark parity:** The `naive` rows in `CM1` match the published `CB1` `naive` `MAE` and `RMSE` to all 6 decimal places for every SME and pooled `ALL` row.

## Models Evaluated

### Benchmark — Naïve Persistence (`naive`)

```text
y_hat(t+h | t) = y(t)
```

Predicts the most recent observed closing balance at origin `t` for any horizon `h`.

### Model 1 — Drift + Calendar Net-Flow (`drift_calendar`)

Models daily net cash movement `d(k) = y(k) - y(k-1)` fitted strictly on observations `<= origin` (minimum 60 consecutive days):

1. Estimate sample daily drift `mu = mean(d)`.
2. Run 6 iterations of shrunk alternating backfitting to separate:
   - Day-of-month effects `beta[1..31]` with shrinkage `k_dom = 2.0` (capturing monthly rent, payroll, and recurring calendar cycles), centered by observed-day counts so total drift `mu` is preserved.
   - Day-of-week effects `alpha[0..6]` with shrinkage `k_dow = 5.0` (capturing weekly direct-sales and payment cadence), centered by observed-weekday counts.
3. Roll forward cumulatively from `y(origin)`:

```text
y_hat(t+h | t) = y(t) + sum_{s=1..h} (mu + alpha[dow(t+s)] + beta[dom(t+s)])
```

### Model 2 — Regularized Regression on Lag + Calendar Features (`ridge_lag_calendar`)

Predicts daily net cash movement `d(k)` using closed-form L2-regularized Ridge regression (`alpha = 100.0`) fitted strictly on observations `<= origin` (minimum 60 consecutive days, `MIN_LAG = 28`), then rolled forward recursively for `s = 1..h`:

- **Lag net-movement features (6):** lags `1, 2, 3, 7, 14, 28`.
- **Rolling-mean net-movement features (3):** trailing `7, 14, 28`-day means prior to day `k`.
- **Calendar features (39):** 7 day-of-week one-hot indicators, 31 day-of-month one-hot indicators, and 1 quarter-end-month indicator (`month in {3, 6, 9, 12}`).
- **Prefix-only standardization:** Feature means, standard deviations, and target mean `y_mean` (unpenalized intercept) are computed strictly on training rows `<= origin`.
- **Recursive roll-forward:** Each predicted daily net movement `d_hat(t+s)` is appended to the lag/rolling buffer to generate `d_hat(t+s+1)`, and summed cumulatively onto `y(t)`.

## Empirical 80% & 95% Prediction Intervals

Both models and the naïve benchmark produce empirical **80%** and **95%** prediction intervals using split-conformal calibration over strictly past out-of-sample rolling residuals:

- For origin `t` and horizon `h`, only past origins `s` whose target `s + h <= t` (observed at or before `t`) are eligible, capped at the most recent `MAX_RESIDUAL_WINDOW = 365` residuals and requiring at least `MIN_RESIDUAL_COUNT = 20` residuals.
- Absolute residuals `r_s = |y(s+h) - y_hat(s+h | s)|` are sorted ascending and indexed at the exact finite-sample conformal rank `ceil(level * (m + 1))` (capped at `m`) for `level in {0.80, 0.95}`.
- Because residuals are evaluated strictly from `History` prefixes ending at `s <= t - h`, zero future residual leakage occurs.

## Metric Definitions

With error `e_i = predicted_i - actual_i`:

- **MAE:** `sum(|e_i|) / N` (USD)
- **RMSE:** `sqrt(sum(e_i^2) / N)` (USD; pooled `ALL` rows recompute RMSE from all 1,008 squared errors, never averaging SME RMSEs)
- **ME (Mean Signed Error):** `sum(e_i) / N` (USD; negative indicates underprediction, positive indicates overprediction)
- **MASE:** `mean(|e_i| / scale_sme)`, where `scale_sme` is each SME's in-sample 1-step naïve MAE on the 2024 training partition (`FS1-20261018-A`: `356.953370`, `FS1-20261018-B`: `588.055096`, `FS1-20261018-C`: `454.634603` USD)
- **% Improvement vs. Naïve:** `100 * (naive_metric - model_metric) / naive_metric`
- **Coverage 80% / 95%:** Fraction of observations where `lower <= actual <= upper` (inclusive)

## Holdout Evaluation Results (FS1 / 336 Shared Origins)

All values below are transcribed directly from `forecasting/results/fs1-closing-balance-models-v1/metrics.csv` (Decimal precision 80, rounded to 6 decimal places via `ROUND_HALF_UP`).

### 7-Day Horizon (`2025-01-07` to `2025-12-08`)

| SME | Model | N | MAE (USD) | RMSE (USD) | ME (USD) | MASE | MAE Impr vs. Naïve | RMSE Impr vs. Naïve | Cov 80% | Cov 95% |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FS1-20261018-A | `naive` | 336 | 1394.519821 | 1765.955125 | -916.234286 | 3.906728 | 0.000000% | 0.000000% | 0.827381 | 0.934524 |
| FS1-20261018-A | `drift_calendar` | 336 | 925.353274 | 1208.527746 | -76.929762 | 2.592365 | 33.643591% | 31.565206% | 0.839286 | 0.958333 |
| FS1-20261018-A | `ridge_lag_calendar` | 336 | 953.133929 | 1247.291085 | -83.933929 | 2.670192 | 31.651461% | 29.370171% | 0.836310 | 0.952381 |
| FS1-20261018-B | `naive` | 336 | 2664.837589 | 3338.981145 | -1400.462589 | 4.531612 | 0.000000% | 0.000000% | 0.806548 | 0.952381 |
| FS1-20261018-B | `drift_calendar` | 336 | 1797.924375 | 2339.411078 | -269.211399 | 3.057408 | 32.531559% | 29.936379% | 0.800595 | 0.952381 |
| FS1-20261018-B | `ridge_lag_calendar` | 336 | 1887.772768 | 2422.936306 | -163.162827 | 3.210197 | 29.159932% | 27.434861% | 0.791667 | 0.949405 |
| FS1-20261018-C | `naive` | 336 | 2396.454732 | 2990.488905 | -1624.080387 | 5.271167 | 0.000000% | 0.000000% | 0.767857 | 0.931548 |
| FS1-20261018-C | `drift_calendar` | 336 | 1508.231905 | 1975.438857 | -410.386607 | 3.317460 | 37.064035% | 33.942612% | 0.800595 | 0.943452 |
| FS1-20261018-C | `ridge_lag_calendar` | 336 | 1536.278482 | 1999.792137 | -295.579077 | 3.379150 | 35.893699% | 33.128254% | 0.797619 | 0.946429 |
| **ALL (Pooled)** | **`naive`** | **1008** | **2151.937381** | **2781.511369** | **-1313.592421** | **4.569836** | **0.000000%** | **0.000000%** | **0.800595** | **0.939484** |
| **ALL (Pooled)** | **`drift_calendar`** | **1008** | **1410.503185** | **1900.503633** | **-252.175923** | **2.989077** | **34.454264%** | **31.673706%** | **0.813492** | **0.951389** |
| **ALL (Pooled)** | **`ridge_lag_calendar`** | **1008** | **1459.061726** | **1951.539903** | **-180.891944** | **3.086513** | **32.197761%** | **29.838867%** | **0.808532** | **0.949405** |

### 30-Day Horizon (`2025-01-30` to `2025-12-31`)

| SME | Model | N | MAE (USD) | RMSE (USD) | ME (USD) | MASE | MAE Impr vs. Naïve | RMSE Impr vs. Naïve | Cov 80% | Cov 95% |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FS1-20261018-A | `naive` | 336 | 3921.773601 | 4507.589325 | -3883.753065 | 10.986795 | 0.000000% | 0.000000% | 0.806548 | 0.892857 |
| FS1-20261018-A | `drift_calendar` | 336 | 1876.489018 | 2327.047404 | -286.319018 | 5.256958 | 52.152031% | 48.374902% | 0.791667 | 0.925595 |
| FS1-20261018-A | `ridge_lag_calendar` | 336 | 1929.821339 | 2372.835676 | -274.813839 | 5.406368 | 50.792128% | 47.359098% | 0.770833 | 0.937500 |
| FS1-20261018-B | `naive` | 336 | 6128.518482 | 7454.321146 | -5974.540446 | 10.421674 | 0.000000% | 0.000000% | 0.776786 | 0.910714 |
| FS1-20261018-B | `drift_calendar` | 336 | 3488.039048 | 4727.123859 | -1117.978393 | 5.931483 | 43.085118% | 36.585455% | 0.815476 | 0.934524 |
| FS1-20261018-B | `ridge_lag_calendar` | 336 | 3693.743988 | 4724.726612 | -772.894762 | 6.281289 | 39.728598% | 36.617614% | 0.752976 | 0.937500 |
| FS1-20261018-C | `naive` | 336 | 6833.750238 | 7555.169726 | -6799.617619 | 15.031302 | 0.000000% | 0.000000% | 0.773810 | 0.934524 |
| FS1-20261018-C | `drift_calendar` | 336 | 3088.401250 | 3743.484474 | -1567.276071 | 6.793150 | 54.806641% | 50.451352% | 0.869048 | 0.994048 |
| FS1-20261018-C | `ridge_lag_calendar` | 336 | 3054.767083 | 3731.620019 | -1137.440060 | 6.719170 | 55.298819% | 50.608389% | 0.880952 | 0.994048 |
| **ALL (Pooled)** | **`naive`** | **1008** | **5628.014107** | **6657.473366** | **-5552.637044** | **12.146591** | **0.000000%** | **0.000000%** | **0.785714** | **0.912698** |
| **ALL (Pooled)** | **`drift_calendar`** | **1008** | **2817.643105** | **3731.600443** | **-990.524494** | **5.993864** | **49.935394%** | **43.948699%** | **0.825397** | **0.951389** |
| **ALL (Pooled)** | **`ridge_lag_calendar`** | **1008** | **2892.777470** | **3736.236014** | **-728.382887** | **6.135609** | **48.600387%** | **43.879069%** | **0.801587** | **0.956349** |

### Key Observations

- **Point accuracy:** Both `drift_calendar` and `ridge_lag_calendar` substantially outperform `naive` across every SME and both horizons. On pooled metrics, `drift_calendar` reduces MAE by **34.45%** (7d) and **49.94%** (30d), while `ridge_lag_calendar` reduces MAE by **32.20%** (7d) and **48.60%** (30d).
- **Directional bias (`ME`):** Because FS1 cash balances trend upward and 2025 scales synthetic amounts +5 percentage points over 2024, `naive` exhibits severe downward bias (`-1313.59` USD at 7d, `-5552.64` USD at 30d). Both models eliminate most of this bias; `ridge_lag_calendar` achieves the smallest magnitude bias (`-180.89` USD at 7d, `-728.38` USD at 30d) by adapting faster via recent lag/rolling-mean features.
- **Interval calibration:** Pooled empirical coverage closely tracks nominal targets across both horizons (`80.85%–82.54%` for 80% intervals; `94.94%–95.63%` for 95% intervals), whereas `naive` 30-day intervals under-cover (`91.27%` for 95%) due to unmodeled upward drift.

## Live HTTP Prediction Endpoint (`POST /forecast`)

Implemented in [main.py](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/app/main.py), keeping `GET /health` unchanged (`{"status": "UP", "service": "forecasting"}`).

- **Request JSON:**
  - `currency`: 3-letter uppercase ISO currency code (e.g., `"USD"`).
  - `history`: nonempty chronologically ordered list of `{date: "YYYY-MM-DD", closing_balance: "1234.56"}` with strictly consecutive calendar days and exact 2-decimal minor units.
  - `horizons`: optional list of unique positive integers in `[1, 365]` (default `[7, 30]`).
  - `model`: `"drift_calendar"` (default), `"ridge_lag_calendar"`, or `"naive"`.
- **Minimum required history:** `min_fit_days[model] + max(horizons) + 19` consecutive daily observations (e.g., `109` days for `drift_calendar` or `ridge_lag_calendar` at `max_horizon=30`; `50` days for `naive` at `max_horizon=30`).
- **Validation (HTTP 422):** Rejects missing calendar days, duplicate or reversed dates, fractional minor units (`"100.123"`), non-finite/boolean/null balances, invalid currencies, duplicate/invalid horizons, unknown model names, or insufficient history.
- **Response JSON:** Exact 2-decimal point predictions, `interval_80` (`lower`, `upper`), `interval_95` (`lower`, `upper`), `residual_count`, `model` metadata, and an explicit disclaimer that outputs are conditional mathematical estimates (not solvency proofs or credit/lending decisions).

## Commands & Reproducibility Artifacts

Artifacts are written to `forecasting/results/fs1-closing-balance-models-v1/`:
- [metrics.csv](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v1/metrics.csv)
- [predictions.csv](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v1/predictions.csv)
- [results.json](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v1/results.json) (includes input CSV/manifest SHA-256 hashes, source file SHA-256 hashes, Python/NumPy versions, and hyperparameters)

```sh
forecasting/.venv/bin/python -m pip check
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_models
```

## Limitations

- Evaluated on 3 synthetic profiles from a single generator seed (`FS1` / `20261018`); synthetic accuracy does not generalize to real SME bank feeds.
- Models rely exclusively on univariate daily closing cash balances and calendar attributes (`<= origin`); they do not incorporate open invoices, scheduled payables/receivables, or macroeconomic covariates.
- Rolling-origin horizons overlap across consecutive days, so evaluation residuals are serially correlated. Conformal intervals assume local exchangeability of recent rolling residuals and can widen or under-cover during abrupt structural shifts.
- Predictions are conditional mathematical estimates only and must not be used as solvency proofs, liquidity guarantees, or automated credit/lending decisions.
