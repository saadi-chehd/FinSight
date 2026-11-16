from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from forecasting.experiments.baseline_data import (
    FutureDataError, History, MissingHistoryError, Observation, Series, load_dataset,
)
from forecasting.experiments.cash_flow_baseline import (
    BASELINES, HORIZONS, Prediction, artifacts, chronological_split, evaluate,
    mae_rmse, predict, summarize, write_artifacts,
)


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/synthetic/FS1-20261018"
START = date(2024, 1, 1)


def linear_series(key="A", length=100, offset=0):
    return Series(key, "USD", tuple(
        Observation(START + timedelta(days=index), Decimal(index + offset))
        for index in range(length)
    ))


class BaselineTests(unittest.TestCase):
    def test_chronological_split_has_no_overlap(self):
        split = chronological_split(linear_series(), START + timedelta(days=39), START + timedelta(days=99))
        self.assertEqual((40, 60), (split.train_rows, split.test_rows))
        self.assertEqual(split.train_end + timedelta(days=1), split.test_start)
        self.assertLess(split.train_end, split.test_start)

    def test_split_rejects_empty_reversed_and_out_of_range_periods(self):
        for cutoff, end in ((-1, 99), (99, 99), (50, 49), (39, 100)):
            with self.subTest(cutoff=cutoff, end=end), self.assertRaises(ValueError):
                chronological_split(linear_series(), START + timedelta(days=cutoff), START + timedelta(days=end))

    def test_exact_common_origins_and_endpoint_alignment(self):
        result = evaluate((linear_series(),), START + timedelta(days=39), START + timedelta(days=99))
        self.assertEqual(31, len(result.origins))
        self.assertEqual(START + timedelta(days=39), result.origins[0])
        self.assertEqual(START + timedelta(days=69), result.origins[-1])
        self.assertEqual(124, len(result.predictions))
        self.assertFalse(result.excluded)
        for row in result.predictions:
            self.assertEqual(row.origin + timedelta(days=row.horizon_days), row.target_date)
            self.assertEqual(Decimal((row.target_date - START).days), row.actual)
            self.assertLessEqual(row.input_reference_date, row.origin)
            self.assertGreater(row.target_date, result.splits[0].train_end)

    def test_weekly_rule_is_recursive_and_seven_day_prediction_equals_naive(self):
        history = linear_series().history(START + timedelta(days=39))
        self.assertEqual(predict(history, 7, "naive"), predict(history, 7, "weekly_seasonal"))
        weekly = predict(history, 30, "weekly_seasonal")
        self.assertEqual(history.origin - timedelta(days=5), weekly.reference_date)
        self.assertEqual(Decimal("34.00"), weekly.value)
        for horizon in range(1, 36):
            self.assertLessEqual(predict(history, horizon, "weekly_seasonal").reference_date, history.origin)

    def test_future_actuals_are_inaccessible_to_models(self):
        origin = START + timedelta(days=39)
        history = linear_series().history(origin)
        self.assertEqual(40, len(history.observations))
        with self.assertRaises(FutureDataError):
            history.value(origin + timedelta(days=1))
        with self.assertRaises(FutureDataError):
            History(origin, linear_series().observations[:41])

    def test_changing_future_labels_changes_scores_but_not_any_input_prediction(self):
        original = linear_series()
        changed = Series("A", "USD", tuple(
            Observation(row.day, row.closing + Decimal("5000.00") if index > 69 else row.closing)
            for index, row in enumerate(original.observations)
        ))
        before = evaluate((original,), START + timedelta(days=39), START + timedelta(days=99))
        after = evaluate((changed,), START + timedelta(days=39), START + timedelta(days=99))
        self.assertEqual(
            [(row.origin, row.baseline, row.horizon_days, row.predicted) for row in before.predictions],
            [(row.origin, row.baseline, row.horizon_days, row.predicted) for row in after.predictions],
        )
        self.assertNotEqual(summarize(before.predictions), summarize(after.predictions))

    def test_later_test_observations_only_change_forecasts_after_they_become_known(self):
        original = linear_series()
        cutoff = START + timedelta(days=49)
        changed = Series("A", "USD", tuple(
            Observation(row.day, row.closing if row.day <= cutoff else row.closing + Decimal("1000.00"))
            for row in original.observations
        ))
        before = evaluate((original,), START + timedelta(days=39), START + timedelta(days=99))
        after = evaluate((changed,), START + timedelta(days=39), START + timedelta(days=99))
        self.assertEqual(
            [(row.predicted, row.input_reference_date) for row in before.predictions if row.origin <= cutoff],
            [(row.predicted, row.input_reference_date) for row in after.predictions if row.origin <= cutoff],
        )
        self.assertNotEqual(
            [row.predicted for row in before.predictions if row.origin > cutoff],
            [row.predicted for row in after.predictions if row.origin > cutoff],
        )

    def test_missing_week_is_not_replaced_with_naive(self):
        history = linear_series().history(START + timedelta(days=5))
        self.assertEqual(Decimal("5.00"), predict(history, 30, "naive").value)
        for horizon in HORIZONS:
            with self.subTest(horizon=horizon), self.assertRaises(MissingHistoryError):
                predict(history, horizon, "weekly_seasonal")
        result = evaluate((linear_series(),), START + timedelta(days=5), START + timedelta(days=99))
        self.assertEqual((START + timedelta(days=5),), tuple(row.origin for row in result.excluded))
        self.assertEqual(START + timedelta(days=6), result.origins[0])
        self.assertFalse(any(row.origin == START + timedelta(days=5) for row in result.predictions))

    def test_same_origins_for_both_models_both_horizons_and_all_smes(self):
        result = evaluate((linear_series("B", offset=50), linear_series()),
                          START + timedelta(days=39), START + timedelta(days=99))
        for key in ("A", "B"):
            for horizon in HORIZONS:
                for baseline in BASELINES:
                    origins = tuple(row.origin for row in result.predictions
                                    if (row.company_key, row.horizon_days, row.baseline) == (key, horizon, baseline))
                    self.assertEqual(result.origins, origins)

    def test_invalid_model_horizon_series_and_missing_origin_fail(self):
        history = linear_series().history(START + timedelta(days=10))
        for horizon in (0, -1, 1.5, True):
            with self.subTest(horizon=horizon), self.assertRaises(ValueError):
                predict(history, horizon, "naive")
        with self.assertRaises(ValueError):
            predict(history, 7, "unknown")
        with self.assertRaises(MissingHistoryError):
            linear_series().history(START - timedelta(days=1))
        with self.assertRaises(ValueError):
            Series("A", "USD", (Observation(START, Decimal(1)), Observation(START + timedelta(days=2), Decimal(2))))
        with self.assertRaises(ValueError):
            evaluate((), START, START + timedelta(days=90))
        with self.assertRaises(ValueError):
            evaluate((linear_series(), linear_series()), START + timedelta(days=39), START + timedelta(days=99))
        with self.assertRaises(ValueError):
            evaluate((linear_series(),), START + timedelta(days=39), START + timedelta(days=50))

    def test_negative_balances_are_predicted_as_recorded(self):
        history = Series("A", "USD", tuple(Observation(START + timedelta(days=index), Decimal(-index))
                                             for index in range(10))).history(START + timedelta(days=9))
        self.assertEqual(Decimal("-9.00"), predict(history, 7, "naive").value)
        self.assertEqual(Decimal("-4.00"), predict(history, 30, "weekly_seasonal").value)


