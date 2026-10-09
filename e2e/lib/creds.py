"""Controlled test account credentials (local, git-ignored).

All accounts are created by the test harness itself via the app's normal
email/password sign-up flow, so they are fully under test control. These files
must never be committed.
"""

from __future__ import annotations

import os, time

SECRETS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".secrets")
CREDS = os.path.join(SECRETS_DIR, "creds.env")


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
        data["primary_password"] = "RUNTIME_PASSWORD"
        data["primary_initial_balance"] = "10000"
    if "secondary_email" not in data or regenerate:
        ts = primary_suffix or time.strftime("%Y%m%d.%H%M%S")
        data["secondary_email"] = f"e2eqa2.balance.{ts}@example.com"
        data["secondary_password"] = "RUNTIME_PASSWORD"
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


if __name__ == "__main__":
    d = load()
    for k, v in d.items():
        print(f"{k}={v}")
    print(f"\nstored in {CREDS}")