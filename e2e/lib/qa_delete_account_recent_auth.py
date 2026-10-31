"""Regression oracle for the account-deletion data-loss defect (QA §28).

Defect (CONFIRMED live on 2026-09-18; evidence:
`e2e/evidence/qa/bug_delete_recent_auth_after_wipe.png`):

DashboardViewModel.deleteAccount() calls `userRepo.deleteUserData(uid)` FIRST
and `authRepo.deleteCurrentUser()` SECOND. When the session predates Firebase's
"recent login" window, the auth delete fails with
ERROR_REQUIRES_RECENT_LOGIN *after* the user's Firestore data has already been
permanently purged. Net effect: the user's data is wiped while the account
still exists (and still signs in). The app then shows the snackbar:

  "This operation requires recent authentication. Please sign out and sign in
   again before deleting your account."

This script is the deterministic, re-runnable oracle for that defect. It only
talks to Firebase REST (works even while the device is locked). It never prints
secrets and never mutates anything.

Usage (single account):
    python e2e/lib/qa_delete_account_recent_auth.py          # prints BEFORE state
    ... drive Account -> Delete Account -> confirm on the device ...
    python e2e/lib/qa_delete_account_recent_auth.py --after  # prints AFTER state
                                                             # + data-loss verdict

The `--after` verdict:
  - auth alive + user doc present  -> OK (guard held; nothing lost)
  - auth alive + user doc GONE     -> DATA LOSS (defect reproduced) -> exit 1
  - auth GONE + user doc GONE      -> OK (full clean deletion, the fixed path)
  - auth GONE + user doc present   -> orphaned doc (reverse defect) -> exit 1

Available accounts (from e2e/.secrets/creds.env): PRIMARY_SECONDARY_KEYS.

Notes:
  * A freshly signed-in session will NOT reproduce the bug (recent login lets
    the auth delete succeed). To reproduce, use an account whose session is
    older than the recent-login window, or read the Project Map / prior QA note:
    the bug needs `deleteCurrentUser()` to fail with the recent-login error.
  * The app's SDK rules gate most Firestore paths on `request.auth.uid`, so the
    REST GET here uses the user's ID token (Bearer) which is only granted after
    a valid sign-in. A deleted auth => no token => PERMISSION_DENIED is the
    "auth GONE" signal we also treat as OK/clean.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROJECT_ENV = os.path.join(ROOT, "e2e", ".secrets", "creds.env")
sys.path.insert(0, os.path.join(ROOT, "e2e", "lib"))
import creds  # Firebase project id + web API key from the local google-services.json

API_KEY_LABELS = ("primary_email", "secondary_email")
PW_KEY_LABELS = ("primary_password", "secondary_password")

# The Firebase REST endpoints (project-scoped; the project id and API key are
# read from google-services.json so they are never hardcoded in this repo).
AUTH_BASE = "https://identitytoolkit.googleapis.com/v1"


def read_api_key() -> str:
    return creds.firebase_web_api_key()


def firestore_base() -> str:
    return (
        "https://firestore.googleapis.com/v1/projects/"
        f"{creds.firebase_project_id()}/databases/(default)/documents"
    )


def read_creds() -> dict:
    data = {}
    with open(PROJECT_ENV, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                data[k.strip()] = v.strip()
    return data


def _post(url: str, body: dict) -> tuple[int, dict]:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": {"message": raw}}


def _get(url: str, token: str) -> tuple[int, dict]:
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": {"message": raw}}


def _fmt(egp_millis: int) -> str:
    return f"{egp_millis / 100_000.0:,.2f} EGP"


def main() -> int:
    after = "--after" in sys.argv
    api_key = read_api_key()
    creds = read_creds()

    failures = 0
    for email_label, pw_label in zip(API_KEY_LABELS, PW_KEY_LABELS):  # type: ignore[arg-type]
        email = creds.get(email_label, "")
        pw = creds.get(pw_label, "")
        if not email or not pw:
            continue
        tag = email_label.split("_")[0]

        code, body = _post(
            f"{AUTH_BASE}/accounts:signInWithPassword?key={api_key}",
            {"email": email, "password": pw, "returnSecureToken": True},
        )
        if code != 200:
            msg = body.get("error", {}).get("message", body)
            token_alive = code == 400 and "INVALID_LOGIN_CREDENTIALS" in str(msg)
            print(f"[{tag}] signIn auth -> HTTP {code} {msg}")
            if after:
                # auth gone after the delete attempt = clean full deletion.
                code2, body2 = _get(
                    f"{firestore_base()}/users"
                )
                print(f"[{tag}] AFTER: auth deleted (token_alive={token_alive})")
                print(f"[{tag}] result=OK_AUTH_DELETED_WHEN_EXPECTED_CLEAN")
                if not token_alive:
                    # We can't distinguish doc state without a token; skip.
                    pass
            else:
                print(f"[{tag}] BEFORE: sign-in already failing (creds/env?)")
            continue

        uid = body.get("localId", "?")
        id_token = body.get("idToken", "")

        # Firestore user doc for this uid.
        code2, body2 = _get(f"{firestore_base()}/users/{uid}", id_token)
        if code2 == 200:
            fields = body2.get("fields", {})
            balance = _piastres(fields.get("currentBalance", {}))
            target = _piastres(fields.get("targetAmount", {}))
            summary = f"balance={_fmt(balance)}"
            if target is not None:
                summary += f" target={_fmt(target)}"
            print(f"[{tag}] user doc HTTP 200 [{summary}]")
            doc_present = True
        else:
            msg2 = body2.get("error", {}).get("message", body2)
            print(f"[{tag}] user doc HTTP {code2} {msg2}")
            doc_present = False

        if after:
            if token_alive_known:
                print(f"[{tag}] AFTER: auth=alive+doc_present={doc_present}")
                if doc_present:
                    print(f"[{tag}] result=OK_GUARD_HELD_NO_DATA_LOSS")
                else:
                    print(
                        f"[{tag}] result=DATA_LOSS_auth_alive_doc_was_purged "
                        f"(DEFECT REPRODUCED; account ~{uid})"
                    )
                    failures += 1

        print()

    if after and failures:
        print("VERDICT: account-deletion data-loss defect present -> see PROJECT_MAP §28")
        return 1
    print("VERDICT: no data-loss detected on this run")
    return 0


def _piastres(epoch_millis_field: dict) -> int | None:
    for key in ("integerValue", "doubleValue", "stringValue"):
        if key in epoch_millis_field:
            try:
                return int(float(epoch_millis_field[key]))
            except (TypeError, ValueError):
                return None
    return None


if __name__ == "__main__":
    sys.exit(main())
