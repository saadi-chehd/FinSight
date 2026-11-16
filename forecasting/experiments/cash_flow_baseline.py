"""CB1: fixed-rule, rolling-origin closing-cash benchmarks; Python standard library only."""

import argparse
import csv
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
import hashlib
import io
import json
from pathlib import Path
import platform
from typing import Sequence

from .baseline_data import Dataset, History, MissingHistoryError, Series, load_dataset


HORIZONS = (7, 30)
BASELINES = ("naive", "weekly_seasonal")
SEASON_DAYS = 7
DEFAULT_TRAIN_END = date(2024, 12, 31)
DEFAULT_TEST_END = date(2025, 12, 31)


@dataclass(frozen=True)
class Forecast:
    value: Decimal
    reference_date: date


def predict(history: History, horizon: int, baseline: str) -> Forecast:
    """Only observed prefix data are accepted; the full series/targets cannot enter here."""
    if not isinstance(horizon, int) or isinstance(horizon, bool) or horizon < 1:
        raise ValueError("Horizon must be a positive number of calendar days")
    if baseline == "naive":
        reference = history.origin
    elif baseline == "weekly_seasonal":
        # Require a complete observed week; no fallback, imputation, or actual future values.
        for offset in range(SEASON_DAYS):
            history.value(history.origin - timedelta(days=offset))
        weeks = (horizon + SEASON_DAYS - 1) // SEASON_DAYS
        reference = history.origin + timedelta(days=horizon - SEASON_DAYS * weeks)
    else:
        raise ValueError("Unknown baseline; no silent substitution")
    return Forecast(history.value(reference), reference)


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
    return Split(series.company_key, first, train_end, (train_end - first).days + 1,
                 train_end + timedelta(days=1), test_end, (test_end - train_end).days)


@dataclass(frozen=True)
class Prediction:
    company_key: str
    currency: str
    origin: date
    horizon_days: int
    target_date: date
    baseline: str
    input_reference_date: date
    predicted: Decimal
    actual: Decimal


@dataclass(frozen=True)
class ExcludedOrigin:
    origin: date
    missing_history_smes: tuple[str, ...]
    reason: str = "insufficient_weekly_history; excluded for both models, horizons, and all SMEs"


@dataclass(frozen=True)
class Evaluation:
    splits: tuple[Split, ...]
    origins: tuple[date, ...]
    excluded: tuple[ExcludedOrigin, ...]
    predictions: tuple[Prediction, ...]


def evaluate(series: Sequence[Series], train_end: date, test_end: date) -> Evaluation:
    ordered = tuple(sorted(series, key=lambda item: item.company_key))
    if not ordered or len({item.company_key for item in ordered}) != len(ordered):
        raise ValueError("At least one uniquely identified SME is required")
    if len({item.currency for item in ordered}) != 1:
        raise ValueError("Pooled monetary metrics cannot silently combine different currencies")
    splits = tuple(chronological_split(item, train_end, test_end) for item in ordered)
    last_origin = test_end - timedelta(days=max(HORIZONS))
    if last_origin < train_end:
        raise ValueError("Test span must contain the complete longest-horizon target")
    origins, excluded, predictions = [], [], []
    for offset in range((last_origin - train_end).days + 1):
        origin = train_end + timedelta(days=offset)
        histories = {item.company_key: item.history(origin) for item in ordered}
        missing = []
        for key, history in histories.items():
            try:
                predict(history, max(HORIZONS), "weekly_seasonal")
            except MissingHistoryError:
                missing.append(key)
        if missing:
            excluded.append(ExcludedOrigin(origin, tuple(missing)))
            continue
        origins.append(origin)
        for item in ordered:
            history = histories[item.company_key]
            for horizon in HORIZONS:
                # Compute both forecasts before looking up the target actual.
                estimates = [(baseline, predict(history, horizon, baseline)) for baseline in BASELINES]
                target_date = origin + timedelta(days=horizon)
                actual = item.actual(target_date)
                for baseline, forecast in estimates:
                    predictions.append(Prediction(
                        item.company_key, item.currency, origin, horizon, target_date,
                        baseline, forecast.reference_date, forecast.value, actual,
                    ))
    if not origins:
        raise MissingHistoryError("No common origins have the complete observed seasonal history")
    return Evaluation(splits, tuple(origins), tuple(excluded), tuple(predictions))


def mae_rmse(actuals: Sequence[Decimal], predictions: Sequence[Decimal]) -> tuple[Decimal, Decimal]:
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
        return mae, rmse


def metric_text(value: Decimal) -> str:
    with localcontext() as context:
        context.prec = 80
        return format(value.quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP), "f")


