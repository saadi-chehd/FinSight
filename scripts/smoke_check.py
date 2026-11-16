"""Start the built foundation on free loopback ports, check HTTP, then stop it.
Run from any directory with python3 scripts/smoke_check.py. No extra packages.
"""
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    if not all(os.environ.get(key) for key in ("DB_URL", "DB_USER", "DB_PASSWORD")):
        raise SystemExit("Export root .env before running the smoke check; see README.")
    ports = set()
    while len(ports) < 3:
        ports.add(free_port())
    backend_port, python_port, frontend_port = sorted(ports)
    env = os.environ.copy()
    env.update(BACKEND_HOST="127.0.0.1", BACKEND_PORT=str(backend_port),
               VITE_PORT=str(frontend_port),
               VITE_BACKEND_TARGET=f"http://127.0.0.1:{backend_port}")
    log_dir = ROOT / ".cache" / "smoke"
    log_dir.mkdir(parents=True, exist_ok=True)
    commands = [
        ("backend", ["java", "-jar", str(ROOT / "backend/target/backend-0.0.1-SNAPSHOT.jar")], ROOT),
        ("forecasting", [str(ROOT / "forecasting/.venv/bin/python"), "-m", "uvicorn",
                         "app.main:app", "--host", "127.0.0.1", "--port", str(python_port)], ROOT / "forecasting"),
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
        print("HTTP smoke checks passed; browser rendering and Java–Python calls are not tested.")
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
