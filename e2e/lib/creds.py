"""Controlled test-account credentials and local Firebase config (never committed).

All e2e accounts are created by the harness itself through the app's normal
email/password sign-up flow, so they are fully under test control. This module
therefore holds NO literals that are worth stealing: passwords are generated at
runtime and only ever written to the git-ignored ``e2e/.secrets/creds.env``, and
the Firebase web API key / signing-cert hash are read from the git-ignored
``app/google-services.json`` (or the environment) instead of being hard-coded.

Environment overrides:
  E2E_PRIMARY_PASSWORD    fixed password for the primary test account
  E2E_SECONDARY_PASSWORD  fixed password for the secondary test account
  FIREBASE_WEB_API_KEY    web API key (else read from app/google-services.json)
  FIREBASE_CERT_HASH      signing-cert SHA-1 (else read from app/google-services.json)
"""

from __future__ import annotations

import json, os, secrets as _secrets, time

E2E_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(E2E_DIR)
SECRETS_DIR = os.path.join(E2E_DIR, ".secrets")
CREDS = os.path.join(SECRETS_DIR, "creds.env")
GOOGLE_SERVICES = os.path.join(REPO_ROOT, "app", "google-services.json")

_MISSING_CONFIG = (
    "Firebase config unavailable: place app/google-services.json in the repo "
    "(git-ignored - download it from the Firebase console, see "
    "app/google-services.json.example) or set the matching environment variable."
)


def random_password(length: int = 18) -> str:
    """Throwaway password generated at runtime; only persisted in e2e/.secrets/creds.env."""
    return _secrets.token_urlsafe(length)


def load(regenerate: bool = False, primary_suffix: str | None = None,
         primary_domain: str = "example.com") -> dict:
    data: dict[str, str] = {}
    if os.path.exists(CREDS) and not regenerate:
        with open(CREDS, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    data[k] = v
    if "primary_email" not in data or regenerate or primary_suffix:
        ts = primary_suffix or time.strftime("%Y%m%d%H%M%S")
        local = ts.replace(".", "")
        data["primary_email"] = f"e2eqa{local}@{primary_domain}"
        data["primary_password"] = os.environ.get("E2E_PRIMARY_PASSWORD") or random_password()
        data["primary_initial_balance"] = "10000"
    if "secondary_email" not in data or regenerate:
        ts = primary_suffix or time.strftime("%Y%m%d.%H%M%S")
        data["secondary_email"] = f"e2eqa2.balance.{ts}@example.com"
        data["secondary_password"] = os.environ.get("E2E_SECONDARY_PASSWORD") or random_password()
        data["secondary_initial_balance"] = "5000"
    if "primary_initial_balance" not in data:
        data["primary_initial_balance"] = "10000"
    save(data)
    return data


def save(data: dict) -> None:
    os.makedirs(SECRETS_DIR, exist_ok=True)
    with open(CREDS, "w", encoding="utf-8") as f:
        for k, v in data.items():
            f.write(f"{k}={v}\n")


def _google_services() -> dict:
    with open(GOOGLE_SERVICES, encoding="utf-8") as f:
        return json.load(f)


def firebase_web_api_key() -> str:
    """Web API key from app/google-services.json, or FIREBASE_WEB_API_KEY."""
    env = os.environ.get("FIREBASE_WEB_API_KEY")
    if env:
        return env
    try:
        return _google_services()["client"][0]["api_key"][0]["current_key"]
    except (OSError, KeyError, IndexError, TypeError) as exc:
        raise RuntimeError(_MISSING_CONFIG) from exc


def firebase_cert_hash() -> str:
    """Android signing-cert SHA-1 from app/google-services.json, or FIREBASE_CERT_HASH."""
    env = os.environ.get("FIREBASE_CERT_HASH")
    if env:
        return env.upper()
    try:
        clients = _google_services()["client"][0].get("oauth_client", [])
    except (OSError, KeyError, IndexError, TypeError) as exc:
        raise RuntimeError(_MISSING_CONFIG) from exc
    for client in clients:
        info = client.get("android_info") or {}
        if info.get("certificate_hash"):
            return info["certificate_hash"].upper()
    raise RuntimeError("No android_info certificate_hash found in app/google-services.json.")


if __name__ == "__main__":
    d = load()
    for k, v in d.items():
        print(f"{k}={v}")
    print(f"\nstored in {CREDS}")
