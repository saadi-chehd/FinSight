"""CM1: Leakage-safe cash-flow forecasting models, empirical conformal intervals, and evaluation."""

import argparse
import csv
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP, localcontext
import hashlib
import io
import json
import math
from pathlib import Path
import platform
from typing import Sequence

import numpy as np

from .baseline_data import (
    CENT,
    Dataset,
    History,
    MissingHistoryError,
    Observation,
    Series,
    load_dataset,
    money,
)


EXPERIMENT_VERSION = "CM1"
HORIZONS = (7, 30)
MODELS = ("naive", "drift_calendar", "ridge_lag_calendar")
DEFAULT_TRAIN_END = date(2024, 12, 31)
DEFAULT_TEST_END = date(2025, 12, 31)
DEFAULT_DATA_DIR = Path("data/synthetic/FS1-20261018")
DEFAULT_OUTPUT_DIR = Path("forecasting/results/fs1-closing-balance-models-v1")

MIN_FIT_DAYS: dict[str, int] = {
    "naive": 1,
    "drift_calendar": 60,
    "ridge_lag_calendar": 60,
}
MIN_RESIDUAL_COUNT = 20
MAX_RESIDUAL_WINDOW = 365

DRIFT_CALENDAR_K_DOM = 2.0
DRIFT_CALENDAR_K_DOW = 5.0
DRIFT_CALENDAR_ITERS = 6

RIDGE_LAGS = (1, 2, 3, 7, 14, 28)
RIDGE_ROLL_WINDOWS = (7, 14, 28)
RIDGE_MIN_LAG = 28
RIDGE_ALPHA = 100.0
RIDGE_FEATURE_COUNT = len(RIDGE_LAGS) + len(RIDGE_ROLL_WINDOWS) + 7 + 31 + 1

INTERVAL_LEVEL_80 = Decimal("0.80")
INTERVAL_LEVEL_95 = Decimal("0.95")

DISCLAIMER = (
    "Conditional mathematical estimates derived solely from historical daily closing cash balances. "
    "Outputs are not solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions."
)

MODEL_DESCRIPTIONS: dict[str, str] = {
    "naive": "Persistence benchmark: y_hat(t+h|t) = y(t).",
    "drift_calendar": (
        "Decomposes daily net cash movements into sample drift plus shrunk day-of-week and "
        "day-of-month recurring calendar effects fitted strictly on history <= origin, rolled forward cumulatively."
    ),
    "ridge_lag_calendar": (
        "L2-regularized Ridge regression (alpha=100.0) on daily net movement using lag, rolling-mean, "
        "day-of-week, day-of-month, and quarter-end-month features standardized strictly on history <= origin, "
        "rolled forward recursively."
    ),
}


def quantize_money(value: Decimal | float) -> Decimal:
    """Quantize a finite Decimal or float to exact 2-decimal minor units within NUMERIC(19,2)."""
    if isinstance(value, bool):
        raise ValueError("Boolean is not a valid monetary value")
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("Monetary value must be finite")
        with localcontext() as context:
            context.prec = 80
            return money(value.quantize(CENT, rounding=ROUND_HALF_UP))
    if isinstance(value, (int, float)):
        numeric = float(value)
        if not math.isfinite(numeric):
            raise ValueError("Monetary value must be finite")
        with localcontext() as context:
            context.prec = 80
            return money(Decimal(f"{numeric:.10f}").quantize(CENT, rounding=ROUND_HALF_UP))
    raise ValueError("Unsupported type for monetary quantization")


def _validate_model_and_horizons(model: str, horizons: Sequence[int]) -> tuple[int, ...]:
    if model not in MODELS:
        raise ValueError(f"Unknown model {model!r}; expected one of {MODELS}")
    if not horizons:
        raise ValueError("At least one forecast horizon is required")
    validated: list[int] = []
    for horizon in horizons:
        if not isinstance(horizon, int) or isinstance(horizon, bool) or horizon < 1:
            raise ValueError("Horizons must be positive integers")
        validated.append(horizon)
    return tuple(validated)


def min_required_history_days(model: str, max_horizon: int) -> int:
    """Minimum consecutive observed calendar days required for point + conformal interval prediction."""
    if model not in MODELS:
        raise ValueError(f"Unknown model {model!r}; expected one of {MODELS}")
    if not isinstance(max_horizon, int) or isinstance(max_horizon, bool) or max_horizon < 1:
        raise ValueError("max_horizon must be a positive integer")
    return MIN_FIT_DAYS[model] + max_horizon + MIN_RESIDUAL_COUNT - 1