def summarize(predictions: Sequence[Prediction]) -> list[dict]:
    if not predictions or len({row.currency for row in predictions}) != 1:
        raise ValueError("Summary requires nonempty single-currency predictions")
    keys = tuple(sorted({row.company_key for row in predictions}))
    scores = {}
    for horizon in HORIZONS:
        for baseline in BASELINES:
            for key in (*keys, "ALL"):
                group = [row for row in predictions if row.horizon_days == horizon
                         and row.baseline == baseline and (key == "ALL" or row.company_key == key)]
                mae, rmse = mae_rmse([row.actual for row in group], [row.predicted for row in group])
                scores[horizon, baseline, key] = len(group), mae, rmse
    result = []
    with localcontext() as context:
        context.prec = 80
        for (horizon, baseline, key), (n, mae, rmse) in scores.items():
            _, naive_mae, naive_rmse = scores[horizon, "naive", key]
            result.append({
                "company_key": key, "currency": predictions[0].currency,
                "horizon_days": horizon, "baseline": baseline, "n": n,
                "mae": metric_text(mae), "rmse": metric_text(rmse),
                "mae_delta_vs_naive": metric_text(mae - naive_mae),
                "rmse_delta_vs_naive": metric_text(rmse - naive_rmse),
                "mae_improvement_pct": metric_text(100 * (naive_mae - mae) / naive_mae) if naive_mae else None,
                "rmse_improvement_pct": metric_text(100 * (naive_rmse - rmse) / naive_rmse) if naive_rmse else None,
            })
    return result


def _csv(rows: list[dict]) -> str:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=tuple(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue()


def artifacts(dataset: Dataset, evaluation: Evaluation) -> dict[str, str]:
    metrics = summarize(evaluation.predictions)
    predictions = [{
        "is_synthetic": "true", "company_key": row.company_key, "currency": row.currency,
        "origin": row.origin.isoformat(), "horizon_days": row.horizon_days,
        "target_date": row.target_date.isoformat(), "baseline": row.baseline,
        "input_reference_date": row.input_reference_date.isoformat(),
        "predicted_closing": format(row.predicted, ".2f"), "actual_closing": format(row.actual, ".2f"),
    } for row in evaluation.predictions]
    origin_start, origin_end = evaluation.origins[0], evaluation.origins[-1]
    report = {
        "experiment_version": "CB1", "is_synthetic": True,
        "target": "daily_closing_cash_balance", "unit": evaluation.predictions[0].currency,
        "horizon_days": list(HORIZONS),
        "baselines": {
            "naive": "y_hat(t+h|t) = y(t)",
            "weekly_seasonal": "y_hat(t+h|t) = y(t+h-7*ceil(h/7)); repeat the last observed week",
        },
        "protocol": {
            "forecast_origin": "after closing on t; history contains only dates <= t",
            "rolling_observations": True, "fitting_or_scaling": "none",
            "common_origin_start": origin_start.isoformat(), "common_origin_end": origin_end.isoformat(),
            "common_origin_count_per_sme": len(evaluation.origins),
            "candidate_origin_count": len(evaluation.origins) + len(evaluation.excluded),
            "excluded_origins": [{**asdict(row), "origin": row.origin.isoformat()} for row in evaluation.excluded],
            "target_date_ranges": {
                str(h): {"start": (origin_start + timedelta(days=h)).isoformat(),
                         "end": (origin_end + timedelta(days=h)).isoformat()} for h in HORIZONS
            },
            "metric_aggregation": "pool all absolute/squared errors within each horizon/model; never average SME RMSE",
            "metric_serialization": "6 decimal places, ROUND_HALF_UP; Decimal precision 80 before serialization",
        },
        "splits": [{key: value.isoformat() if isinstance(value, date) else value
                    for key, value in asdict(split).items()} for split in evaluation.splits],
        "input": {"generator_version": "FS1", "seed": dataset.seed,
                  "start": dataset.start.isoformat(), "end": dataset.end.isoformat(),
                  "manifest_sha256": dataset.manifest_sha256, "csv_sha256": dict(dataset.file_hashes)},
        "runtime": {"python_version": platform.python_version(), "third_party_dependencies": []},
        "source_sha256": {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                          for name in ("baseline_data.py", "cash_flow_baseline.py")},
        "prediction_row_count": len(predictions), "metrics": metrics,
    }
    return {
        "predictions.csv": _csv(predictions), "metrics.csv": _csv(metrics),
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
    parser.add_argument("--data-dir", type=Path, default=Path("data/synthetic/FS1-20261018"))
    parser.add_argument("--output-dir", type=Path, default=Path("forecasting/results/fs1-closing-balance-baseline"))
    parser.add_argument("--train-end", type=date.fromisoformat, default=DEFAULT_TRAIN_END)
    parser.add_argument("--test-end", type=date.fromisoformat, default=DEFAULT_TEST_END)
    args = parser.parse_args(argv)
    try:
        dataset = load_dataset(args.data_dir)
        evaluation = evaluate(dataset.series, args.train_end, args.test_end)
        contents = artifacts(dataset, evaluation)
        write_artifacts(contents, args.output_dir, args.data_dir)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"Baseline experiment failed: {error}\n")
    print(f"SYNTHETIC ONLY: {len(dataset.series)} SMEs; {len(evaluation.origins)} common origins each; "
          f"{len(evaluation.predictions)} prediction rows; {len(evaluation.excluded)} excluded origins.")
    print(contents["metrics.csv"], end="")


if __name__ == "__main__":
    main()
