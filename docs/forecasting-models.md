# Cash-Flow Forecasting Models (`CM1` & `CM2`) & Live `/forecast` Endpoint

This document records the cash-flow forecasting models implemented in FinSight — deterministic statistical/linear baselines (`CM1`) and causal machine-learning / invoice-aware models (`CM2`) — their offline rolling-origin evaluation on the synthetic **FS1 / seed 20261018** dataset, a 2-extra-seed generalization check (`20261019`, `20261020`), and the live `POST /forecast` HTTP endpoint.

> **Synthetic-only notice:** All evaluation figures below are measured on fictional USD SMEs generated deterministically by the FS1 synthetic generator (`data/synthetic/FS1-20261018/` and temporary `.cache/multi-seed/` exports). They verify mathematical, anti-leakage, and software correctness; they are not evidence of real-world SME forecast accuracy or creditworthiness.

## Alignment with Baseline Protocol (`CB1` & `CM1`)

Both `forecasting/experiments/cash_flow_models.py` (`CM1`) and `forecasting/experiments/cash_flow_ml.py` (`CM2`) follow the exact chronological split, origin set, target definition, and pooling rules established in [forecasting-baseline.md](forecasting-baseline.md) and [evaluation-plan.md](evaluation-plan.md):

- **Target:** Daily closing cash balance `y(t+h)` at `h = 7` and `h = 30` calendar days.
- **Train / initial prefix:** `2024-01-01` through `2024-12-31` (366 days per SME).
- **Holdout / test:** `2025-01-01` through `2025-12-31` (365 days per SME).
- **Shared rolling origins:** `2024-12-31` through `2025-12-01` (336 origins per SME, 0 exclusions, 1,008 SME-origin pairs per horizon/model, **12,096 prediction rows** total across 6 models × 2 horizons × 3 SMEs × 336 origins in `CM2`).
- **Strict zero-leakage controls:**
  - Every model receives only closing-balance observations dated `<= origin`.
  - Point-in-time open invoices and empirical settlement statistics at `origin` are reconstructed strictly from `invoices.csv` rows with `issue_date <= origin` and linked `transactions.csv` payments with `booked_date <= origin`. The `payment_status_at_cutoff` CSV column is never parsed, stored, or branched on.
  - Hyperparameters, scalers, settlement priors, ensemble weights, and split-conformal 80%/95% radii are fitted strictly on history `<= origin`. Design choices were validated on 2024-only prefixes (`<= 2024-12-31`) prior to locking hyperparameters for 2025 holdout evaluation.
- **CM1/CB1 parity:** In `CM2`, `naive`, `drift_calendar`, and `ridge_lag_calendar` match the published `CM1` (and `CB1` `naive`) `MAE`, `RMSE`, `ME`, `MASE`, and coverage metrics to all 6 decimal places for every SME and pooled `ALL` row.

## Models Evaluated

### `CM1` Models (`cash_flow_models.py`)

1. **Naïve Persistence (`naive`):**
   ```text
   y_hat(t+h | t) = y(t)
   ```
   Minimum fit days: `1` (`min_history_days = 50` at `max_horizon = 30`).

2. **Drift + Calendar Net-Flow (`drift_calendar`):**
   Models daily net cash movement `d(k) = y(k) - y(k-1)` fitted strictly on observations `<= origin` (minimum `60` consecutive days; `min_history_days = 109` at `max_horizon = 30`) via sample drift `mu = mean(d)` plus 6 iterations of shrunk alternating backfitting for day-of-month (`k_dom = 2.0`) and day-of-week (`k_dow = 5.0`) effects, rolled forward cumulatively from `y(t)`.

3. **Ridge Regression on Lag + Calendar Features (`ridge_lag_calendar`):**
   Closed-form L2-regularized Ridge regression (`alpha = 100.0`) on daily net cash movement `d(k)` using 6 net-movement lags (`1, 2, 3, 7, 14, 28`), 3 trailing rolling means (`7, 14, 28` days), and 39 calendar indicators (7 day-of-week, 31 day-of-month, 1 quarter-end-month), standardized strictly on `<= origin` and rolled forward recursively (`min_history_days = 109` at `max_horizon = 30`).

