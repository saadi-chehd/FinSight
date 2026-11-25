import csv
from datetime import date, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from forecasting.experiments.baseline_data import (
    MissingHistoryError,
    Observation,
    Series,
    load_dataset,
)
from forecasting.experiments.cash_flow_ml import (
    ALL_MODELS,
    CM1_MODELS,
    HORIZONS,
    ML_MODELS,
    InvoiceLedger,
    InvoicePayment,
    InvoiceRecord,
    OpenInvoice,
    artifacts_ml,
    evaluate_ml,
    load_invoice_ledgers,
    min_required_history_days_all,
    predict_horizons_ml,
    predict_with_intervals_ml,
    summarize_ml,
    write_artifacts,
)
from forecasting.experiments.cash_flow_models import MIN_RESIDUAL_COUNT


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/synthetic/FS1-20261018"
BASELINE_METRICS = ROOT / "forecasting/results/fs1-closing-balance-baseline/metrics.csv"
CM1_METRICS = ROOT / "forecasting/results/fs1-closing-balance-models-v1/metrics.csv"
START = date(2024, 1, 1)


def calendar_series(key: str = "A", length: int = 240, start_balance: Decimal = Decimal("5000.00")) -> Series:
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


def build_synthetic_ledger(key: str = "A", length: int = 240) -> InvoiceLedger:
    invoices: list[InvoiceRecord] = []
    payments: list[InvoicePayment] = []
    inv_idx = 1
    tx_idx = 1
    for offset in range(0, length, 5):
        issue = START + timedelta(days=offset)
        due = issue + timedelta(days=14)
        direction = "INFLOW" if inv_idx % 2 == 1 else "OUTFLOW"
        amount = Decimal("600.00") if direction == "INFLOW" else Decimal("350.00")
        inv_key = f"{key}-I{inv_idx:04d}"
        invoices.append(
            InvoiceRecord(
                invoice_key=inv_key,
                company_key=key,
                currency="USD",
                direction=direction,
                amount=amount,
                issue_date=issue,
                due_date=due,
            )
        )
        # Settle most invoices around due date + 2 days so open & resolved invoices coexist
        settle_day = due + timedelta(days=2)
        if settle_day < START + timedelta(days=length):
            payments.append(
                InvoicePayment(
                    transaction_key=f"{key}-T{tx_idx:04d}",
                    company_key=key,
                    currency="USD",
                    invoice_key=inv_key,
                    booked_date=settle_day,
                    direction=direction,
                    amount=amount,
                )
            )
            tx_idx += 1
        inv_idx += 1
    return InvoiceLedger(key, "USD", tuple(invoices), tuple(payments))