def _fit_predict_drift_calendar(
    observations: tuple[Observation, ...],
    origin: date,
    horizons: tuple[int, ...],
) -> dict[int, Decimal]:
    dates = [obs.day for obs in observations[1:]]
    nets = [float(observations[i].closing - observations[i - 1].closing) for i in range(1, len(observations))]
    n = len(nets)
    mu = sum(nets) / n
    alpha = [0.0] * 7
    beta = [0.0] * 32

    for _ in range(DRIFT_CALENDAR_ITERS):
        s_dom = [0.0] * 32
        c_dom = [0] * 32
        for d, net in zip(dates, nets):
            s_dom[d.day] += net - mu - alpha[d.weekday()]
            c_dom[d.day] += 1
        for m in range(1, 32):
            beta[m] = s_dom[m] / (c_dom[m] + DRIFT_CALENDAR_K_DOM) if c_dom[m] else 0.0
        mean_beta = sum(beta[m] * c_dom[m] for m in range(1, 32)) / n
        for m in range(1, 32):
            if c_dom[m]:
                beta[m] -= mean_beta

        s_dow = [0.0] * 7
        c_dow = [0] * 7
        for d, net in zip(dates, nets):
            s_dow[d.weekday()] += net - mu - beta[d.day]
            c_dow[d.weekday()] += 1
        for w in range(7):
            alpha[w] = s_dow[w] / (c_dow[w] + DRIFT_CALENDAR_K_DOW) if c_dow[w] else 0.0
        mean_alpha = sum(alpha[w] * c_dow[w] for w in range(7)) / n
        for w in range(7):
            if c_dow[w]:
                alpha[w] -= mean_alpha

    horizon_set = set(horizons)
    last_close = float(observations[-1].closing)
    cum = 0.0
    predictions: dict[int, Decimal] = {}
    for step in range(1, max(horizons) + 1):
        fd = origin + timedelta(days=step)
        cum += mu + alpha[fd.weekday()] + beta[fd.day]
        if step in horizon_set:
            predictions[step] = quantize_money(last_close + cum)
    return predictions


def _ridge_single_feature(fd: date, buf: Sequence[float]) -> np.ndarray:
    feat = np.zeros(RIDGE_FEATURE_COUNT, dtype=np.float64)
    col = 0
    for lag in RIDGE_LAGS:
        feat[col] = buf[-lag]
        col += 1
    for window in RIDGE_ROLL_WINDOWS:
        feat[col] = sum(buf[-window:]) / window
        col += 1
    feat[col + fd.weekday()] = 1.0
    col += 7
    feat[col + (fd.day - 1)] = 1.0
    col += 31
    if fd.month in (3, 6, 9, 12):
        feat[col] = 1.0
    return feat