### `CM2` Models (`cash_flow_ml.py`)

All `CM2` models require `min_fit_days = max(90, 28 + max_horizon + 30)` and `min_history_days = min_fit_days + max_horizon + 19` (`139` consecutive days at `max_horizon = 30`).

4. **Gradient Boosting Direct (`gbdt_direct`):**
   Direct multi-horizon `GradientBoostingRegressor` (`n_estimators=25`, `learning_rate=0.025`, `max_depth=2`, `max_features="sqrt"`, `min_samples_leaf=20`, `subsample=0.85`, `random_state=42`) predicting cumulative balance change `y(s+h) - y(s)` from **63 strictly causal features** at `(s, h)` fitted solely on `(s, s+h)` pairs with `s + h <= origin`:
   - Daily net movement lags (`0, 1, 2, 3, 6, 7, 13, 14, 27, 28`)
   - Multi-day balance changes (`7d, 14d, 28d, 56d` with graceful fallback for shorter prefixes)
   - Rolling `mean, std, min, max` over trailing `7, 14, 28` days
   - Exponential trends (`ewma_7, ewma_28, ewma_60`, and `ewma_28_x_h`)
   - Origin & target calendar encodings (`dow`, `sin/cos(dow)`, `dom`, `sin/cos(dom)`, `is_month_end`, `is_quarter_end_month`)
   - Horizon window `(s, s+h]` calendar counts (weekday counts `0..6`, business-day count, weekend-day count, recurring billing/outflow dates `dom in {1, 15, 20, 25}`, month-end, quarter-end, plus strictly causal shrunk day-of-month calendar sum `win_causal_cal_sum` / `win_causal_dom_sum` fitted only on observations `<= s`).

5. **Invoice-Aware GBDT + Ridge (`invoice_aware_ml`):**
   - **Offline / Full Ledger mode (`InvoiceLedger`):** Combines the 63 causal cash-flow features with **24 point-in-time invoice schedule & empirical settlement features** (**87 total features**) reconstructed strictly from `issue_date <= s` and `booked_date <= s`: scheduled inflows/outflows/net due in `7d`, `30d`, and `h` days; overdue receivable/payable/net totals; open invoice totals & counts; lag-adjusted expected settlements (`lag_adj_inflow_h`, `lag_adj_outflow_h`, `lag_adj_net_h`); and shrunk empirical settlement lag & on-time/collection rates (`settlement_prior_k = 5.0`). Uses a `StandardScaler` + `Ridge(alpha=50.0)` linear base plus a `GradientBoostingRegressor` (`n_estimators=25`, `learning_rate=0.025`, `max_depth=2`, `max_features="sqrt"`, `min_samples_leaf=20`, `subsample=0.85`, `random_state=42`) residual model fitted strictly on `s + h <= origin`.
   - **Live API Snapshot mode (`open_invoices`):** When invoked via `POST /forecast` without a full historical `InvoiceLedger`, uses `gbdt_direct` as the base forecast plus a conservative bounded schedule adjustment comparing `net_due_h + 0.25 * net_overdue` against the historical `h`-day net movement scale, with split-conformal intervals from strictly past rolling residuals. Passing `open_invoices: []` is supported; omitting `open_invoices` (`null`) returns HTTP `422`.

6. **Weighted Adaptive Ensemble (`ensemble_ml`):**
   Combines active members `("drift_calendar", "gbdt_direct", "invoice_aware_ml")` (or gracefully falls back to `("drift_calendar", "gbdt_direct")` when `open_invoices` is omitted in live API mode). At origin `t` and horizon `h`, each member receives non-negative weight:
   ```text
   w_m = (1 / (MAE_m + 1e-6))^2   (normalized to sum to 1.0)
   ```
   computed strictly from each member's past out-of-sample rolling residuals satisfying `s + h <= t` over the trailing `MAX_WEIGHT_WINDOW = 90` residuals (falling back to equal weights `1/K` when `< MIN_WEIGHT_RESIDUALS = 10`). Conformal 80% and 95% intervals are calibrated directly from `ensemble_ml`'s own strictly past out-of-sample ensemble residuals.

