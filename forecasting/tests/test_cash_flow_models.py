import csv
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from forecasting.experiments.baseline_data import (
    MissingHistoryError,
    Observation,
    Series,
    load_dataset,
)
from forecasting.experiments.cash_flow_models import (
    HORIZONS,
    MIN_FIT_DAYS,
    MIN_RESIDUAL_COUNT,
    MODELS,
    ModelPrediction,
    artifacts,
    conformal_radius,
    evaluate,
    mae_rmse_me,
    min_required_history_days,
    predict_horizons,
    predict_with_intervals,
    summarize,
    write_artifacts,
)


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/synthetic/FS1-20261018"
BASELINE_METRICS = ROOT / "forecasting/results/fs1-closing-balance-baseline/metrics.csv"
START = date(2024, 1, 1)


def calendar_series(key: str = "A", length: int = 220, start_balance: Decimal = Decimal("5000.00")) -> Series:
    observations: list[Observation] = []
    balance = start_balance
    for idx in range(length):
        day = START + timedelta(days=idx)
        net = Decimal("120.00")
        if day.weekday() == 1:
            net += Decimal("250.00")
        elif day.weekday() == 4:
            net += Decimal("180.00")
        if day.day == 25:
            net -= Decimal("1800.00")
        elif day.day == 1:
            net -= Decimal("700.00")
        balance = balance + net
        observations.append(Observation(day, balance))
    return Series(key, "USD", tuple(observations))


class ModelLeakageAndBoundaryTests(unittest.TestCase):
    def test_changing_future_labels_leaves_all_predictions_and_intervals_unchanged(self):
        original = calendar_series(length=220)
        train_end = START + timedelta(days=149)
        test_end = START + timedelta(days=219)
        last_origin = test_end - timedelta(days=max(HORIZONS))

        perturbed = Series(
            "A",
            "USD",
            tuple(
                Observation(obs.day, obs.closing + Decimal("9500.00") if obs.day > last_origin else obs.closing)
                for obs in original.observations
            ),
        )

        before = evaluate((original,), train_end, test_end)
        after = evaluate((perturbed,), train_end, test_end)

        self.assertEqual(
            [
                (
                    r.origin,
                    r.model,
                    r.horizon_days,
                    r.predicted,
                    r.lower_80,
                    r.upper_80,
                    r.lower_95,
                    r.upper_95,
                    r.residual_count,
                )
                for r in before.predictions
            ],
            [
                (
                    r.origin,
                    r.model,
                    r.horizon_days,
                    r.predicted,
                    r.lower_80,
                    r.upper_80,
                    r.lower_95,
                    r.upper_95,
                    r.residual_count,
                )
                for r in after.predictions
            ],
        )
        self.assertNotEqual(
            summarize(before.predictions, before.train_scales),
            summarize(after.predictions, after.train_scales),
        )

    def test_later_observations_do_not_leak_into_earlier_predictions_or_intervals(self):
        original = calendar_series(length=220)
        train_end = START + timedelta(days=149)
        test_end = START + timedelta(days=219)
        cutoff = START + timedelta(days=165)

        perturbed = Series(
            "A",
            "USD",
            tuple(
                Observation(obs.day, obs.closing + Decimal("4000.00") if obs.day > cutoff else obs.closing)
                for obs in original.observations
            ),
        )

        before = evaluate((original,), train_end, test_end)
        after = evaluate((perturbed,), train_end, test_end)

        before_early = [
            (r.origin, r.model, r.horizon_days, r.predicted, r.lower_80, r.upper_80, r.lower_95, r.upper_95)
            for r in before.predictions
            if r.origin <= cutoff
        ]
        after_early = [
            (r.origin, r.model, r.horizon_days, r.predicted, r.lower_80, r.upper_80, r.lower_95, r.upper_95)
            for r in after.predictions
            if r.origin <= cutoff
        ]
        self.assertEqual(before_early, after_early)

        for model in ("drift_calendar", "ridge_lag_calendar"):
            before_late = [r.predicted for r in before.predictions if r.origin > cutoff and r.model == model]
            after_late = [r.predicted for r in after.predictions if r.origin > cutoff and r.model == model]
            self.assertNotEqual(before_late, after_late)

    def test_insufficient_history_and_invalid_inputs_raise(self):
        short_series = calendar_series(length=50)
        short_history = short_series.history(short_series.observations[-1].day)
        for model in ("drift_calendar", "ridge_lag_calendar"):
            with self.subTest(model=model), self.assertRaises(MissingHistoryError):
                predict_horizons(short_history, HORIZONS, model)

        almost_enough = calendar_series(length=min_required_history_days("drift_calendar", 30) - 1)
        with self.assertRaises(MissingHistoryError):
            predict_with_intervals(almost_enough.history(almost_enough.observations[-1].day), HORIZONS, "drift_calendar")

        valid_history = calendar_series(length=120).history(START + timedelta(days=119))
        for bad_horizon in (0, -5, 1.5, True):
            with self.subTest(horizon=bad_horizon), self.assertRaises(ValueError):
                predict_horizons(valid_history, (bad_horizon,), "naive")
        with self.assertRaises(ValueError):
            predict_horizons(valid_history, (), "naive")
        with self.assertRaises(ValueError):
            predict_horizons(valid_history, (7,), "unknown_model")
        with self.assertRaises(MissingHistoryError):
            conformal_radius([Decimal("1.00")] * (MIN_RESIDUAL_COUNT - 1), Decimal("0.80"))
        with self.assertRaises(ValueError):
            conformal_radius([Decimal("1.00")] * MIN_RESIDUAL_COUNT, Decimal("1.00"))

    def test_negative_balances_predicted_without_clamping(self):
        observations = tuple(
            Observation(START + timedelta(days=idx), Decimal(-1000 - 25 * idx))
            for idx in range(130)
        )
        history = Series("NEG", "USD", observations).history(observations[-1].day)
        for model in MODELS:
            with self.subTest(model=model):
                forecasts = predict_with_intervals(history, HORIZONS, model)
                for h in HORIZONS:
                    fc = forecasts[h]
                    self.assertLess(fc.predicted, Decimal("0.00"))
                    self.assertLessEqual(fc.lower_95, fc.lower_80)
                    self.assertLessEqual(fc.lower_80, fc.predicted)
                    self.assertLessEqual(fc.predicted, fc.upper_80)
                    self.assertLessEqual(fc.upper_80, fc.upper_95)