def _prediction_signature(rows) -> list[tuple]:
    return [
        (
            r.company_key,
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
        for r in rows
    ]


class CashFlowMLLeakageTests(unittest.TestCase):
    def test_mutating_future_closing_balances_leaves_earlier_predictions_and_intervals_unchanged(self):
        original = calendar_series(length=240)
        ledger = build_synthetic_ledger(length=240)
        train_end = START + timedelta(days=159)
        test_end = START + timedelta(days=239)
        cutoff = START + timedelta(days=180)
        last_origin = test_end - timedelta(days=max(HORIZONS))

        perturbed_after_cutoff = Series(
            "A",
            "USD",
            tuple(
                Observation(obs.day, obs.closing + Decimal("8500.00") if obs.day > cutoff else obs.closing)
                for obs in original.observations
            ),
        )
        perturbed_after_last_origin = Series(
            "A",
            "USD",
            tuple(
                Observation(obs.day, obs.closing + Decimal("9500.00") if obs.day > last_origin else obs.closing)
                for obs in original.observations
            ),
        )

        before = evaluate_ml((original,), {"A": ledger}, train_end, test_end)
        after_cutoff = evaluate_ml((perturbed_after_cutoff,), {"A": ledger}, train_end, test_end)
        after_last_origin = evaluate_ml((perturbed_after_last_origin,), {"A": ledger}, train_end, test_end)

        self.assertEqual(
            _prediction_signature(before.predictions),
            _prediction_signature(after_last_origin.predictions),
        )

        before_early = _prediction_signature(r for r in before.predictions if r.origin <= cutoff)
        after_early = _prediction_signature(r for r in after_cutoff.predictions if r.origin <= cutoff)
        self.assertTrue(before_early)
        self.assertEqual(before_early, after_early)

        # Verify ML models actually respond to observations after cutoff on origins > cutoff
        for model in ML_MODELS:
            before_late = [r.predicted for r in before.predictions if r.origin > cutoff and r.model == model]
            after_late = [r.predicted for r in after_cutoff.predictions if r.origin > cutoff and r.model == model]
            self.assertNotEqual(before_late, after_late)

    def test_inserting_and_mutating_future_invoices_leaves_earlier_predictions_and_intervals_unchanged(self):
        series = calendar_series(length=240)
        base_ledger = build_synthetic_ledger(length=240)
        train_end = START + timedelta(days=159)
        test_end = START + timedelta(days=239)
        cutoff = START + timedelta(days=180)

        mutated_invoices = []
        for inv in base_ledger.invoices:
            if inv.issue_date > cutoff:
                mutated_invoices.append(
                    InvoiceRecord(
                        invoice_key=inv.invoice_key,
                        company_key=inv.company_key,
                        currency=inv.currency,
                        direction="OUTFLOW" if inv.direction == "INFLOW" else "INFLOW",
                        amount=inv.amount + Decimal("50000.00"),
                        issue_date=inv.issue_date,
                        due_date=inv.due_date + timedelta(days=5),
                    )
                )
            else:
                mutated_invoices.append(inv)

        mutated_invoices.append(
            InvoiceRecord(
                invoice_key="A-FUTURE-EXTRA",
                company_key="A",
                currency="USD",
                direction="INFLOW",
                amount=Decimal("250000.00"),
                issue_date=cutoff + timedelta(days=1),
                due_date=cutoff + timedelta(days=4),
            )
        )
        perturbed_ledger = InvoiceLedger("A", "USD", tuple(mutated_invoices), base_ledger.payments)

        self.assertEqual(base_ledger.open_invoices_as_of(cutoff), perturbed_ledger.open_invoices_as_of(cutoff))
        self.assertEqual(
            base_ledger.empirical_settlement_stats(cutoff),
            perturbed_ledger.empirical_settlement_stats(cutoff),
        )

        before = evaluate_ml((series,), {"A": base_ledger}, train_end, test_end)
        after = evaluate_ml((series,), {"A": perturbed_ledger}, train_end, test_end)

        before_early = _prediction_signature(r for r in before.predictions if r.origin <= cutoff)
        after_early = _prediction_signature(r for r in after.predictions if r.origin <= cutoff)
        self.assertTrue(before_early)
        self.assertEqual(before_early, after_early)

        # Confirm later origins see the altered future invoices
        for model in ("invoice_aware_ml", "ensemble_ml"):
            before_late = [r.predicted for r in before.predictions if r.origin > cutoff and r.model == model]
            after_late = [r.predicted for r in after.predictions if r.origin > cutoff and r.model == model]
            self.assertNotEqual(before_late, after_late)

    def test_inserting_and_mutating_future_payments_leaves_earlier_predictions_and_intervals_unchanged(self):
        series = calendar_series(length=240)
        base_ledger = build_synthetic_ledger(length=240)
        train_end = START + timedelta(days=159)
        test_end = START + timedelta(days=239)
        cutoff = START + timedelta(days=180)

        mutated_payments = []
        for tx in base_ledger.payments:
            if tx.booked_date > cutoff:
                mutated_payments.append(
                    InvoicePayment(
                        transaction_key=tx.transaction_key,
                        company_key=tx.company_key,
                        currency=tx.currency,
                        invoice_key=tx.invoice_key,
                        booked_date=tx.booked_date,
                        direction=tx.direction,
                        amount=Decimal("0.01"),
                    )
                )
            else:
                mutated_payments.append(tx)

        # Add a huge payment right after cutoff for an invoice issued before cutoff
        pre_cutoff_invoice = next(inv for inv in base_ledger.invoices if inv.issue_date <= cutoff)
        mutated_payments.append(
            InvoicePayment(
                transaction_key="A-FUTURE-PAY",
                company_key="A",
                currency="USD",
                invoice_key=pre_cutoff_invoice.invoice_key,
                booked_date=cutoff + timedelta(days=1),
                direction=pre_cutoff_invoice.direction,
                amount=Decimal("99999.00"),
            )
        )
        perturbed_ledger = InvoiceLedger("A", "USD", base_ledger.invoices, tuple(mutated_payments))

        self.assertEqual(base_ledger.open_invoices_as_of(cutoff), perturbed_ledger.open_invoices_as_of(cutoff))
        self.assertEqual(
            base_ledger.empirical_settlement_stats(cutoff),
            perturbed_ledger.empirical_settlement_stats(cutoff),
        )

        before = evaluate_ml((series,), {"A": base_ledger}, train_end, test_end)
        after = evaluate_ml((series,), {"A": perturbed_ledger}, train_end, test_end)

        before_early = _prediction_signature(r for r in before.predictions if r.origin <= cutoff)
        after_early = _prediction_signature(r for r in after.predictions if r.origin <= cutoff)
        self.assertTrue(before_early)
        self.assertEqual(before_early, after_early)

    def test_scrambling_payment_status_at_cutoff_leaves_predictions_and_intervals_identical(self):
        original_dataset = load_dataset(DATA)
        original_ledgers = load_invoice_ledgers(DATA)

        with tempfile.TemporaryDirectory() as tmpdir:
            copy_dir = Path(tmpdir) / "FS1-scrambled"
            shutil.copytree(DATA, copy_dir)

            invoices_path = copy_dir / "invoices.csv"
            with invoices_path.open(encoding="utf-8", newline="") as handle:
                reader = csv.DictReader(handle)
                fieldnames = list(reader.fieldnames or [])
                rows = list(reader)

            statuses = ["PAID", "OPEN", "OVERDUE", "PARTIALLY_PAID", "CANCELLED"]
            for idx, row in enumerate(rows):
                row["payment_status_at_cutoff"] = statuses[idx % len(statuses)]

            with invoices_path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n", quoting=csv.QUOTE_ALL)
                writer.writeheader()
                writer.writerows(rows)

            manifest_path = copy_dir / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["sha256"]["invoices.csv"] = hashlib.sha256(invoices_path.read_bytes()).hexdigest()
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

            scrambled_dataset = load_dataset(copy_dir)
            scrambled_ledgers = load_invoice_ledgers(copy_dir)

            self.assertEqual(original_ledgers, scrambled_ledgers)

            sample_origins = (date(2024, 12, 31), date(2025, 6, 15), date(2025, 12, 1))
            for orig_series, scr_series in zip(original_dataset.series, scrambled_dataset.series):
                key = orig_series.company_key
                for origin in sample_origins:
                    orig_hist = orig_series.history(origin)
                    scr_hist = scr_series.history(origin)
                    for model in ALL_MODELS:
                        with self.subTest(sme=key, origin=origin, model=model):
                            b1 = predict_with_intervals_ml(
                                orig_hist, HORIZONS, model, invoice_ledger=original_ledgers[key]
                            )
                            b2 = predict_with_intervals_ml(
                                scr_hist, HORIZONS, model, invoice_ledger=scrambled_ledgers[key]
                            )
                            self.assertEqual(b1.forecasts, b2.forecasts)
                            self.assertEqual(b1.ensemble_weights, b2.ensemble_weights)
                            self.assertEqual(b1.open_invoice_count, b2.open_invoice_count)


class CashFlowMLContractAndValidationTests(unittest.TestCase):
    def test_min_required_history_days_all_and_error_cases(self):
        self.assertEqual(50, min_required_history_days_all("naive", 30))
        self.assertEqual(109, min_required_history_days_all("drift_calendar", 30))
        self.assertEqual(109, min_required_history_days_all("ridge_lag_calendar", 30))
        for model in ML_MODELS:
            self.assertEqual(139, min_required_history_days_all(model, 30))

        short_history = calendar_series(length=138).history(START + timedelta(days=137))
        for model in ML_MODELS:
            with self.subTest(model=model), self.assertRaises(MissingHistoryError):
                predict_with_intervals_ml(
                    short_history,
                    HORIZONS,
                    model,
                    open_invoices=() if model == "invoice_aware_ml" else None,
                )

        sufficient = calendar_series(length=150).history(START + timedelta(days=149))
        with self.assertRaises(ValueError):
            predict_with_intervals_ml(sufficient, HORIZONS, "invoice_aware_ml")
        with self.assertRaises(ValueError):
            predict_horizons_ml(sufficient, HORIZONS, "invoice_aware_ml")

        # ensemble_ml without open_invoices or ledger falls back gracefully
        fallback_bundle = predict_with_intervals_ml(sufficient, HORIZONS, "ensemble_ml")
        self.assertFalse(fallback_bundle.uses_open_invoices)
        self.assertIsNone(fallback_bundle.open_invoice_count)
        self.assertEqual(("drift_calendar", "gbdt_direct"), fallback_bundle.active_members)
        self.assertIsNotNone(fallback_bundle.ensemble_weights)
        for h in HORIZONS:
            weights = fallback_bundle.ensemble_weights[h]
            self.assertEqual({"drift_calendar", "gbdt_direct"}, set(weights))
            self.assertAlmostEqual(1.0, sum(weights.values()), places=5)

        # ensemble_ml with explicit open_invoices includes invoice_aware_ml
        open_invs = (
            OpenInvoice(direction="INFLOW", due_date=START + timedelta(days=155), outstanding=Decimal("500.00")),
        )
        full_bundle = predict_with_intervals_ml(sufficient, HORIZONS, "ensemble_ml", open_invoices=open_invs)
        self.assertTrue(full_bundle.uses_open_invoices)
        self.assertEqual(1, full_bundle.open_invoice_count)
        self.assertEqual(("drift_calendar", "gbdt_direct", "invoice_aware_ml"), full_bundle.active_members)


class FS1MLAlignmentAndParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset = load_dataset(DATA)
        cls.ledgers = load_invoice_ledgers(DATA)
        cls.evaluation = evaluate_ml(cls.dataset.series, cls.ledgers, date(2024, 12, 31), date(2025, 12, 31))

    def test_fs1_336_origins_zero_exclusions_and_intervals(self):
        self.assertEqual(336, len(self.evaluation.origins))
        self.assertEqual(date(2024, 12, 31), self.evaluation.origins[0])
        self.assertEqual(date(2025, 12, 1), self.evaluation.origins[-1])
        self.assertFalse(self.evaluation.excluded)
        # 3 SMEs * 336 origins * 2 horizons * 6 models = 12,096
        self.assertEqual(12096, len(self.evaluation.predictions))

        for row in self.evaluation.predictions:
            self.assertGreaterEqual(row.residual_count, MIN_RESIDUAL_COUNT)
            self.assertLessEqual(row.lower_95, row.lower_80)
            self.assertLessEqual(row.lower_80, row.predicted)
            self.assertLessEqual(row.predicted, row.upper_80)
            self.assertLessEqual(row.upper_80, row.upper_95)

    def test_cm1_and_cb1_exact_6_decimal_parity(self):
        summary = summarize_ml(self.evaluation.predictions, self.evaluation.train_scales)
        # 4 company keys (A, B, C, ALL) * 2 horizons * 6 models = 48 rows
        self.assertEqual(48, len(summary))

        metric_fields = (
            "n",
            "mae",
            "rmse",
            "me",
            "mase",
            "mae_delta_vs_naive",
            "rmse_delta_vs_naive",
            "mae_improvement_pct",
            "rmse_improvement_pct",
            "coverage_80",
            "coverage_95",
        )
        with CM1_METRICS.open(encoding="utf-8", newline="") as handle:
            cm1_rows = {
                (int(r["horizon_days"]), r["model"], r["company_key"]): r
                for r in csv.DictReader(handle)
            }
        ml_cm1_rows = {
            (r["horizon_days"], r["model"], r["company_key"]): {k: str(r[k]) for k in metric_fields}
            for r in summary
            if r["model"] in CM1_MODELS
        }
        self.assertEqual(set(cm1_rows), set(ml_cm1_rows))
        for key, expected in cm1_rows.items():
            actual = ml_cm1_rows[key]
            for field in metric_fields:
                self.assertEqual(expected[field], actual[field], msg=f"Mismatch at {key} field={field}")

        with BASELINE_METRICS.open(encoding="utf-8", newline="") as handle:
            cb1_naive = {
                (int(r["horizon_days"]), r["company_key"]): (r["mae"], r["rmse"])
                for r in csv.DictReader(handle)
                if r["baseline"] == "naive"
            }
        ml_naive = {
            (r["horizon_days"], r["company_key"]): (r["mae"], r["rmse"])
            for r in summary
            if r["model"] == "naive"
        }
        self.assertEqual(cb1_naive, ml_naive)

    def test_evaluate_ml_matches_predict_with_intervals_ml(self):
        sample_origins = (date(2024, 12, 31), date(2025, 6, 15), date(2025, 12, 1))
        indexed = {
            (r.company_key, r.origin, r.horizon_days, r.model): r
            for r in self.evaluation.predictions
            if r.origin in sample_origins
        }
        for item in self.dataset.series:
            key = item.company_key
            ledger = self.ledgers[key]
            for origin in sample_origins:
                history = item.history(origin)
                for model in ALL_MODELS:
                    with self.subTest(sme=key, origin=origin, model=model):
                        bundle = predict_with_intervals_ml(history, HORIZONS, model, invoice_ledger=ledger)
                        for h in HORIZONS:
                            eval_row = indexed[(key, origin, h, model)]
                            fc = bundle.forecasts[h]
                            self.assertEqual(fc.predicted, eval_row.predicted)
                            self.assertEqual(fc.lower_80, eval_row.lower_80)
                            self.assertEqual(fc.upper_80, eval_row.upper_80)
                            self.assertEqual(fc.lower_95, eval_row.lower_95)
                            self.assertEqual(fc.upper_95, eval_row.upper_95)
                            self.assertEqual(fc.residual_count, eval_row.residual_count)

    def test_deterministic_reproducibility_and_write_protection(self):
        second = evaluate_ml(self.dataset.series, self.ledgers, date(2024, 12, 31), date(2025, 12, 31))
        self.assertEqual(self.evaluation.predictions, second.predictions)
        first_contents = artifacts_ml(self.dataset, self.evaluation)
        second_contents = artifacts_ml(self.dataset, second)
        self.assertEqual(first_contents, second_contents)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifacts(first_contents, root / "one", DATA)
            write_artifacts(first_contents, root / "one", DATA)
            with self.assertRaises(ValueError):
                write_artifacts({"metrics.csv": "tampered"}, root / "one", DATA)


if __name__ == "__main__":
    unittest.main()