def _fit_predict_ridge(
    observations: tuple[Observation, ...],
    origin: date,
    horizons: tuple[int, ...],
) -> dict[int, Decimal]:
    dates = [obs.day for obs in observations[1:]]
    nets = np.array(
        [float(observations[i].closing - observations[i - 1].closing) for i in range(1, len(observations))],
        dtype=np.float64,
    )
    m = len(nets)
    n_rows = m - RIDGE_MIN_LAG
    if n_rows < 1:
        raise MissingHistoryError("Insufficient observations to construct Ridge lag features")

    X = np.zeros((n_rows, RIDGE_FEATURE_COUNT), dtype=np.float64)
    col = 0
    for lag in RIDGE_LAGS:
        X[:, col] = nets[RIDGE_MIN_LAG - lag : m - lag]
        col += 1

    cumsum = np.concatenate(([0.0], np.cumsum(nets)))
    for window in RIDGE_ROLL_WINDOWS:
        X[:, col] = (cumsum[RIDGE_MIN_LAG:m] - cumsum[RIDGE_MIN_LAG - window : m - window]) / window
        col += 1

    target_dates = dates[RIDGE_MIN_LAG:]
    row_idx = np.arange(n_rows)
    dows = np.fromiter((d.weekday() for d in target_dates), dtype=np.int64, count=n_rows)
    doms = np.fromiter((d.day - 1 for d in target_dates), dtype=np.int64, count=n_rows)
    qends = np.fromiter((1.0 if d.month in (3, 6, 9, 12) else 0.0 for d in target_dates), dtype=np.float64, count=n_rows)

    X[row_idx, col + dows] = 1.0
    col += 7
    X[row_idx, col + doms] = 1.0
    col += 31
    X[:, col] = qends

    y = nets[RIDGE_MIN_LAG:]
    y_mean = float(np.mean(y))
    x_mean = np.mean(X, axis=0)
    x_std = np.std(X, axis=0)
    active = x_std > 1e-12

    Z = np.zeros_like(X)
    Z[:, active] = (X[:, active] - x_mean[active]) / x_std[active]

    gram = Z.T @ Z
    gram[np.diag_indices_from(gram)] += RIDGE_ALPHA
    weights = np.linalg.solve(gram, Z.T @ (y - y_mean))

    buf = nets.tolist()
    horizon_set = set(horizons)
    last_close = float(observations[-1].closing)
    cum = 0.0
    predictions: dict[int, Decimal] = {}
    for step in range(1, max(horizons) + 1):
        fd = origin + timedelta(days=step)
        feat = _ridge_single_feature(fd, buf)
        z = np.zeros(RIDGE_FEATURE_COUNT, dtype=np.float64)
        z[active] = (feat[active] - x_mean[active]) / x_std[active]
        pred_net = y_mean + float(z @ weights)
        buf.append(pred_net)
        cum += pred_net
        if step in horizon_set:
            predictions[step] = quantize_money(last_close + cum)
    return predictions


def predict_horizons(history: History, horizons: Sequence[int], model: str) -> dict[int, Decimal]:
    """Predict closing balances strictly from an origin-bounded History prefix."""
    validated_horizons = _validate_model_and_horizons(model, horizons)
    if len(history.observations) < MIN_FIT_DAYS[model]:
        raise MissingHistoryError(
            f"Model {model!r} requires at least {MIN_FIT_DAYS[model]} consecutive observations; "
            f"received {len(history.observations)}"
        )
    if model == "naive":
        latest = history.value(history.origin)
        return {h: latest for h in validated_horizons}
    if model == "drift_calendar":
        return _fit_predict_drift_calendar(history.observations, history.origin, validated_horizons)
    return _fit_predict_ridge(history.observations, history.origin, validated_horizons)


def conformal_radius(abs_residuals: Sequence[Decimal], level: Decimal) -> Decimal:
    """Exact finite-sample split-conformal radius from strictly past out-of-sample absolute residuals."""
    m = len(abs_residuals)
    if m < MIN_RESIDUAL_COUNT:
        raise MissingHistoryError(
            f"Prediction intervals require at least {MIN_RESIDUAL_COUNT} past residuals; got {m}"
        )
    if not isinstance(level, Decimal) or not (Decimal(0) < level < Decimal(1)):
        raise ValueError("Confidence level must be a Decimal strictly between 0 and 1")
    ordered = sorted(abs_residuals)
    with localcontext() as context:
        context.prec = 80
        rank = min(m, int((level * Decimal(m + 1)).to_integral_value(rounding=ROUND_CEILING)))
    return money(ordered[rank - 1])


@dataclass(frozen=True)
class IntervalForecast:
    horizon_days: int
    target_date: date
    predicted: Decimal
    lower_80: Decimal
    upper_80: Decimal
    lower_95: Decimal
    upper_95: Decimal
    residual_count: int