class ModelAccuracyAndMetricTests(unittest.TestCase):
    def test_linear_plus_calendar_fixture_beats_naive(self):
        series = calendar_series(length=260)
        result = evaluate((series,), START + timedelta(days=179), START + timedelta(days=259))
        rows = summarize(result.predictions, result.train_scales)
        by_key = {(r["horizon_days"], r["model"]): r for r in rows if r["company_key"] == "ALL"}
        for h in HORIZONS:
            naive_mae = Decimal(by_key[(h, "naive")]["mae"])
            for model in ("drift_calendar", "ridge_lag_calendar"):
                model_mae = Decimal(by_key[(h, model)]["mae"])
                self.assertLess(model_mae, naive_mae)
                self.assertGreater(Decimal(by_key[(h, model)]["mae_improvement_pct"]), Decimal("0"))

    def test_me_mase_coverage_and_pooled_rmse_formulas(self):
        mae, rmse, me = mae_rmse_me(
            [Decimal("10.00"), Decimal("10.00")],
            [Decimal("7.00"), Decimal("15.00")],
        )
        self.assertEqual(Decimal("4"), mae)
        self.assertEqual(Decimal("1"), me)
        self.assertAlmostEqual(4.123106, float(rmse), places=6)

        rows = [
            ModelPrediction(
                company_key=key,
                currency="USD",
                origin=START,
                horizon_days=h,
                target_date=START + timedelta(days=h),
                model=m,
                predicted=Decimal(pred),
                actual=Decimal("0.00"),
                lower_80=Decimal(pred) - Decimal("3.00"),
                upper_80=Decimal(pred) + Decimal("3.00"),
                lower_95=Decimal(pred) - Decimal("4.00"),
                upper_95=Decimal(pred) + Decimal("4.00"),
                residual_count=20,
            )
            for key, pred in (("A", "3.00"), ("B", "4.00"))
            for h in HORIZONS
            for m in MODELS
        ]
        summary = summarize(rows, {"A": Decimal("1.50"), "B": Decimal("2.00")})
        overall = next(r for r in summary if r["company_key"] == "ALL" and r["model"] == "drift_calendar" and r["horizon_days"] == 7)
        self.assertEqual(2, overall["n"])
        self.assertEqual("3.500000", overall["mae"])
        self.assertEqual("3.535534", overall["rmse"])
        self.assertEqual("3.500000", overall["me"])
        self.assertEqual("2.000000", overall["mase"])
        self.assertEqual("0.500000", overall["coverage_80"])
        self.assertEqual("1.000000", overall["coverage_95"])

        constant_summary = summarize(rows, {"A": Decimal("0.00"), "B": Decimal("2.00")})
        overall_const = next(r for r in constant_summary if r["company_key"] == "ALL")
        self.assertIsNone(overall_const["mase"])