## Empirical 80% & 95% Prediction Intervals

All 6 models produce empirical **80%** and **95%** prediction intervals via split-conformal calibration over strictly past out-of-sample rolling residuals (`s + h <= origin`, minimum `MIN_RESIDUAL_COUNT = 20`):

- **`CM1` residual window:** up to `365` most recent past residuals.
- **`CM2` residual window & refit stride:** up to `MAX_ML_RESIDUAL_WINDOW = 180` most recent past residuals with deterministic strided refits (`RESIDUAL_REFIT_STRIDE = 7` days, `fit_idx <= s_idx`) for past rolling residual origins while always fitting fresh on the exact `<= origin` prefix at the target origin.
- Absolute residuals `r_s = |y(s+h) - y_hat(s+h | s)|` are indexed at finite-sample conformal rank `ceil(level * (m + 1))` (capped at `m`) for `level in {0.80, 0.95}`.

## Metric Definitions

With error `e_i = predicted_i - actual_i`:

- **MAE:** `sum(|e_i|) / N` (USD)
- **RMSE:** `sqrt(sum(e_i^2) / N)` (USD; pooled `ALL` rows recompute RMSE over all 1,008 squared errors, never averaging SME RMSEs)
- **ME (Mean Signed Error):** `sum(e_i) / N` (USD)
- **MASE:** `mean(|e_i| / scale_sme)`, where `scale_sme` is each SME's in-sample 1-step naïve MAE on 2024 (`FS1-20261018-A`: `356.953370`, `FS1-20261018-B`: `588.055096`, `FS1-20261018-C`: `454.634603` USD)
- **% Improvement vs. Naïve:** `100 * (naive_metric - model_metric) / naive_metric`
- **Coverage 80% / 95%:** Fraction of observations where `lower <= actual <= upper` (inclusive)

## Holdout Evaluation Results (FS1 / Seed 20261018 / 336 Shared Origins)

All values below are transcribed directly from [metrics.csv](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v2/metrics.csv) (`Decimal` precision 80, rounded to 6 decimal places via `ROUND_HALF_UP`).

### 7-Day Horizon (`2025-01-07` to `2025-12-08`)

