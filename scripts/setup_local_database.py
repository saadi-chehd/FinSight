"""One-time provisioning of fresh local development/test databases; no fixture data.
Requires running Homebrew PostgreSQL 18 and local administrator socket access.
Never overwrites an existing .env, database, or role.
"""
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    prefix = subprocess.check_output(["brew", "--prefix", "postgresql@18"], text=True).strip()
    psql = str(Path(prefix) / "bin/psql")
    command = [psql, "-X", "-v", "ON_ERROR_STOP=1", "-d", "postgres"]
    # Force local socket administrator access, regardless of inherited app settings.
    env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
    def sql(statement):
        result = subprocess.run(command + ["-At"], input=statement, text=True, env=env, capture_output=True)
        if result.returncode:
            message = result.stderr
            for password in passwords:
                message = message.replace(password, "[REDACTED]")
            raise RuntimeError(message)
        return result.stdout.strip()
    passwords = []
    if (ROOT / ".env").exists():
        raise SystemExit("Existing .env preserved. Provision manually using docs/database.md.")
    exists = sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('finsight_app','finsight_test_app');")
    databases = sql("SELECT count(*) FROM pg_database WHERE datname IN ('finsight','finsight_test');")
    if exists != "0" or databases != "0":
        raise SystemExit("Existing roles/databases preserved; inspect them before manual provisioning.")
    passwords = [secrets.token_hex(32), secrets.token_hex(32)]
    content = (ROOT / ".env.example").read_text()
    content = content.replace("DB_PASSWORD=replace_with_local_password", "DB_PASSWORD=" + passwords[0])
    content = content.replace("TEST_DB_PASSWORD=replace_with_different_local_password", "TEST_DB_PASSWORD=" + passwords[1])
    fd = os.open(ROOT / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as handle:
        handle.write(content)
    for role, database, password in zip(["finsight_app", "finsight_test_app"], ["finsight", "finsight_test"], passwords):
        sql(f"SET password_encryption='scram-sha-256'; CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{password}';")
        sql(f"CREATE DATABASE {database} OWNER {role} ENCODING 'UTF8' TEMPLATE template0;")
        sql(f"REVOKE ALL ON DATABASE {database} FROM PUBLIC;")
        sql(f"ALTER DATABASE {database} SET timezone TO 'UTC';")
    print("Created two nonsuperuser roles and isolated databases; credentials stored only in ignored .env (0600).")
    print("No schema or business records inserted. If provisioning was interrupted, inspect before retrying.")


if __name__ == "__main__":
    main()