class FS1AlignmentAndReproducibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = load_dataset(DATA)
        cls.evaluation = evaluate(cls.dataset.series, date(2024, 12, 31), date(2025, 12, 31))

    def test_fs1_336_origins_counts_and_exact_naive_alignment(self):
        self.assertEqual(336, len(self.evaluation.origins))
        self.assertEqual(date(2024, 12, 31), self.evaluation.origins[0])
        self.assertEqual(date(2025, 12, 1), self.evaluation.origins[-1])
        self.assertFalse(self.evaluation.excluded)
        self.assertEqual(6048, len(self.evaluation.predictions))

        for row in self.evaluation.predictions:
            self.assertGreaterEqual(row.residual_count, MIN_RESIDUAL_COUNT)
            self.assertLessEqual(row.lower_95, row.lower_80)
            self.assertLessEqual(row.lower_80, row.predicted)
            self.assertLessEqual(row.predicted, row.upper_80)
            self.assertLessEqual(row.upper_80, row.upper_95)

        rows = summarize(self.evaluation.predictions, self.evaluation.train_scales)
        self.assertEqual(24, len(rows))

        with BASELINE_METRICS.open(encoding="utf-8", newline="") as handle:
            baseline_naive = {
                (int(r["horizon_days"]), r["company_key"]): (r["mae"], r["rmse"])
                for r in csv.DictReader(handle)
                if r["baseline"] == "naive"
            }
        model_naive = {
            (r["horizon_days"], r["company_key"]): (r["mae"], r["rmse"])
            for r in rows
            if r["model"] == "naive"
        }
        self.assertEqual(baseline_naive, model_naive)

    def test_evaluate_matches_direct_predict_with_intervals_per_origin(self):
        sample_origins = (date(2024, 12, 31), date(2025, 6, 15), date(2025, 12, 1))
        series_by_key = {item.company_key: item for item in self.dataset.series}
        indexed = {
            (r.company_key, r.origin, r.horizon_days, r.model): r
            for r in self.evaluation.predictions
            if r.origin in sample_origins
        }
        for key, item in series_by_key.items():
            for origin in sample_origins:
                history = item.history(origin)
                for model in MODELS:
                    direct = predict_with_intervals(history, HORIZONS, model)
                    for h in HORIZONS:
                        eval_row = indexed[(key, origin, h, model)]
                        fc = direct[h]
                        self.assertEqual(fc.predicted, eval_row.predicted)
                        self.assertEqual(fc.lower_80, eval_row.lower_80)
                        self.assertEqual(fc.upper_80, eval_row.upper_80)
                        self.assertEqual(fc.lower_95, eval_row.lower_95)
                        self.assertEqual(fc.upper_95, eval_row.upper_95)
                        self.assertEqual(fc.residual_count, eval_row.residual_count)

    def test_reproducibility_and_overwrite_protection(self):
        second = evaluate(self.dataset.series, date(2024, 12, 31), date(2025, 12, 31))
        self.assertEqual(self.evaluation, second)
        first_contents = artifacts(self.dataset, self.evaluation)
        second_contents = artifacts(self.dataset, second)
        self.assertEqual(first_contents, second_contents)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifacts(first_contents, root / "one", DATA)
            write_artifacts(first_contents, root / "one", DATA)
            with self.assertRaises(ValueError):
                write_artifacts({"metrics.csv": "tampered"}, root / "one", DATA)
            with self.assertRaises(ValueError):
                write_artifacts({"metrics.csv": "anything"}, DATA / "bad-output", DATA)


if __name__ == "__main__":
    unittest.main()