def predict_with_intervals(
    history: History,
    horizons: Sequence[int],
    model: str,
) -> dict[int, IntervalForecast]:
    """Compute point forecasts and empirical 80%/95% conformal intervals strictly from History."""
    validated_horizons = _validate_model_and_horizons(model, horizons)
    max_h = max(validated_horizons)
    required = min_required_history_days(model, max_h)
    obs = history.observations
    n = len(obs)
    if n < required:
        raise MissingHistoryError(
            f"Model {model!r} at max horizon {max_h} requires at least {required} consecutive days "
            f"(fit={MIN_FIT_DAYS[model]}, residual_min={MIN_RESIDUAL_COUNT}); received {n}"
        )

    point_preds = predict_horizons(history, validated_horizons, model)
    min_idx = MIN_FIT_DAYS[model] - 1

    eligible_by_h: dict[int, list[int]] = {}
    needed_by_idx: dict[int, set[int]] = {}
    for h in validated_horizons:
        start_idx = max(min_idx, n - 1 - h - (MAX_RESIDUAL_WINDOW - 1))
        end_idx = n - h  # exclusive: s_idx + h <= n - 1 (origin)
        indices = list(range(start_idx, end_idx))
        eligible_by_h[h] = indices
        for idx in indices:
            needed_by_idx.setdefault(idx, set()).add(h)

    past_preds: dict[int, dict[int, Decimal]] = {}
    for idx, hs in sorted(needed_by_idx.items()):
        sub_history = History(obs[idx].day, obs[: idx + 1])
        past_preds[idx] = predict_horizons(sub_history, tuple(sorted(hs)), model)

    results: dict[int, IntervalForecast] = {}
    for h in validated_horizons:
        abs_residuals = [
            abs(obs[idx + h].closing - past_preds[idx][h])
            for idx in eligible_by_h[h]
        ]
        r80 = conformal_radius(abs_residuals, INTERVAL_LEVEL_80)
        r95 = conformal_radius(abs_residuals, INTERVAL_LEVEL_95)
        pred = point_preds[h]
        results[h] = IntervalForecast(
            horizon_days=h,
            target_date=history.origin + timedelta(days=h),
            predicted=pred,
            lower_80=pred - r80,
            upper_80=pred + r80,
            lower_95=pred - r95,
            upper_95=pred + r95,
            residual_count=len(abs_residuals),
        )
    return results


@dataclass(frozen=True)
class Split:
    company_key: str
    train_start: date
    train_end: date
    train_rows: int
    test_start: date
    test_end: date
    test_rows: int


def chronological_split(series: Series, train_end: date, test_end: date) -> Split:
    first, last = series.observations[0].day, series.observations[-1].day
    if not first <= train_end < test_end <= last:
        raise ValueError("Split needs nonempty chronological train/test spans within the observed series")
    return Split(
        series.company_key,
        first,
        train_end,
        (train_end - first).days + 1,
        train_end + timedelta(days=1),
        test_end,
        (test_end - train_end).days,
    )


@dataclass(frozen=True)
class ModelPrediction:
    company_key: str
    currency: str
    origin: date
    horizon_days: int
    target_date: date
    model: str
    predicted: Decimal
    actual: Decimal
    lower_80: Decimal
    upper_80: Decimal
    lower_95: Decimal
    upper_95: Decimal
    residual_count: int


@dataclass(frozen=True)
class ExcludedOrigin:
    origin: date
    missing_history_smes: tuple[str, ...]
    reason: str = "insufficient_history_for_model_or_intervals; excluded for all models, horizons, and SMEs"


@dataclass(frozen=True)
class ModelEvaluation:
    splits: tuple[Split, ...]
    origins: tuple[date, ...]
    excluded: tuple[ExcludedOrigin, ...]
    predictions: tuple[ModelPrediction, ...]
    train_scales: dict[str, Decimal]


