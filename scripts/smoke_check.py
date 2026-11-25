"""Start the built foundation on free loopback ports, check HTTP, then stop it.
Run from any directory with python3 scripts/smoke_check.py. No extra packages.
"""
from datetime import date, timedelta
from decimal import Decimal
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request_json(opener, url, method="GET", payload=None, expected_status=200):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if payload is None else {"Content-Type": "application/json", "Accept": "application/json"}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=10) as response:
            body = response.read().decode("utf-8")
            assert response.status == expected_status, f"Expected {expected_status}, got {response.status}: {body}"
            return json.loads(body)
    except urllib.error.HTTPError as err:
        body = err.read().decode("utf-8")
        assert err.code == expected_status, f"Expected {expected_status}, got {err.code} for {url}: {body}"
        return json.loads(body)


def build_synthetic_history(days=120, start=date(2024, 1, 1)):
    balance = Decimal("5000.00")
    entries = []
    for idx in range(days):
        day = start + timedelta(days=idx)
        balance += Decimal("35.00") if day.weekday() != 6 else Decimal("-50.00")
        entries.append({"date": day.isoformat(), "closing_balance": format(balance, ".2f")})
    return entries


def main():
    if not all(os.environ.get(key) for key in ("DB_URL", "DB_USER", "DB_PASSWORD")):
        raise SystemExit("Export root .env before running the smoke check; see README.")
    ports = set()
    while len(ports) < 3:
        ports.add(free_port())
    backend_port, python_port, frontend_port = sorted(ports)
    env = os.environ.copy()
    env.update(
        BACKEND_HOST="127.0.0.1",
        BACKEND_PORT=str(backend_port),
        FORECAST_PORT=str(python_port),
        VITE_PORT=str(frontend_port),
        VITE_BACKEND_TARGET=f"http://127.0.0.1:{backend_port}",
    )
    log_dir = ROOT / ".cache" / "smoke"
    log_dir.mkdir(parents=True, exist_ok=True)
    commands = [
        ("backend", ["java", "-jar", str(ROOT / "backend/target/backend-0.0.1-SNAPSHOT.jar")], ROOT),
        ("forecasting", [str(ROOT / "forecasting/.venv/bin/python"), "-m", "uvicorn",
                         "forecasting.app.main:app", "--host", "127.0.0.1", "--port", str(python_port)], ROOT),
        ("frontend", ["node", str(ROOT / "frontend/node_modules/vite/bin/vite.js")], ROOT / "frontend"),
    ]
    processes, logs = [], []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        for name, command, cwd in commands:
            log = (log_dir / f"{name}.log").open("w")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=cwd, env=env, stdout=log,
                                              stderr=subprocess.STDOUT, start_new_session=True))
        checks = [
            ("Java health", backend_port, "/api/health", {"status": "UP", "service": "backend"}),
            ("Database readiness", backend_port, "/api/health/database", {"status": "UP", "service": "database"}),
            ("Python health", python_port, "/health", {"status": "UP", "service": "forecasting"}),
            ("Vite → Java proxy", frontend_port, "/api/health", {"status": "UP", "service": "backend"}),
            ("Frontend HTML", frontend_port, "/", None),
        ]
        deadline = time.monotonic() + 45
        for label, port, path, expected in checks:
            while True:
                if any(p.poll() is not None for p in processes):
                    raise RuntimeError(f"A server exited; inspect {log_dir}")
                try:
                    with opener.open(f"http://127.0.0.1:{port}{path}", timeout=2) as response:
                        body = response.read().decode()
                        assert response.status == 200
                        if expected is not None:
                            assert json.loads(body) == expected, body
                        else:
                            assert '<div id="root">' in body and '/src/main.jsx' in body, body
                    print(f"PASS: {label}", flush=True)
                    break
                except (OSError, AssertionError, ValueError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f"{label} timed out; inspect {log_dir}")
                    time.sleep(0.3)

        smes = request_json(opener, f"http://127.0.0.1:{backend_port}/api/smes", expected_status=200)
        assert isinstance(smes, list), f"Expected JSON list from /api/smes, got {type(smes)}"
        print(f"PASS: GET /api/smes ({len(smes)} SME(s))", flush=True)

        missing_query = urllib.parse.urlencode({
            "openingDate": "2024-01-01",
            "openingAmount": "5000.00",
            "currency": "USD",
            "endDate": "2024-12-31",
        })
        missing_err = request_json(
            opener,
            f"http://127.0.0.1:{backend_port}/api/smes/999999/forecast?{missing_query}",
            expected_status=404,
        )
        assert missing_err.get("status") == 404, missing_err
        print("PASS: GET /api/smes/999999/forecast -> 404", flush=True)

        if smes:
            sme = smes[0]
            live_query = urllib.parse.urlencode({
                "openingDate": "2024-01-01",
                "openingAmount": "5000.00",
                "currency": sme["currency"],
                "endDate": "2024-12-31",
            })
            live_forecast = request_json(
                opener,
                f"http://127.0.0.1:{backend_port}/api/smes/{sme['id']}/forecast?{live_query}",
                expected_status=200,
            )
            assert len(live_forecast.get("forecasts", [])) == 2, live_forecast
            assert live_forecast.get("disclaimer"), live_forecast
            print(f"PASS: Live Java -> Python forecast for SME {sme['id']}", flush=True)

            live_ml_query = urllib.parse.urlencode({
                "openingDate": "2024-01-01",
                "openingAmount": "5000.00",
                "currency": sme["currency"],
                "endDate": "2024-12-31",
                "model": "ensemble_ml",
            })
            live_ml_forecast = request_json(
                opener,
                f"http://127.0.0.1:{backend_port}/api/smes/{sme['id']}/forecast?{live_ml_query}",
                expected_status=200,
            )
            assert len(live_ml_forecast.get("forecasts", [])) == 2, live_ml_forecast
            assert live_ml_forecast.get("model", {}).get("name") == "ensemble_ml", live_ml_forecast
            assert live_ml_forecast.get("model", {}).get("version") == "CM2", live_ml_forecast
            assert live_ml_forecast.get("model", {}).get("usesOpenInvoices") is True, live_ml_forecast
            print(f"PASS: Live Java -> Python CM2 ensemble_ml forecast for SME {sme['id']}", flush=True)
        else:
            py_forecast = request_json(
                opener,
                f"http://127.0.0.1:{python_port}/forecast",
                method="POST",
                payload={"currency": "USD", "history": build_synthetic_history(120)},
                expected_status=200,
            )
            assert len(py_forecast.get("forecasts", [])) == 2, py_forecast
            assert py_forecast.get("disclaimer"), py_forecast
            print("PASS: Direct Python POST /forecast (empty dev DB fallback)", flush=True)

            py_ml_forecast = request_json(
                opener,
                f"http://127.0.0.1:{python_port}/forecast",
                method="POST",
                payload={"currency": "USD", "history": build_synthetic_history(150), "model": "gbdt_direct"},
                expected_status=200,
            )
            assert len(py_ml_forecast.get("forecasts", [])) == 2, py_ml_forecast
            assert py_ml_forecast.get("model", {}).get("version") == "CM2", py_ml_forecast
            print("PASS: Direct Python POST /forecast CM2 gbdt_direct", flush=True)

        print("HTTP smoke checks and forecast bridge checks passed.")
    finally:
        for process in processes:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
        for process in processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