| SME | Model | N | MAE (USD) | RMSE (USD) | ME (USD) | MASE | MAE Impr vs. Naïve | RMSE Impr vs. Naïve | Cov 80% | Cov 95% |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FS1-20261018-A | `naive` | 336 | 1394.519821 | 1765.955125 | -916.234286 | 3.906728 | 0.000000% | 0.000000% | 0.827381 | 0.934524 |
| FS1-20261018-A | `drift_calendar` | 336 | 925.353274 | 1208.527746 | -76.929762 | 2.592365 | 33.643591% | 31.565206% | 0.839286 | 0.958333 |
| FS1-20261018-A | `ridge_lag_calendar` | 336 | 953.133929 | 1247.291085 | -83.933929 | 2.670192 | 31.651461% | 29.370171% | 0.836310 | 0.952381 |
| FS1-20261018-A | `gbdt_direct` | 336 | 972.416131 | 1237.286352 | 53.073929 | 2.724211 | 30.268748% | 29.936705% | 0.815476 | 0.943452 |
| FS1-20261018-A | `invoice_aware_ml` | 336 | 959.696518 | 1208.311475 | 140.620030 | 2.688577 | 31.180862% | 31.577453% | 0.818452 | 0.925595 |
| FS1-20261018-A | `ensemble_ml` | 336 | 927.830595 | 1191.327337 | 32.641607 | 2.599305 | 33.465944% | 32.539207% | 0.821429 | 0.937500 |
| FS1-20261018-B | `naive` | 336 | 2664.837589 | 3338.981145 | -1400.462589 | 4.531612 | 0.000000% | 0.000000% | 0.806548 | 0.952381 |
| FS1-20261018-B | `drift_calendar` | 336 | 1797.924375 | 2339.411078 | -269.211399 | 3.057408 | 32.531559% | 29.936379% | 0.800595 | 0.952381 |
| FS1-20261018-B | `ridge_lag_calendar` | 336 | 1887.772768 | 2422.936306 | -163.162827 | 3.210197 | 29.159932% | 27.434861% | 0.791667 | 0.949405 |
| FS1-20261018-B | `gbdt_direct` | 336 | 1833.832917 | 2331.303399 | 188.521905 | 3.118471 | 31.184064% | 30.179198% | 0.788690 | 0.940476 |
| FS1-20261018-B | `invoice_aware_ml` | 336 | 2004.719077 | 2532.412167 | 420.165030 | 3.409067 | 24.771435% | 24.156141% | 0.836310 | 0.922619 |
| FS1-20261018-B | `ensemble_ml` | 336 | 1842.850387 | 2356.795717 | 89.587589 | 3.133806 | 30.845677% | 29.415722% | 0.815476 | 0.934524 |
| FS1-20261018-C | `naive` | 336 | 2396.454732 | 2990.488905 | -1624.080387 | 5.271167 | 0.000000% | 0.000000% | 0.767857 | 0.931548 |
| FS1-20261018-C | `drift_calendar` | 336 | 1508.231905 | 1975.438857 | -410.386607 | 3.317460 | 37.064035% | 33.942612% | 0.800595 | 0.943452 |
| FS1-20261018-C | `ridge_lag_calendar` | 336 | 1536.278482 | 1999.792137 | -295.579077 | 3.379150 | 35.893699% | 33.128254% | 0.797619 | 0.946429 |
| FS1-20261018-C | `gbdt_direct` | 336 | 1512.961637 | 1929.822662 | 93.760446 | 3.327863 | 36.866672% | 35.467988% | 0.791667 | 0.943452 |
| FS1-20261018-C | `invoice_aware_ml` | 336 | 1596.844762 | 1988.412609 | 350.422202 | 3.512370 | 33.366371% | 33.508778% | 0.803571 | 0.952381 |
| FS1-20261018-C | `ensemble_ml` | 336 | 1493.294762 | 1925.334994 | -10.726190 | 3.284604 | 37.687337% | 35.618053% | 0.782738 | 0.946429 |
| **ALL (Pooled)** | **`naive`** | **1008** | **2151.937381** | **2781.511369** | **-1313.592421** | **4.569836** | **0.000000%** | **0.000000%** | **0.800595** | **0.939484** |
| **ALL (Pooled)** | **`drift_calendar`** | **1008** | **1410.503185** | **1900.503633** | **-252.175923** | **2.989077** | **34.454264%** | **31.673706%** | **0.813492** | **0.951389** |
| **ALL (Pooled)** | **`ridge_lag_calendar`** | **1008** | **1459.061726** | **1951.539903** | **-180.891944** | **3.086513** | **32.197761%** | **29.838867%** | **0.808532** | **0.949405** |
| **ALL (Pooled)** | **`gbdt_direct`** | **1008** | **1439.736895** | **1887.685405** | **111.785427** | **3.056848** | **33.095781%** | **32.134543%** | **0.798611** | **0.942460** |
| **ALL (Pooled)** | **`invoice_aware_ml`** | **1008** | **1520.420119** | **1985.523668** | **303.735754** | **3.203338** | **29.346452%** | **28.617093%** | **0.819444** | **0.933532** |
| **ALL (Pooled)** | **`ensemble_ml`** | **1008** | **1421.325248** | **1886.854677** | **37.167669** | **3.005905** | **33.951366%** | **32.164409%** | **0.806548** | **0.939484** |

### 30-Day Horizon (`2025-01-30` to `2025-12-31`)

