# Evaluation Plan & Boundary

## Chronological Evaluation Protocol

All cash-flow forecasting benchmarks and models in FinSight share one strict chronological protocol on the synthetic **FS1 / seed 20261018** export (`data/synthetic/FS1-20261018/`, 731 consecutive calendar days per SME from `2024-01-01` through `2025-12-31`):

- **Initial training / prefix:** `2024-01-01` through `2024-12-31` (366 days per SME).
- **Holdout / test span:** `2025-01-01` through `2025-12-31` (365 days per SME).
- **Shared rolling origins:** `2024-12-31` through `2025-12-01` (336 origins per SME; 1,008 SME-origin pairs per horizon/model).
- **Horizons:** `7` and `30` calendar days ahead, predicting exact endpoint closing cash balance `y(t+h)`.

## Anti-Leakage Controls

- Every predictor accepts only an immutable, origin-bounded `History` prefix containing closing balances dated `<= origin`.
- Future actuals, raw invoices, raw transactions, and end-of-dataset `payment_status_at_cutoff` snapshots are excluded from predictors.
- Empirical prediction intervals (`80%` and `95%`) use split-conformal calibration over strictly past out-of-sample rolling residuals whose targets satisfy `s + h <= origin`.
- Perturbation unit tests verify that changing observations strictly after an origin (or after the final origin) leaves all earlier point predictions and interval bounds unchanged.

## Metrics & Pooling

For error `e_i = predicted_i - actual_i`:

- `MAE` and `RMSE` in USD (`Decimal` precision 80; pooled `ALL` rows recompute `RMSE` over all 1,008 squared errors rather than averaging SME RMSEs).
- `ME` (mean signed error) for directional bias.
- `MASE` scaled by each SME's in-sample 2024 1-step naïve MAE.
- `% improvement vs. naive` for both `MAE` and `RMSE`.
- Empirical `80%` and `95%` interval coverage (`lower <= actual <= upper`).

## Implemented Milestones

1. **Baseline Benchmark (`CB1`):** See [forecasting-baseline.md](forecasting-baseline.md) and `forecasting/experiments/cash_flow_baseline.py` (`naive` and `weekly_seasonal`, artifacts in `forecasting/results/fs1-closing-balance-baseline/`).
2. **Forecasting Models & Live Endpoint (`CM1`):** See [forecasting-models.md](forecasting-models.md), `forecasting/experiments/cash_flow_models.py`, and `POST /forecast` in `forecasting/app/main.py` (`drift_calendar` and `ridge_lag_calendar` vs. `naive`, empirical 80%/95% conformal intervals, artifacts in `forecasting/results/fs1-closing-balance-models-v1/`).

## Caveats

Synthetic FS1 results validate chronological alignment, leakage isolation, and pipeline reproducibility only. They must not be presented as real-world SME accuracy, solvency proof, or credit/lending eligibility.
