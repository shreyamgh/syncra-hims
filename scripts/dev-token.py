#!/usr/bin/env python3
"""Mint a short-lived HS256 JWT for LOCAL DEVELOPMENT against the gateway.

Reads SYNCRA_JWT_SECRET / _ISSUER / _AUDIENCE from the project .env.
Stand-in until the Syncra IAM service exists — never use in production.

    python3 scripts/dev-token.py --sub dr.sharma --roles DOCTOR
    curl -H "Authorization: Bearer $(python3 scripts/dev-token.py)" localhost:8080/...
"""

import argparse
import base64
import hashlib
import hmac
import json
import sys
import time
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


def read_env(path: Path) -> dict:
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint(secret: str, claims: dict) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = ".".join(
        b64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims)
    )
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sub", default="dev.doctor", help="subject (user id)")
    parser.add_argument("--roles", nargs="*", default=["DOCTOR"], help="role names")
    parser.add_argument("--ttl", type=int, default=3600, help="lifetime in seconds")
    args = parser.parse_args()

    if not ENV_FILE.exists():
        print("dev-token: .env not found; run ./scripts/init-env.sh first", file=sys.stderr)
        return 1
    env = read_env(ENV_FILE)
    if env.get("SYNCRA_ENVIRONMENT", "development") == "production":
        print("dev-token: refusing to mint tokens for a production environment", file=sys.stderr)
        return 1

    now = int(time.time())
    claims = {
        "iss": env.get("SYNCRA_JWT_ISSUER", "https://auth.syncra.local"),
        "aud": [env.get("SYNCRA_JWT_AUDIENCE", "syncra-hims")],
        "sub": args.sub,
        "roles": args.roles,
        "iat": now,
        "nbf": now,
        "exp": now + args.ttl,
    }
    print(mint(env["SYNCRA_JWT_SECRET"], claims))
    return 0


if __name__ == "__main__":
    sys.exit(main())
