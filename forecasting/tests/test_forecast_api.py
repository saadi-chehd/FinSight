from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import re
import unittest

from fastapi.testclient import TestClient

from forecasting.app.main import app
from forecasting.experiments.baseline_data import load_dataset
from forecasting.experiments.cash_flow_models import (
    HORIZONS,
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


if __name__ == "__main__":
    unittest.main()
