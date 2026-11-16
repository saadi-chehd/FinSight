import csv
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from forecasting.experiments.baseline_data import load_dataset, money


DATA = Path(__file__).resolve().parents[2] / "data/synthetic/FS1-20261018"


class InputContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "dataset"
        shutil.copytree(DATA, self.directory)

    def rewrite(self, filename, change):
        path = self.directory / filename
        with path.open(newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            columns, rows = list(reader.fieldnames), list(reader)
        change(columns, rows)
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        manifest_path = self.directory / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["sha256"][filename] = hashlib.sha256(path.read_bytes()).hexdigest()
        if filename == "daily_cash_flow.csv":
            manifest["daily_row_count"] = len(rows)
        manifest_path.write_text(json.dumps(manifest))

    def test_actual_fs1_contract_and_money_are_retained(self):
        dataset = load_dataset(self.directory)
        self.assertEqual(20261018, dataset.seed)
        self.assertEqual(3, len(dataset.series))
        self.assertTrue(all(len(series.observations) == 731 for series in dataset.series))
        self.assertEqual(Decimal("4140.34"), dataset.series[0].observations[0].closing)
        self.assertEqual(Decimal("96934.82"), dataset.series[0].observations[-1].closing)

    def test_changed_input_checksum_is_rejected_before_evaluation(self):
        with (self.directory / "transactions.csv").open("a") as handle:
            handle.write("\n")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            load_dataset(self.directory)

    def test_missing_calendar_day_is_not_filled_or_backfilled(self):
        self.rewrite("daily_cash_flow.csv", lambda columns, rows: rows.pop(10))
        with self.assertRaisesRegex(ValueError, "ordered and complete"):
            load_dataset(self.directory)

    def test_shuffled_daily_rows_are_rejected_not_silently_sorted(self):
        def shuffle(columns, rows):
            rows[0], rows[1] = rows[1], rows[0]
        self.rewrite("daily_cash_flow.csv", shuffle)
        with self.assertRaisesRegex(ValueError, "ordered and complete"):
            load_dataset(self.directory)

    def test_financial_roll_forward_is_checked_independently_of_hashes(self):
        self.rewrite("daily_cash_flow.csv", lambda columns, rows: rows[0].update(closing_balance="9999.99"))
        with self.assertRaisesRegex(ValueError, "roll-forward"):
            load_dataset(self.directory)

    def test_non_synthetic_labels_and_silent_column_renames_are_rejected(self):
        self.rewrite("daily_cash_flow.csv", lambda columns, rows: rows[0].update(is_synthetic="false"))
        with self.assertRaisesRegex(ValueError, "explicitly synthetic"):
            load_dataset(self.directory)
        with (self.directory / "daily_cash_flow.csv").open() as handle:
            text = handle.read()
        path = self.directory / "daily_cash_flow.csv"
        path.write_text(text.replace("closing_balance", "renamed_target", 1))
        manifest_path = self.directory / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["sha256"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "columns differ"):
            load_dataset(self.directory)

    def test_manifest_cannot_silently_change_generator_currency_or_file_set(self):
        manifest_path = self.directory / "manifest.json"
        original = json.loads(manifest_path.read_text())
        for key, value in (("generator_version", "FS2"), ("currency", "EUR"), ("is_synthetic", False)):
            with self.subTest(key=key):
                changed = {**original, key: value}
                manifest_path.write_text(json.dumps(changed))
                with self.assertRaises(ValueError):
                    load_dataset(self.directory)
        changed = {**original, "sha256": {**original["sha256"], "../other.csv": "not-a-hash"}}
        manifest_path.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, "five canonical"):
            load_dataset(self.directory)

    def test_opening_input_cannot_be_replaced_with_an_assumed_zero(self):
        self.rewrite("opening_balances.csv", lambda columns, rows: rows[0].update(opening_balance="0.00"))
        with self.assertRaisesRegex(ValueError, "roll-forward"):
            load_dataset(self.directory)

    def test_nonfinite_fractional_or_out_of_range_money_is_rejected(self):
        for value in ("NaN", "Infinity", "-Infinity", "0.001", "100000000000000000.00"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                money(value)
        self.assertEqual(Decimal("-1.20"), money("-1.20"))


if __name__ == "__main__":
    unittest.main()