def evaluate(series: Sequence[Series], train_end: date, test_end: date) -> ModelEvaluation:
    """Rolling-origin evaluation with strictly prefix-bounded fitting and strictly past residuals."""
    ordered = tuple(sorted(series, key=lambda item: item.company_key))
    if not ordered or len({item.company_key for item in ordered}) != len(ordered):
        raise ValueError("At least one uniquely identified SME is required")
    if len({item.currency for item in ordered}) != 1:
        raise ValueError("Pooled monetary metrics cannot silently combine different currencies")

    splits = tuple(chronological_split(item, train_end, test_end) for item in ordered)
    max_h = max(HORIZONS)
    last_origin = test_end - timedelta(days=max_h)
    if last_origin < train_end:
        raise ValueError("Test span must contain the complete longest-horizon target")

    required_days = max(min_required_history_days(m, max_h) for m in MODELS)
    candidate_origins = [
        train_end + timedelta(days=offset)
        for offset in range((last_origin - train_end).days + 1)
    ]

    origins: list[date] = []
    excluded: list[ExcludedOrigin] = []
    for origin in candidate_origins:
        missing = []
        for item in ordered:
            obs_count = (origin - item.observations[0].day).days + 1
            if obs_count < required_days:
                missing.append(item.company_key)
        if missing:
            excluded.append(ExcludedOrigin(origin, tuple(missing)))
            continue
        origins.append(origin)

    if not origins:
        raise MissingHistoryError("No common origins satisfy the minimum model + interval history requirement")

    train_scales: dict[str, Decimal] = {}
    with localcontext() as context:
        context.prec = 80
        for item in ordered:
            train_end_idx = (train_end - item.observations[0].day).days
            if train_end_idx < 1:
                raise ValueError(f"SME {item.company_key} needs at least 2 training days to compute MASE scale")
            diffs = [
                abs(item.observations[i].closing - item.observations[i - 1].closing)
                for i in range(1, train_end_idx + 1)
            ]
            train_scales[item.company_key] = sum(diffs, Decimal(0)) / Decimal(len(diffs))

    # Precompute point forecasts strictly from prefixes obs[:idx+1] for each SME and model.
    # For origin t (index t_idx), residuals only reference s_idx where s_idx + h <= t_idx.
    predictions: list[ModelPrediction] = []
    origin_set = set(origins)
    first_eval_origin = origins[0]

    per_sme_records: dict[tuple[str, date, int, str], ModelPrediction] = {}
    for item in ordered:
        obs = item.observations
        start_day = obs[0].day
        first_eval_idx = (first_eval_origin - start_day).days
        last_eval_idx = (origins[-1] - start_day).days

        for model in MODELS:
            min_fit_idx = MIN_FIT_DAYS[model] - 1
            earliest_needed_idx = max(
                min_fit_idx,
                first_eval_idx - max_h - (MAX_RESIDUAL_WINDOW - 1),
            )
            cached_point: dict[int, dict[int, Decimal]] = {}
            for idx in range(earliest_needed_idx, last_eval_idx + 1):
                prefix_history = History(obs[idx].day, obs[: idx + 1])
                cached_point[idx] = predict_horizons(prefix_history, HORIZONS, model)

            for t_idx in range(first_eval_idx, last_eval_idx + 1):
                origin = obs[t_idx].day
                if origin not in origin_set:
                    continue
                for horizon in HORIZONS:
                    point = cached_point[t_idx][horizon]
                    start_res_idx = max(min_fit_idx, t_idx - horizon - (MAX_RESIDUAL_WINDOW - 1))
                    end_res_idx = t_idx - horizon + 1  # s_idx + horizon <= t_idx
                    abs_residuals = [
                        abs(obs[s_idx + horizon].closing - cached_point[s_idx][horizon])
                        for s_idx in range(start_res_idx, end_res_idx)
                    ]
                    r80 = conformal_radius(abs_residuals, INTERVAL_LEVEL_80)
                    r95 = conformal_radius(abs_residuals, INTERVAL_LEVEL_95)
                    target_date = origin + timedelta(days=horizon)
                    actual = item.actual(target_date)
                    per_sme_records[(item.company_key, origin, horizon, model)] = ModelPrediction(
                        company_key=item.company_key,
                        currency=item.currency,
                        origin=origin,
                        horizon_days=horizon,
                        target_date=target_date,
                        model=model,
                        predicted=point,
                        actual=actual,
                        lower_80=point - r80,
                        upper_80=point + r80,
                        lower_95=point - r95,
                        upper_95=point + r95,
                        residual_count=len(abs_residuals),
                    )

    for origin in origins:
        for item in ordered:
            for horizon in HORIZONS:
                for model in MODELS:
                    predictions.append(per_sme_records[(item.company_key, origin, horizon, model)])

    return ModelEvaluation(splits, tuple(origins), tuple(excluded), tuple(predictions), train_scales)


def mae_rmse_me(actuals: Sequence[Decimal], predictions: Sequence[Decimal]) -> tuple[Decimal, Decimal, Decimal]:
    """Compute MAE, RMSE, and Mean Signed Error (predicted - actual) at precision 80."""
    if len(actuals) != len(predictions) or not actuals:
        raise ValueError("Metrics require equal nonempty paired arrays")
    if any(not isinstance(value, Decimal) or not value.is_finite() for value in (*actuals, *predictions)):
        raise ValueError("Metrics require finite Decimal values")
    with localcontext() as context:
        context.prec = 80
        errors = [predicted - actual for actual, predicted in zip(actuals, predictions)]
        n = Decimal(len(errors))
        mae = sum((abs(error) for error in errors), Decimal(0)) / n
        rmse = (sum((error * error for error in errors), Decimal(0)) / n).sqrt()
        me = sum(errors, Decimal(0)) / n
        return mae, rmse, me


def metric_text(value: Decimal) -> str:
    with localcontext() as context:
        context.prec = 80
        return format(value.quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP), "f")


