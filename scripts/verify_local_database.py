"""Verify real local PostgreSQL authentication/isolation without printing secrets."""
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    prefix = subprocess.check_output(["brew", "--prefix", "postgresql@18"], text=True).strip()
    psql = str(Path(prefix) / "bin/psql")
    env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
    required = ["DB_PASSWORD", "TEST_DB_PASSWORD"]
    if not all(env.get(key) for key in required):
        raise SystemExit("Export root .env first; see README.")
    for database, role, key, other in [
        ("finsight", "finsight_app", "DB_PASSWORD", "finsight_test"),
        ("finsight_test", "finsight_test_app", "TEST_DB_PASSWORD", "finsight"),
    ]:
        args = [psql, "-X", "-w", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", "5432", "-U", role]
        def run(db, password, query="SELECT current_user || ':' || current_database();"):
            local_env = env.copy()
            if password is not None: local_env["PGPASSWORD"] = password
            return subprocess.run(args + ["-d", db, "-Atc", query], env=local_env, text=True, capture_output=True)
        result = run(database, env[key])
        assert result.returncode == 0 and result.stdout.strip() == role + ":" + database, "Correct-password connection failed"
        print(f"PASS: {role} password connection to {database}")
        for label, password in [("wrong", "deliberately-wrong-password"), ("missing", None)]:
            result = run(database, password)
            assert result.returncode != 0, f"{label} password unexpectedly accepted"
            assert "password" in result.stderr.lower(), "Failure was not password-related"
            print(f"PASS: {role} {label} password rejected")
        result = run(other, env[key])
        assert result.returncode != 0 and "permission denied for database" in result.stderr.lower(), "Cross-database access was not rejected"
        print(f"PASS: {role} denied connection to {other}")
    mode = (ROOT / ".env").stat().st_mode & 0o777
    assert mode == 0o600, ".env should be owner-only"
    assert subprocess.run(["git", "check-ignore", "-q", ".env"], cwd=ROOT).returncode == 0
    print("PASS: .env is ignored by Git and mode 0600")


if __name__ == "__main__":
    main()
