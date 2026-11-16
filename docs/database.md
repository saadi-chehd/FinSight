# Local PostgreSQL setup and operations

Run commands from the repository root. This machine uses `/opt/homebrew`; on another Mac determine it with `brew --prefix`. No Docker or globally installed Flyway is required.

## Current installation

PostgreSQL **18.6**, Homebrew formula `postgresql@18` revision `18.6_1`, is installed and manually running. Development database `finsight` is owned by `finsight_app`; test database `finsight_test` is owned by `finsight_test_app`. The roles are nonsuperusers with no database/role creation, replication, or bypass-RLS privileges. They can migrate their own databases, but cannot connect to each other's database.

Generated passwords are stored in root `.env`, ignored by Git and mode `0600`. Setup did not print them or put them in shell arguments. Do not run `cat .env`, enable shell tracing, or paste secrets into Git or documentation.

## Fresh-machine provisioning

The following installs system-wide software; use it only after the machine owner approves installation. It is already done on this machine.

```sh
HOMEBREW_NO_AUTO_UPDATE=1 brew install postgresql@18
mkdir -p .cache
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
```

Homebrew initializes its default cluster under `/opt/homebrew/var/postgresql@18`. Before configuring it, inspect its `pg_hba.conf`, confirm it is the new local development cluster, and check `SHOW listen_addresses;` through local `psql`. Keep it listening on localhost. No login service should be enabled.

On a fresh cluster without project roles, databases, or root `.env`:

```sh
python3 scripts/setup_local_database.py
python3 scripts/configure_postgres_auth.py
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 reload
set -a
source .env
set +a
python3 scripts/verify_local_database.py
```

The provisioning helper inspects for existing names, generates separate random passwords, writes `.env` with exclusive creation and owner-only permissions, and uses local administrator socket access to create the roles/databases. Both database defaults are UTC. PUBLIC connection privileges are revoked on these two databases. No financial tables or data are seeded by provisioning.

The authentication helper first backs up the inspected `pg_hba.conf` as `pg_hba.conf.before-finsight`, refuses to overwrite that backup, then replaces Homebrew's trust defaults. Application/test roles use SCRAM on sockets and loopback TCP; local administration uses OS peer identity. TCP access for all roles requires SCRAM. The administrator's existing local OS identity is used for provisioning only; never configure it as `DB_USER`.

Both helpers are intentionally one-time tools. They refuse existing project configuration rather than rotating passwords or destroying databases. If interrupted, inspect existing state before continuing. Do not run the authentication helper against a shared/preexisting cluster: its purpose is to configure this newly installed local development cluster.

## Existing .env or partial setup

Preserve it and provision manually through local administrator `psql` after inspecting roles/databases. If the names are absent, the equivalent SQL is:

```sql
CREATE ROLE finsight_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE finsight_test_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
```

Use psql's interactive `\password finsight_app` and `\password finsight_test_app` to set separate passwords without putting literal passwords in command history. Ensure `SHOW password_encryption` returns `scram-sha-256`. Then:

```sql
CREATE DATABASE finsight OWNER finsight_app ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE finsight_test OWNER finsight_test_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE finsight FROM PUBLIC;
REVOKE ALL ON DATABASE finsight_test FROM PUBLIC;
ALTER DATABASE finsight SET timezone TO 'UTC';
ALTER DATABASE finsight_test SET timezone TO 'UTC';
```

Set the corresponding values from `.env.example` in your existing ignored `.env`, using a local editor. Apply owner-only permissions with `chmod 600 .env`. Configure the same SCRAM/peer rules only after inspecting the existing cluster configuration.

## Manual start, status, stop

```sh
# Start (do not run if already running):
mkdir -p .cache
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -l "$PWD/.cache/postgresql.log" -w start
# Status and connection readiness:
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 status
/opt/homebrew/opt/postgresql@18/bin/pg_isready -h 127.0.0.1 -p 5432
# Stop the local server, rolling back any open transactions:
/opt/homebrew/opt/postgresql@18/bin/pg_ctl -D /opt/homebrew/var/postgresql@18 -m fast -w stop
```

Do not run `brew services start postgresql@18`: that enables automatic login startup, which this project did not request. `brew services list` should report `none` even while this manually launched server is running. `pg_isready` proves readiness, not correct password authentication; use the auth verification script for that.

## Migrate, build, test, and run

With the server running:

```sh
set -a
source .env
set +a
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Maven `verify` connects only to the test database. Java application startup connects to development and applies pending migrations. Application startup fails if credentials or migrations are invalid. No financial records are inserted by startup. Stop the Java process with Ctrl+C.

Flyway uses the classpath SQL packaged from `database/migrations`. `V1` and `V2` run once in order; `R__schema_comments.sql` maintains metadata and reruns when changed. It records checksums/history in `flyway_schema_history`; validation rejects altered applied versioned migrations. Add new versioned files for future schema changes. No destructive `clean`, baseline-on-migrate, auto-DDL, or parallel `schema.sql` initialization is enabled.

Read migration history without putting passwords in arguments:

```sh
PGPASSWORD="$DB_PASSWORD" /opt/homebrew/opt/postgresql@18/bin/psql -X -w -h 127.0.0.1 -U "$DB_USER" -d finsight -c 'SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

There is no rollback command. Review a new corrective migration for future changes; do not drop/reset a populated development database or edit applied migrations.

## Integration-test safety and scope

Tests require `TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/finsight_test` and `TEST_DB_USER=finsight_test_app`. The guard also accepts another numeric loopback port, but the default provisioning/auth script uses 5432. The guard rejects a development URL before configuring JDBC or Flyway. Tests do not silently skip or use an in-memory replacement.

The same migrations initialize the dedicated test database. Persistence tests insert minimal deterministic test fixtures and remove them afterward. Migration tests create uniquely named schemas inside the guarded test database, copy the SQL to a temporary directory, verify first/repeated application and checksum behavior, then remove only those schemas. They never edit repository SQL or clean the development schema. A failed/interrupted test can leave fixtures or disposable schemas for inspection; routine tests do not truncate unknown data.

See `database-schema.md` for constraints/conventions and `database-verification.md` for exact results and operational commands used.