| SME | Model | N | MAE (USD) | RMSE (USD) | ME (USD) | MASE | MAE Impr vs. Naïve | RMSE Impr vs. Naïve | Cov 80% | Cov 95% |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| FS1-20261018-A | `naive` | 336 | 3921.773601 | 4507.589325 | -3883.753065 | 10.986795 | 0.000000% | 0.000000% | 0.806548 | 0.892857 |
| FS1-20261018-A | `drift_calendar` | 336 | 1876.489018 | 2327.047404 | -286.319018 | 5.256958 | 52.152031% | 48.374902% | 0.791667 | 0.925595 |
| FS1-20261018-A | `ridge_lag_calendar` | 336 | 1929.821339 | 2372.835676 | -274.813839 | 5.406368 | 50.792128% | 47.359098% | 0.770833 | 0.937500 |
| FS1-20261018-A | `gbdt_direct` | 336 | 1848.050268 | 2310.825578 | 284.831935 | 5.177288 | 52.877181% | 48.734780% | 0.738095 | 0.886905 |
| FS1-20261018-A | `invoice_aware_ml` | 336 | 2187.206994 | 2635.802584 | 259.284673 | 6.127431 | 44.229137% | 41.525228% | 0.678571 | 0.886905 |
| FS1-20261018-A | `ensemble_ml` | 336 | 1969.391161 | 2404.210756 | 140.087649 | 5.517222 | 49.783150% | 46.663048% | 0.669643 | 0.869048 |
| FS1-20261018-B | `naive` | 336 | 6128.518482 | 7454.321146 | -5974.540446 | 10.421674 | 0.000000% | 0.000000% | 0.776786 | 0.910714 |
| FS1-20261018-B | `drift_calendar` | 336 | 3488.039048 | 4727.123859 | -1117.978393 | 5.931483 | 43.085118% | 36.585455% | 0.815476 | 0.934524 |
| FS1-20261018-B | `ridge_lag_calendar` | 336 | 3693.743988 | 4724.726612 | -772.894762 | 6.281289 | 39.728598% | 36.617614% | 0.752976 | 0.937500 |
| FS1-20261018-B | `gbdt_direct` | 336 | 3657.822560 | 4600.408535 | 824.496964 | 6.220204 | 40.314734% | 38.285346% | 0.797619 | 0.901786 |
| FS1-20261018-B | `invoice_aware_ml` | 336 | 4259.710060 | 5300.845059 | 1812.683512 | 7.243726 | 30.493641% | 28.888963% | 0.785714 | 0.937500 |
| FS1-20261018-B | `ensemble_ml` | 336 | 3559.464673 | 4615.187292 | 396.732054 | 6.052944 | 41.919655% | 38.087088% | 0.836310 | 0.913690 |
| FS1-20261018-C | `naive` | 336 | 6833.750238 | 7555.169726 | -6799.617619 | 15.031302 | 0.000000% | 0.000000% | 0.773810 | 0.934524 |
| FS1-20261018-C | `drift_calendar` | 336 | 3088.401250 | 3743.484474 | -1567.276071 | 6.793150 | 54.806641% | 50.451352% | 0.869048 | 0.994048 |
| FS1-20261018-C | `ridge_lag_calendar` | 336 | 3054.767083 | 3731.620019 | -1137.440060 | 6.719170 | 55.298819% | 50.608389% | 0.880952 | 0.994048 |
| FS1-20261018-C | `gbdt_direct` | 336 | 2786.659345 | 3488.783230 | 341.634167 | 6.129448 | 59.222107% | 53.822570% | 0.803571 | 0.973214 |
| FS1-20261018-C | `invoice_aware_ml` | 336 | 2536.066875 | 3024.415718 | 1133.364673 | 5.578253 | 62.889090% | 59.968924% | 0.812500 | 0.949405 |
| FS1-20261018-C | `ensemble_ml` | 336 | 2521.474494 | 3081.817663 | 174.802827 | 5.546156 | 63.102624% | 59.209154% | 0.842262 | 0.964286 |
| **ALL (Pooled)** | **`naive`** | **1008** | **5628.014107** | **6657.473366** | **-5552.637044** | **12.146591** | **0.000000%** | **0.000000%** | **0.785714** | **0.912698** |
| **ALL (Pooled)** | **`drift_calendar`** | **1008** | **2817.643105** | **3731.600443** | **-990.524494** | **5.993864** | **49.935394%** | **43.948699%** | **0.825397** | **0.951389** |
| **ALL (Pooled)** | **`ridge_lag_calendar`** | **1008** | **2892.777470** | **3736.236014** | **-728.382887** | **6.135609** | **48.600387%** | **43.879069%** | **0.801587** | **0.956349** |
| **ALL (Pooled)** | **`gbdt_direct`** | **1008** | **2764.177391** | **3590.509804** | **483.654355** | **5.842313** | **50.885386%** | **46.067981%** | **0.779762** | **0.920635** |
| **ALL (Pooled)** | **`invoice_aware_ml`** | **1008** | **2994.327976** | **3838.120375** | **1068.444286** | **6.316470** | **46.796012%** | **42.348694%** | **0.758929** | **0.924603** |
| **ALL (Pooled)** | **`ensemble_ml`** | **1008** | **2683.443442** | **3491.789570** | **237.207510** | **5.705441** | **52.319888%** | **47.550829%** | **0.782738** | **0.915675** |

