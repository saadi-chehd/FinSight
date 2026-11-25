from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import re
import unittest

from fastapi.testclient import TestClient

from forecasting.app.main import app
from forecasting.experiments.baseline_data import load_dataset
from forecasting.experiments.cash_flow_ml import (
    ALL_MODELS,
    CM1_MODELS,
    HORIZONS,
    ML_MODELS,
    OpenInvoice,
    load_invoice_ledgers,
    min_required_history_days_all,
    predict_with_intervals_ml,
)
from forecasting.experiments.cash_flow_models import (
    MODELS,
    min_required_history_days,
    predict_with_intervals,
)


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/synthetic/FS1-20261018"
MONEY_RE = re.compile(r"^-?\d+\.\d{2}$")


def build_history_payload(days: int = 150, start: date = date(2024, 1, 1), step: Decimal = Decimal("45.50")) -> list[dict]:
    balance = Decimal("5000.00")
    entries = []
    for idx in range(days):
        day = start + timedelta(days=idx)
        balance = balance + step + (Decimal("120.00") if day.weekday() == 1 else Decimal("0.00"))
        entries.append({"date": day.isoformat(), "closing_balance": format(balance, ".2f")})
    return entries


class ForecastApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)
        cls.dataset = load_dataset(DATA)
        cls.ledgers = load_invoice_ledgers(DATA)

    def test_health_endpoint_unchanged(self):
        response = self.client.get("/health")
        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "UP", "service": "forecasting"}, response.json())

    def test_post_forecast_happy_paths_all_models(self):
        history = build_history_payload(days=150)
        default_resp = self.client.post("/forecast", json={"currency": "usd", "history": history})
        self.assertEqual(200, default_resp.status_code)
        body = default_resp.json()
        self.assertEqual("USD", body["currency"])
        self.assertEqual("2024-01-01", body["history_start_date"])
        self.assertEqual(history[-1]["date"], body["origin_date"])
        self.assertEqual(150, body["history_days"])
        self.assertEqual("drift_calendar", body["model"]["name"])
        self.assertEqual([7, 30], [f["horizon_days"] for f in body["forecasts"]])
        self.assertIn("conditional mathematical estimates", body["disclaimer"].lower())
        self.assertIn("not solvency proofs", body["disclaimer"].lower())

        for model in MODELS:
            with self.subTest(model=model):
                resp = self.client.post(
                    "/forecast",
                    json={
                        "currency": "EUR",
                        "history": history,
                        "horizons": [1, 7, 14],
                        "model": model,
                    },
                )
                self.assertEqual(200, resp.status_code)
                data = resp.json()
                self.assertEqual("EUR", data["currency"])
                self.assertEqual(model, data["model"]["name"])
                self.assertEqual([1, 7, 14], [f["horizon_days"] for f in data["forecasts"]])
                for fc in data["forecasts"]:
                    self.assertRegex(fc["predicted_closing_balance"], MONEY_RE)
                    self.assertRegex(fc["interval_80"]["lower"], MONEY_RE)
                    self.assertRegex(fc["interval_80"]["upper"], MONEY_RE)
                    self.assertRegex(fc["interval_95"]["lower"], MONEY_RE)
                    self.assertRegex(fc["interval_95"]["upper"], MONEY_RE)
                    l95 = Decimal(fc["interval_95"]["lower"])
                    l80 = Decimal(fc["interval_80"]["lower"])
                    pred = Decimal(fc["predicted_closing_balance"])
                    u80 = Decimal(fc["interval_80"]["upper"])
                    u95 = Decimal(fc["interval_95"]["upper"])
                    self.assertLessEqual(l95, l80)
                    self.assertLessEqual(l80, pred)
                    self.assertLessEqual(pred, u80)
                    self.assertLessEqual(u80, u95)
                    self.assertGreaterEqual(fc["residual_count"], 20)

    def test_post_forecast_matches_predict_with_intervals_on_fs1_prefix(self):
        series = self.dataset.series[0]
        origin = date(2024, 12, 31)
        history = series.history(origin)
        payload_history = [
            {"date": obs.day.isoformat(), "closing_balance": format(obs.closing, ".2f")}
            for obs in history.observations
        ]
        for model in MODELS:
            with self.subTest(model=model):
                expected = predict_with_intervals(history, HORIZONS, model)
                resp = self.client.post(
                    "/forecast",
                    json={"currency": series.currency, "history": payload_history, "model": model},
                )
                self.assertEqual(200, resp.status_code)
                forecasts = {item["horizon_days"]: item for item in resp.json()["forecasts"]}
                for h in HORIZONS:
                    fc = expected[h]
                    self.assertEqual(format(fc.predicted, ".2f"), forecasts[h]["predicted_closing_balance"])
                    self.assertEqual(format(fc.lower_80, ".2f"), forecasts[h]["interval_80"]["lower"])
                    self.assertEqual(format(fc.upper_80, ".2f"), forecasts[h]["interval_80"]["upper"])
                    self.assertEqual(format(fc.lower_95, ".2f"), forecasts[h]["interval_95"]["lower"])
                    self.assertEqual(format(fc.upper_95, ".2f"), forecasts[h]["interval_95"]["upper"])
                    self.assertEqual(fc.residual_count, forecasts[h]["residual_count"])

    def test_post_forecast_negative_balances_allowed(self):
        history = build_history_payload(days=120, step=Decimal("-150.00"))
        resp = self.client.post("/forecast", json={"currency": "USD", "history": history, "model": "drift_calendar"})
        self.assertEqual(200, resp.status_code)
        for fc in resp.json()["forecasts"]:
            self.assertLess(Decimal(fc["predicted_closing_balance"]), Decimal("0.00"))

    def test_post_forecast_rejects_invalid_inputs(self):
        valid = build_history_payload(days=130)

        # Missing calendar day
        gap = valid[:40] + valid[41:]
        self.assertEqual(422, self.client.post("/forecast", json={"currency": "USD", "history": gap}).status_code)

        # Duplicate date
        dup = valid[:40] + [valid[39]] + valid[40:]
        self.assertEqual(422, self.client.post("/forecast", json={"currency": "USD", "history": dup}).status_code)

        # Reversed dates
        rev = list(reversed(valid))
        self.assertEqual(422, self.client.post("/forecast", json={"currency": "USD", "history": rev}).status_code)

        # Fractional minor units (string and float)
        for bad_balance in ("100.123", 100.123, True, None, "not-a-number"):
            bad = valid[:-1] + [{"date": valid[-1]["date"], "closing_balance": bad_balance}]
            with self.subTest(bad_balance=bad_balance):
                self.assertEqual(422, self.client.post("/forecast", json={"currency": "USD", "history": bad}).status_code)

        # Insufficient history for drift_calendar / ridge_lag_calendar / naive at horizon 30
        min_drift = min_required_history_days("drift_calendar", 30)
        self.assertEqual(
            422,
            self.client.post(
                "/forecast",
                json={"currency": "USD", "history": valid[: min_drift - 1], "model": "drift_calendar"},
            ).status_code,
        )
        min_naive = min_required_history_days("naive", 30)
        self.assertEqual(
            422,
            self.client.post(
                "/forecast",
                json={"currency": "USD", "history": valid[: min_naive - 1], "model": "naive"},
            ).status_code,
        )

        # Empty history, bad model, bad horizons, bad currency
        for bad_payload in (
            {"currency": "USD", "history": []},
            {"currency": "USD", "history": valid, "model": "arima"},
            {"currency": "USD", "history": valid, "horizons": []},
            {"currency": "USD", "history": valid, "horizons": [7, 7]},
            {"currency": "USD", "history": valid, "horizons": [0]},
            {"currency": "USD", "history": valid, "horizons": [True]},
            {"currency": "USD", "history": valid, "horizons": [400]},
            {"currency": "US", "history": valid},
            {"currency": "123", "history": valid},
        ):
            with self.subTest(bad_payload=bad_payload):
                self.assertEqual(422, self.client.post("/forecast", json=bad_payload).status_code)

    def test_post_forecast_all_six_models_happy_path_and_metadata(self):
        history = build_history_payload(days=160)
        origin = date.fromisoformat(history[-1]["date"])
        sample_invoices = [
            {
                "direction": "INFLOW",
                "due_date": (origin + timedelta(days=5)).isoformat(),
                "outstanding": "1250.00",
            },
            {
                "direction": "OUTFLOW",
                "due_date": (origin + timedelta(days=12)).isoformat(),
                "outstanding": 400.5,
            },
        ]

        for model in ALL_MODELS:
            with self.subTest(model=model):
                payload: dict = {
                    "currency": "USD",
                    "history": history,
                    "horizons": [7, 30],
                    "model": model,
                }
                if model in ("invoice_aware_ml", "ensemble_ml"):
                    payload["open_invoices"] = sample_invoices

                resp = self.client.post("/forecast", json=payload)
                self.assertEqual(200, resp.status_code, msg=resp.text)
                data = resp.json()
                expected_version = "CM1" if model in CM1_MODELS else "CM2"
                self.assertEqual(expected_version, data["model"]["version"])
                self.assertEqual(model, data["model"]["name"])
                self.assertIn("conditional mathematical estimates", data["disclaimer"].lower())
                self.assertIn(
                    "not solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions",
                    data["disclaimer"].lower(),
                )
                if model in ("invoice_aware_ml", "ensemble_ml"):
                    self.assertTrue(data["model"]["uses_open_invoices"])
                    self.assertEqual(len(sample_invoices), data["model"]["open_invoice_count"])
                else:
                    self.assertFalse(data["model"]["uses_open_invoices"])
                    self.assertIsNone(data["model"]["open_invoice_count"])

                for fc in data["forecasts"]:
                    self.assertRegex(fc["predicted_closing_balance"], MONEY_RE)
                    self.assertRegex(fc["interval_80"]["lower"], MONEY_RE)
                    self.assertRegex(fc["interval_80"]["upper"], MONEY_RE)
                    self.assertRegex(fc["interval_95"]["lower"], MONEY_RE)
                    self.assertRegex(fc["interval_95"]["upper"], MONEY_RE)
                    l95 = Decimal(fc["interval_95"]["lower"])
                    l80 = Decimal(fc["interval_80"]["lower"])
                    pred = Decimal(fc["predicted_closing_balance"])
                    u80 = Decimal(fc["interval_80"]["upper"])
                    u95 = Decimal(fc["interval_95"]["upper"])
                    self.assertLessEqual(l95, l80)
                    self.assertLessEqual(l80, pred)
                    self.assertLessEqual(pred, u80)
                    self.assertLessEqual(u80, u95)
                    self.assertGreaterEqual(fc["residual_count"], 20)

    def test_post_forecast_open_invoices_modes_and_ensemble_fallback(self):
        history = build_history_payload(days=160)
        origin = date.fromisoformat(history[-1]["date"])

        # invoice_aware_ml without open_invoices -> 422
        missing_resp = self.client.post(
            "/forecast",
            json={"currency": "USD", "history": history, "model": "invoice_aware_ml"},
        )
        self.assertEqual(422, missing_resp.status_code)
        self.assertIn("open_invoices", missing_resp.json()["detail"])

        # invoice_aware_ml with empty open_invoices=[] -> 200, uses_open_invoices=True, count=0
        empty_inv_resp = self.client.post(
            "/forecast",
            json={
                "currency": "USD",
                "history": history,
                "model": "invoice_aware_ml",
                "open_invoices": [],
            },
        )
        self.assertEqual(200, empty_inv_resp.status_code)
        empty_meta = empty_inv_resp.json()["model"]
        self.assertTrue(empty_meta["uses_open_invoices"])
        self.assertEqual(0, empty_meta["open_invoice_count"])

        # ensemble_ml without open_invoices -> 200, graceful fallback to ["drift_calendar", "gbdt_direct"]
        fallback_resp = self.client.post(
            "/forecast",
            json={"currency": "USD", "history": history, "model": "ensemble_ml"},
        )
        self.assertEqual(200, fallback_resp.status_code)
        fb_meta = fallback_resp.json()["model"]
        self.assertEqual("CM2", fb_meta["version"])
        self.assertFalse(fb_meta["uses_open_invoices"])
        self.assertIsNone(fb_meta["open_invoice_count"])
        self.assertEqual(["drift_calendar", "gbdt_direct"], fb_meta["active_members"])
        self.assertIsNotNone(fb_meta["ensemble_weights"])
        for h_key in ("7", "30"):
            self.assertEqual({"drift_calendar", "gbdt_direct"}, set(fb_meta["ensemble_weights"][h_key]))
            self.assertAlmostEqual(1.0, sum(fb_meta["ensemble_weights"][h_key].values()), places=4)

        # ensemble_ml with empty open_invoices=[] -> 200, uses_open_invoices=True, count=0, 3 active members
        ens_empty_resp = self.client.post(
            "/forecast",
            json={
                "currency": "USD",
                "history": history,
                "model": "ensemble_ml",
                "open_invoices": [],
            },
        )
        self.assertEqual(200, ens_empty_resp.status_code)
        ens_empty_meta = ens_empty_resp.json()["model"]
        self.assertTrue(ens_empty_meta["uses_open_invoices"])
        self.assertEqual(0, ens_empty_meta["open_invoice_count"])
        self.assertEqual(["drift_calendar", "gbdt_direct", "invoice_aware_ml"], ens_empty_meta["active_members"])

        # ensemble_ml with non-empty open_invoices
        ens_full_resp = self.client.post(
            "/forecast",
            json={
                "currency": "USD",
                "history": history,
                "model": "ensemble_ml",
                "open_invoices": [
                    {
                        "direction": "INFLOW",
                        "due_date": (origin + timedelta(days=3)).isoformat(),
                        "outstanding": "900.00",
                    }
                ],
            },
        )
        self.assertEqual(200, ens_full_resp.status_code)
        ens_full_meta = ens_full_resp.json()["model"]
        self.assertTrue(ens_full_meta["uses_open_invoices"])
        self.assertEqual(1, ens_full_meta["open_invoice_count"])
        self.assertEqual(["drift_calendar", "gbdt_direct", "invoice_aware_ml"], ens_full_meta["active_members"])
        for h_key in ("7", "30"):
            self.assertEqual(
                {"drift_calendar", "gbdt_direct", "invoice_aware_ml"},
                set(ens_full_meta["ensemble_weights"][h_key]),
            )
            self.assertAlmostEqual(1.0, sum(ens_full_meta["ensemble_weights"][h_key].values()), places=4)

    def test_post_forecast_rejects_invalid_open_invoices_and_insufficient_ml_history(self):
        history = build_history_payload(days=160)
        valid_due = "2024-06-15"

        bad_invoices = [
            {"direction": "INFLOW", "due_date": valid_due, "outstanding": "-10.00"},
            {"direction": "INFLOW", "due_date": valid_due, "outstanding": -5},
            {"direction": "INFLOW", "due_date": valid_due, "outstanding": "0.00"},
            {"direction": "INFLOW", "due_date": valid_due, "outstanding": 0},
            {"direction": "OUTFLOW", "due_date": valid_due, "outstanding": "100.123"},
            {"direction": "OUTFLOW", "due_date": valid_due, "outstanding": 100.123},
            {"direction": "OUTFLOW", "due_date": valid_due, "outstanding": True},
            {"direction": "OUTFLOW", "due_date": valid_due, "outstanding": None},
            {"direction": "OUTFLOW", "due_date": valid_due, "outstanding": "abc"},
            {"direction": "INFLOW", "due_date": 20240615, "outstanding": "100.00"},
            {"direction": "INFLOW", "due_date": True, "outstanding": "100.00"},
            {"direction": "INFLOW", "due_date": 2024.5, "outstanding": "100.00"},
            {"direction": "INFLOW", "due_date": "not-a-date", "outstanding": "100.00"},
            {"direction": "INFLOW", "due_date": None, "outstanding": "100.00"},
            {"direction": "TRANSFER", "due_date": valid_due, "outstanding": "100.00"},
            {"direction": "", "due_date": valid_due, "outstanding": "100.00"},
            {"direction": 123, "due_date": valid_due, "outstanding": "100.00"},
        ]
        for bad_inv in bad_invoices:
            with self.subTest(bad_inv=bad_inv):
                resp = self.client.post(
                    "/forecast",
                    json={
                        "currency": "USD",
                        "history": history,
                        "model": "invoice_aware_ml",
                        "open_invoices": [bad_inv],
                    },
                )
                self.assertEqual(422, resp.status_code)

        for model in ML_MODELS:
            min_ml = min_required_history_days_all(model, 30)
            payload = {
                "currency": "USD",
                "history": history[: min_ml - 1],
                "model": model,
            }
            if model == "invoice_aware_ml":
                payload["open_invoices"] = []
            with self.subTest(model=model):
                self.assertEqual(422, self.client.post("/forecast", json=payload).status_code)

    def test_post_forecast_matches_predict_with_intervals_ml_on_fs1_prefix(self):
        series = self.dataset.series[0]
        origin = date(2024, 12, 31)
        history = series.history(origin)
        open_invs = self.ledgers[series.company_key].open_invoices_as_of(origin)
        payload_history = [
            {"date": obs.day.isoformat(), "closing_balance": format(obs.closing, ".2f")}
            for obs in history.observations
        ]
        payload_invoices = [
            {
                "direction": inv.direction,
                "due_date": inv.due_date.isoformat(),
                "outstanding": format(inv.outstanding, ".2f"),
            }
            for inv in open_invs
        ]
        for model in ML_MODELS:
            with self.subTest(model=model):
                bundle = predict_with_intervals_ml(
                    history,
                    HORIZONS,
                    model,
                    open_invoices=open_invs if model != "gbdt_direct" else None,
                )
                req_json = {
                    "currency": series.currency,
                    "history": payload_history,
                    "model": model,
                }
                if model != "gbdt_direct":
                    req_json["open_invoices"] = payload_invoices
                resp = self.client.post("/forecast", json=req_json)
                self.assertEqual(200, resp.status_code, msg=resp.text)
                forecasts = {item["horizon_days"]: item for item in resp.json()["forecasts"]}
                for h in HORIZONS:
                    fc = bundle.forecasts[h]
                    self.assertEqual(format(fc.predicted, ".2f"), forecasts[h]["predicted_closing_balance"])
                    self.assertEqual(format(fc.lower_80, ".2f"), forecasts[h]["interval_80"]["lower"])
                    self.assertEqual(format(fc.upper_80, ".2f"), forecasts[h]["interval_80"]["upper"])
                    self.assertEqual(format(fc.lower_95, ".2f"), forecasts[h]["interval_95"]["lower"])
                    self.assertEqual(format(fc.upper_95, ".2f"), forecasts[h]["interval_95"]["upper"])
                    self.assertEqual(fc.residual_count, forecasts[h]["residual_count"])


if __name__ == "__main__":
    unittest.main()