def summarize(predictions: Sequence[ModelPrediction], train_scales: dict[str, Decimal]) -> list[dict]:
    if not predictions or len({row.currency for row in predictions}) != 1:
        raise ValueError("Summary requires nonempty single-currency predictions")
    keys = tuple(sorted({row.company_key for row in predictions}))
    scores: dict[tuple[int, str, str], tuple[int, Decimal, Decimal, Decimal, Decimal | None, Decimal, Decimal]] = {}

    with localcontext() as context:
        context.prec = 80
        for horizon in HORIZONS:
            for model in MODELS:
                for key in (*keys, "ALL"):
                    group = [
                        row for row in predictions
                        if row.horizon_days == horizon
                        and row.model == model
                        and (key == "ALL" or row.company_key == key)
                    ]
                    mae, rmse, me = mae_rmse_me(
                        [row.actual for row in group],
                        [row.predicted for row in group],
                    )
                    n = len(group)
                    n_dec = Decimal(n)
                    group_keys = {row.company_key for row in group}
                    if any(train_scales.get(k, Decimal(0)) <= 0 for k in group_keys):
                        mase = None
                    else:
                        scaled_errors = [
                            abs(row.predicted - row.actual) / train_scales[row.company_key]
                            for row in group
                        ]
                        mase = sum(scaled_errors, Decimal(0)) / n_dec
                    cov80 = Decimal(sum(1 for r in group if r.lower_80 <= r.actual <= r.upper_80)) / n_dec
                    cov95 = Decimal(sum(1 for r in group if r.lower_95 <= r.actual <= r.upper_95)) / n_dec
                    scores[(horizon, model, key)] = (n, mae, rmse, me, mase, cov80, cov95)

        result = []
        for (horizon, model, key), (n, mae, rmse, me, mase, cov80, cov95) in scores.items():
            _, naive_mae, naive_rmse, *_ = scores[(horizon, "naive", key)]
            result.append({
                "company_key": key,
                "currency": predictions[0].currency,
                "horizon_days": horizon,
                "model": model,
                "n": n,
                "mae": metric_text(mae),
                "rmse": metric_text(rmse),
                "me": metric_text(me),
                "mase": metric_text(mase) if mase is not None else None,
                "mae_delta_vs_naive": metric_text(mae - naive_mae),
                "rmse_delta_vs_naive": metric_text(rmse - naive_rmse),
                "mae_improvement_pct": metric_text(100 * (naive_mae - mae) / naive_mae) if naive_mae else None,
                "rmse_improvement_pct": metric_text(100 * (naive_rmse - rmse) / naive_rmse) if naive_rmse else None,
                "coverage_80": metric_text(cov80),
                "coverage_95": metric_text(cov95),
            })
    return result


