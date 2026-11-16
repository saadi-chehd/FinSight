#!/bin/sh
# Explicit manual tooling only. Does not read/print .env or start the application.
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"
exec java -Dloader.main=com.finsight.synthetic.SyntheticDataCli \
  -cp "$project_root/backend/target/backend-0.0.1-SNAPSHOT.jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