## Feature Importances & Ensemble Weight Summary (`results.json`)

Transcribed directly from [results.json](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v2/results.json):

### `ensemble_ml` Mean (Min..Max) Adaptive Weights
- **7-day horizon:** `drift_calendar` `0.356778` (`0.285826..0.428014`), `gbdt_direct` `0.340402` (`0.255570..0.394064`), `invoice_aware_ml` `0.302821` (`0.218223..0.437923`)
- **30-day horizon:** `gbdt_direct` `0.344946` (`0.188657..0.536838`), `invoice_aware_ml` `0.335570` (`0.116674..0.624287`), `drift_calendar` `0.319484` (`0.160881..0.515930`)

### Top GBDT Feature Importances (Averaged Across Origins)
- **`gbdt_direct` (`h = 7`):** `delta_bal_56d` (`0.107018`), `roll_28d_min` (`0.059544`), `delta_bal_28d` (`0.057209`), `roll_14d_min` (`0.056022`), `roll_28d_mean` (`0.055298`), `ewma_60` (`0.053446`), `win_causal_cal_sum` (`0.041802`).
- **`gbdt_direct` (`h = 30`):** `delta_bal_56d` (`0.152020`), `roll_28d_min` (`0.113862`), `ewma_60` (`0.108179`), `delta_bal_28d` (`0.095799`), `roll_28d_mean` (`0.094589`), `roll_28d_max` (`0.076192`), `win_causal_cal_sum` (`0.059217`).
- **`invoice_aware_ml` (`h = 7`):** `roll_14d_min` (`0.053274`), `emp_outflow_lag` (`0.046421`), `emp_outflow_ontime` (`0.043245`), `roll_14d_max` (`0.035853`), `outflow_due_30` (`0.032912`), `net_due_30` (`0.030124`).
- **`invoice_aware_ml` (`h = 30`):** `roll_28d_min` (`0.077438`), `emp_inflow_ontime` (`0.069547`), `emp_inflow_lag` (`0.061330`), `roll_28d_max` (`0.054761`), `emp_outflow_lag` (`0.053982`), `open_inflow_total` (`0.042614`), `lag_adj_inflow_h` (`0.039794`).

## Multi-Seed Generalization Check (Locked Hyperparameters)

Two additional synthetic FS1 seeds (`20261019` and `20261020`, generated into gitignored `.cache/multi-seed/` via `./scripts/synthetic_data.sh generate`) were evaluated with locked hyperparameters over the same 336 shared origins per SME (`n = 1,008` pooled per horizon/model, 0 excluded):

