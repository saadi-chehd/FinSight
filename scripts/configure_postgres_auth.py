"""Replace trust defaults on the freshly installed Homebrew PostgreSQL cluster.
Inspect the cluster first; backs up pg_hba.conf once and refuses repeated replacement.
"""
from pathlib import Path
import subprocess

prefix = Path(subprocess.check_output(["brew", "--prefix"], text=True).strip())
hba = prefix / "var/postgresql@18/pg_hba.conf"
backup = hba.with_name("pg_hba.conf.before-finsight")
original = hba.read_bytes()
with backup.open("xb") as handle:
    handle.write(original)
hba.write_text("""# FinSight local development: OS identity for administration; SCRAM for app roles.
local all finsight_app scram-sha-256
local all finsight_test_app scram-sha-256
local all all peer
host all all 127.0.0.1/32 scram-sha-256
host all all ::1/128 scram-sha-256
local replication all peer
host replication all 127.0.0.1/32 scram-sha-256
host replication all ::1/128 scram-sha-256
""")
print("Backed up and configured pg_hba.conf; reload the server before verification.")
