#!/usr/bin/env bash
# Last init step: records that first-boot initialisation finished successfully.
# Checked by ../entrypoint-preflight.sh on every subsequent start.
set -Eeuo pipefail

date -u +"%Y-%m-%dT%H:%M:%SZ" >"$PGDATA/.syncra-initialized"
echo "[syncra-initdb] initialisation complete"