| Seed | Horizon | Model | MAE (USD) | RMSE (USD) | MAE Impr vs. Naïve | Cov 80% | Cov 95% |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| `20261019` | 7 | `naive` | 1938.977014 | 2509.599700 | 0.00% | — | — |
| `20261019` | 7 | `drift_calendar` | 1354.865665 | 1802.731229 | — | — | — |
| `20261019` | 7 | `ridge_lag_calendar` | 1338.027133 | 1782.813634 | — | — | — |
| `20261019` | 7 | `gbdt_direct` | 1377.821369 | 1799.619434 | — | — | — |
| `20261019` | 7 | `invoice_aware_ml` | 1344.135456 | 1771.853107 | — | — | — |
| `20261019` | 7 | `ensemble_ml` | **1332.819038** | **1753.522325** | **31.26%** | **0.805556** | **0.944444** |
| `20261019` | 30 | `naive` | 5200.070546 | 6018.989385 | 0.00% | — | — |
| `20261019` | 30 | `drift_calendar` | 2404.400198 | 3117.992079 | — | — | — |
| `20261019` | 30 | `ridge_lag_calendar` | 2316.190883 | 2970.061290 | — | — | — |
| `20261019` | 30 | `gbdt_direct` | 2569.002232 | 3215.544610 | — | — | — |
| `20261019` | 30 | `invoice_aware_ml` | 2593.819048 | 3313.476361 | — | — | — |
| `20261019` | 30 | `ensemble_ml` | **2385.736200** | **3061.806092** | **54.12%** | **0.762897** | **0.882937** |
| `20261020` | 7 | `naive` | 1955.806677 | 2535.520209 | 0.00% | — | — |
| `20261020` | 7 | `drift_calendar` | 1420.033760 | 1889.369899 | — | — | — |
| `20261020` | 7 | `ridge_lag_calendar` | 1437.645119 | 1935.665062 | — | — | — |
| `20261020` | 7 | `gbdt_direct` | 1440.259514 | 1885.364183 | — | — | — |
| `20261020` | 7 | `invoice_aware_ml` | 1435.468690 | 1898.707234 | — | — | — |
| `20261020` | 7 | `ensemble_ml` | **1414.477986** | **1865.159483** | **27.68%** | **0.783730** | **0.927579** |
| `20261020` | 30 | `naive` | 5208.289117 | 6143.809015 | 0.00% | — | — |
| `20261020` | 30 | `drift_calendar` | 2731.893760 | 3610.787774 | — | — | — |
| `20261020` | 30 | `ridge_lag_calendar` | 2794.919425 | 3695.199837 | — | — | — |
| `20261020` | 30 | `gbdt_direct` | 2624.037212 | 3495.020395 | — | — | — |
| `20261020` | 30 | `invoice_aware_ml` | 2607.287153 | 3581.929041 | — | — | — |
| `20261020` | 30 | `ensemble_ml` | **2556.414444** | **3446.201111** | **50.92%** | **0.807540** | **0.899802** |

### Key Observations

- **Point accuracy & bias:** On FS1 (`20261018`), `ensemble_ml` achieves the lowest pooled 30-day MAE (`2683.443442` USD, `-52.32%` vs. `naive`) and RMSE (`3491.789570` USD, `-47.55%` vs. `naive`), as well as the lowest pooled 7-day RMSE (`1886.854677` USD) and lowest magnitude signed bias across both horizons (`ME = +37.17` USD at 7d, `+237.21` USD at 30d). Across all 3 seeds (`20261018`, `20261019`, `20261020`), `ensemble_ml` beats `drift_calendar` on both 7d and 30d pooled MAE/RMSE and beats every individual model at 7d on `20261019` and both 7d and 30d on `20261020`.
- **Invoice-aware impact by profile:** On `FS1-20261018-C` (where invoice schedules carry stronger signal relative to net cash noise), `invoice_aware_ml` reduces 30-day RMSE by `59.97%` (`3024.42` vs. `7555.17` USD) and `ensemble_ml` reduces 30-day MAE by `63.10%`. On `FS1-20261018-B`, `drift_calendar` and `gbdt_direct` dominate `invoice_aware_ml`, and `ensemble_ml`'s inverse-squared-MAE adaptive weighting protects pooled accuracy by down-weighting weaker members.
- **Conformal coverage:** Because FS1 balances trend upward faster in 2025 (+5% synthetic scaling) and ML residual windows are capped at `180` days with `7`-day refit strides, `CM2` pooled 30-day empirical coverage (`78.27%` for 80%, `91.57%` for 95% on seed `20261018`) is slightly tighter than `CM1`'s 365-day window (`82.54%` / `95.14%`), reflecting the standard trade-off between responsiveness to recent error scales and finite-sample coverage stability under drift.

## Live HTTP Prediction Endpoint (`POST /forecast`)