def _csv(rows: list[dict]) -> str:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=tuple(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue()


def artifacts(dataset: Dataset, evaluation: ModelEvaluation) -> dict[str, str]:
    metrics = summarize(evaluation.predictions, evaluation.train_scales)
    predictions = [
        {
            "is_synthetic": "true",
            "company_key": row.company_key,
            "currency": row.currency,
            "origin": row.origin.isoformat(),
            "horizon_days": row.horizon_days,
            "target_date": row.target_date.isoformat(),
            "model": row.model,
            "predicted_closing": format(row.predicted, ".2f"),
            "actual_closing": format(row.actual, ".2f"),
            "lower_80": format(row.lower_80, ".2f"),
            "upper_80": format(row.upper_80, ".2f"),
            "lower_95": format(row.lower_95, ".2f"),
            "upper_95": format(row.upper_95, ".2f"),
            "residual_count": row.residual_count,
        }
        for row in evaluation.predictions
    ]
    origin_start, origin_end = evaluation.origins[0], evaluation.origins[-1]
    report = {
        "experiment_version": EXPERIMENT_VERSION,
        "is_synthetic": True,
        "target": "daily_closing_cash_balance",
        "unit": evaluation.predictions[0].currency,
        "horizon_days": list(HORIZONS),
        "models": MODEL_DESCRIPTIONS,
        "hyperparameters": {
            "min_fit_days": MIN_FIT_DAYS,
            "min_residual_count": MIN_RESIDUAL_COUNT,
            "max_residual_window": MAX_RESIDUAL_WINDOW,
            "drift_calendar": {
                "k_dom": DRIFT_CALENDAR_K_DOM,
                "k_dow": DRIFT_CALENDAR_K_DOW,
                "backfit_iterations": DRIFT_CALENDAR_ITERS,
            },
            "ridge_lag_calendar": {
                "lags": list(RIDGE_LAGS),
                "rolling_windows": list(RIDGE_ROLL_WINDOWS),
                "min_lag": RIDGE_MIN_LAG,
                "alpha": RIDGE_ALPHA,
                "feature_count": RIDGE_FEATURE_COUNT,
            },
        },
        "protocol": {
            "forecast_origin": "after closing on t; history contains only dates <= t",
            "rolling_observations": True,
            "interval_calibration": (
                f"split conformal from strictly past rolling out-of-sample residuals "
                f"(s + h <= t, window <= {MAX_RESIDUAL_WINDOW}, min {MIN_RESIDUAL_COUNT})"
            ),
            "common_origin_start": origin_start.isoformat(),
            "common_origin_end": origin_end.isoformat(),
            "common_origin_count_per_sme": len(evaluation.origins),
            "candidate_origin_count": len(evaluation.origins) + len(evaluation.excluded),
            "excluded_origins": [
                {**asdict(row), "origin": row.origin.isoformat()} for row in evaluation.excluded
            ],
            "target_date_ranges": {
                str(h): {
                    "start": (origin_start + timedelta(days=h)).isoformat(),
                    "end": (origin_end + timedelta(days=h)).isoformat(),
                }
                for h in HORIZONS
            },
            "metric_aggregation": "pool all absolute/squared/signed errors within each horizon/model; never average SME RMSE",
            "metric_serialization": "6 decimal places, ROUND_HALF_UP; Decimal precision 80 before serialization",
        },
        "train_in_sample_scales": {
            key: metric_text(value) for key, value in sorted(evaluation.train_scales.items())
        },
        "splits": [
            {
                key: value.isoformat() if isinstance(value, date) else value
                for key, value in asdict(split).items()
            }
            for split in evaluation.splits
        ],
        "input": {
            "generator_version": "FS1",
            "seed": dataset.seed,
            "start": dataset.start.isoformat(),
            "end": dataset.end.isoformat(),
            "manifest_sha256": dataset.manifest_sha256,
            "csv_sha256": dict(dataset.file_hashes),
        },
        "runtime": {
            "python_version": platform.python_version(),
            "third_party_dependencies": {"numpy": np.__version__},
        },
        "source_sha256": {
            name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
            for name in ("baseline_data.py", "cash_flow_models.py")
        },
        "prediction_row_count": len(predictions),
        "metrics": metrics,
        "disclaimer": DISCLAIMER,
    }
    return {
        "predictions.csv": _csv(predictions),
        "metrics.csv": _csv(metrics),
        "results.json": json.dumps(report, indent=2, sort_keys=True) + "\n",
    }


def write_artifacts(contents: dict[str, str], output: Path, data_directory: Path) -> None:
    if output.resolve().is_relative_to(data_directory.resolve()):
        raise ValueError("Experiment output must be separate from the immutable synthetic data directory")
    for name, body in contents.items():
        target = output / name
        if target.exists() and target.read_bytes() != body.encode("utf-8"):
            raise ValueError(f"Existing result differs; choose a new output directory: {target}")
    output.mkdir(parents=True, exist_ok=True)
    for name, body in contents.items():
        target = output / name
        if not target.exists():
            with target.open("x", encoding="utf-8", newline="") as handle:
                handle.write(body)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--train-end", type=date.fromisoformat, default=DEFAULT_TRAIN_END)
    parser.add_argument("--test-end", type=date.fromisoformat, default=DEFAULT_TEST_END)
    args = parser.parse_args(argv)
    try:
        dataset = load_dataset(args.data_dir)
        evaluation = evaluate(dataset.series, args.train_end, args.test_end)
        contents = artifacts(dataset, evaluation)
        write_artifacts(contents, args.output_dir, args.data_dir)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"Model experiment failed: {error}\n")
    print(
        f"SYNTHETIC ONLY: {len(dataset.series)} SMEs; {len(evaluation.origins)} common origins each; "
        f"{len(evaluation.predictions)} prediction rows; {len(evaluation.excluded)} excluded origins."
    )
    print(contents["metrics.csv"], end="")


if __name__ == "__main__":
    main()