class MetricTests(unittest.TestCase):
    def test_known_signed_errors_and_rmse_formula(self):
        mae, rmse = mae_rmse([Decimal(10), Decimal(10)], [Decimal(7), Decimal(14)])
        self.assertEqual(Decimal("3.5"), mae)
        self.assertEqual(Decimal("3.535534"), rmse.quantize(Decimal("0.000001")))

    def test_perfect_and_invalid_metrics(self):
        self.assertEqual((Decimal(0), Decimal(0)), mae_rmse([Decimal(1)], [Decimal(1)]))
        for actuals, predicted in (([], []), ([Decimal(1)], []), ([Decimal("NaN")], [Decimal(1)]), ([1.0], [Decimal(1)])):
            with self.subTest(actuals=actuals), self.assertRaises(ValueError):
                mae_rmse(actuals, predicted)

    def test_linear_fixture_has_known_horizon_errors_and_seasonal_comparison(self):
        result = evaluate((linear_series(),), START + timedelta(days=39), START + timedelta(days=99))
        rows = summarize(result.predictions)
        for row in rows:
            expected = 7 if row["horizon_days"] == 7 else 30 if row["baseline"] == "naive" else 35
            self.assertEqual(f"{expected}.000000", row["mae"])
            self.assertEqual(f"{expected}.000000", row["rmse"])
        seasonal = next(row for row in rows if row["baseline"] == "weekly_seasonal" and row["horizon_days"] == 30)
        self.assertEqual("5.000000", seasonal["mae_delta_vs_naive"])
        self.assertEqual("-16.666667", seasonal["mae_improvement_pct"])

    def test_overall_rmse_pools_squared_errors_not_sme_rmse(self):
        rows = [Prediction(key, "USD", START, horizon, START + timedelta(days=horizon), baseline,
                           START, Decimal(error), Decimal(0))
                for key, error in (("A", 3), ("B", 4)) for horizon in HORIZONS for baseline in BASELINES]
        overall = next(row for row in summarize(rows) if row["company_key"] == "ALL")
        self.assertEqual(2, overall["n"])
        self.assertEqual("3.500000", overall["mae"])
        self.assertEqual("3.535534", overall["rmse"])

    def test_zero_naive_error_has_no_fabricated_percentage_improvement(self):
        constant = Series("A", "USD", tuple(Observation(START + timedelta(days=index), Decimal(10)) for index in range(100)))
        result = evaluate((constant,), START + timedelta(days=39), START + timedelta(days=99))
        self.assertTrue(all(row["mae_improvement_pct"] is None for row in summarize(result.predictions)))


class ReproducibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = load_dataset(DATA)

    def test_real_export_split_origins_and_counts(self):
        result = evaluate(self.dataset.series, date(2024, 12, 31), date(2025, 12, 31))
        self.assertEqual(336, len(result.origins))
        self.assertEqual(4032, len(result.predictions))
        self.assertFalse(result.excluded)
        self.assertEqual(date(2024, 12, 31), result.origins[0])
        self.assertEqual(date(2025, 12, 1), result.origins[-1])
        for split in result.splits:
            self.assertEqual((366, 365), (split.train_rows, split.test_rows))
        rows = summarize(result.predictions)
        self.assertEqual(16, len(rows))
        for row in rows:
            self.assertEqual(1008 if row["company_key"] == "ALL" else 336, row["n"])

    def test_evaluation_and_output_bytes_repeat_exactly(self):
        first = evaluate(self.dataset.series, date(2024, 12, 31), date(2025, 12, 31))
        second = evaluate(self.dataset.series, date(2024, 12, 31), date(2025, 12, 31))
        self.assertEqual(first, second)
        contents = artifacts(self.dataset, first)
        self.assertEqual(contents, artifacts(self.dataset, second))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifacts(contents, root / "one", DATA)
            write_artifacts(contents, root / "two", DATA)
            write_artifacts(contents, root / "one", DATA)
            for name in contents:
                self.assertEqual((root / "one" / name).read_bytes(), (root / "two" / name).read_bytes())

    def test_outputs_cannot_overwrite_changed_results_or_synthetic_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            write_artifacts({"result.txt": "original"}, output, DATA)
            with self.assertRaises(ValueError):
                write_artifacts({"result.txt": "changed"}, output, DATA)
            self.assertEqual("original", (output / "result.txt").read_text())
            with self.assertRaises(ValueError):
                write_artifacts({"result.txt": "anything"}, DATA / "results", DATA)


if __name__ == "__main__":
    unittest.main()