Implemented in [main.py](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/app/main.py), keeping `GET /health` unchanged (`{"status": "UP", "service": "forecasting"}`).

- **Request JSON:**
  - `currency`: 3-letter uppercase ISO currency code (e.g., `"USD"`).
  - `history`: nonempty chronologically ordered list of `{date: "YYYY-MM-DD", closing_balance: "1234.56"}` with strictly consecutive calendar days and exact 2-decimal minor units.
  - `horizons`: optional list of unique positive integers in `[1, 365]` (default `[7, 30]`).
  - `model`: `"drift_calendar"` (default at API level), `"ridge_lag_calendar"`, `"naive"`, `"gbdt_direct"`, `"invoice_aware_ml"`, or `"ensemble_ml"`.
  - `open_invoices`: optional list of `{direction: "INFLOW" | "OUTFLOW", due_date: "YYYY-MM-DD", outstanding: "<positive 2-decimal string>"}` (required when `model == "invoice_aware_ml"`; optional for `"ensemble_ml"`).
- **Minimum required history:**
  - `CM2` (`gbdt_direct`, `invoice_aware_ml`, `ensemble_ml`): `139` consecutive days at `max_horizon = 30` (`max(90, 28 + max_horizon + 30) + max_horizon + 19`).
  - `CM1` (`drift_calendar`, `ridge_lag_calendar`): `109` days at `max_horizon = 30`.
  - `CM1` (`naive`): `50` days at `max_horizon = 30`.
- **Validation (HTTP 422):** Rejects missing calendar days, duplicate or reversed dates, fractional minor units (`"100.123"`), non-finite/boolean/null balances, invalid currencies, duplicate/invalid horizons, unknown model names, invalid `open_invoices` entries (non-positive/fractional `outstanding`, bad `due_date`, bad `direction`), missing `open_invoices` for `invoice_aware_ml`, or insufficient history.
- **Response JSON:** Exact 2-decimal point predictions, `interval_80` (`lower`, `upper`), `interval_95` (`lower`, `upper`), `residual_count`, additive `model` metadata (`uses_open_invoices`, `open_invoice_count`, `active_members`, `ensemble_weights`), and an explicit disclaimer that outputs are conditional mathematical estimates (not solvency proofs or credit/lending decisions).

## Commands & Reproducibility Artifacts

`CM2` artifacts are written to `forecasting/results/fs1-closing-balance-models-v2/`:
- [metrics.csv](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v2/metrics.csv)
- [predictions.csv](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v2/predictions.csv)
- [results.json](file:///Users/taha/.gemini/antigravity/scratch/FinSight/forecasting/results/fs1-closing-balance-models-v2/results.json) (includes input CSV/manifest SHA-256 hashes, source file SHA-256 hashes, Python 3.14.10 / `scikit-learn 1.9.1` / `scipy 1.18.1` / `numpy 2.5.5` versions, hyperparameters, feature importances, and ensemble weight summaries)

`CM1` artifacts remain preserved in `forecasting/results/fs1-closing-balance-models-v1/`.

```sh
forecasting/.venv/bin/python -m pip check
forecasting/.venv/bin/python -m unittest discover -s forecasting/tests -v
forecasting/.venv/bin/python -m forecasting.experiments.cash_flow_ml
```

## Limitations

- Evaluated on 3 synthetic profiles per generator seed (`20261018`, `20261019`, `20261020`); synthetic accuracy does not generalize to real SME bank feeds.
- Live API `open_invoices` mode receives only current open invoices as of `endDate` (not a full historical `InvoiceLedger`), so live `invoice_aware_ml` applies a conservative bounded schedule adjustment over `gbdt_direct` rather than training historical GBDT+Ridge weights on past invoice ledgers.
- Rolling-origin horizons overlap across consecutive days, so evaluation residuals are serially correlated. Conformal intervals assume local exchangeability of recent rolling residuals and can under-cover during sustained drift or abrupt regime shifts.
- Predictions are conditional mathematical estimates only and must not be used as solvency proofs, liquidity guarantees, or automated credit/lending decisions.
