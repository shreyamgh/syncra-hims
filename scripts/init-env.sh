#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Creates .env from .env.example, replacing every `__GENERATE__` placeholder
# with an independent random 256-bit hex secret. Refuses to overwrite an
# existing .env unless --force is given (rotating secrets on a live stack
# requires re-initialising the datastores that captured them).
# -----------------------------------------------------------------------------
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT_DIR/.env.example"
TARGET="$ROOT_DIR/.env"

if [[ -e "$TARGET" && "${1:-}" != "--force" ]]; then
  echo "init-env: $TARGET already exists; use --force to regenerate all secrets." >&2
  exit 1
fi

command -v openssl >/dev/null || { echo "init-env: openssl is required" >&2; exit 1; }

umask 077
tmp="$(mktemp "$ROOT_DIR/.env.XXXXXX")"
trap 'rm -f "$tmp"' ERR

while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ ^([A-Z0-9_]+)=__GENERATE__$ ]]; then
    printf '%s=%s\n' "${BASH_REMATCH[1]}" "$(openssl rand -hex 32)"
  else
    printf '%s\n' "$line"
  fi
done <"$TEMPLATE" >"$tmp"

mv "$tmp" "$TARGET"
chmod 600 "$TARGET"
echo "init-env: wrote $TARGET (mode 600)."
