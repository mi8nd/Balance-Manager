"""Seed August-2026 transaction history for the primary QA account via Firestore REST.

Uses the account's Firebase ID token (obtained from identitytoolkit signInWithPassword)
as the bearer credential against the Firestore REST API. Transactions are written under
users/{uid}/transactions with explicit categories and August-2026 timestamps. The
users/{uid} currentBalance is left untouched (historical backfill must not alter balance).
"""
import json
import sys
import urllib.request
import urllib.parse
from datetime import datetime, timezone, timedelta
from pathlib import Path

API_KEY = "REDACTED_API_KEY"
PROJECT_ID = "REDACTED_PROJECT_ID"

def load_creds():
    env_path = Path(__file__).resolve().parents[1] / ".secrets" / "creds.env"
    creds = {}
    for line in env_path.read_text().splitlines():
        if "=" in line:
            k, _, v = line.partition("=")
            creds[k.strip()] = v.strip()
    return creds

def http_json(url, payload, headers=None, method=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data else "GET"))
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return e.code, body

def sign_in(email, password):
    url = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={API_KEY}"
    code, resp = http_json(url, {"email": email, "password": password, "returnSecureToken": True})
    if code != 200:
        raise RuntimeError(f"signInWithPassword failed {code}: {resp}")
    return resp["idToken"], resp["localId"]

def epoch_millis(dt):
    return int((dt - datetime(1970, 1, 1, tzinfo=timezone.utc)).total_seconds() * 1000)

def tx_fields(uid, ttype, amount_egp, description, category, iso_date):
    dt = datetime.fromisoformat(iso_date).replace(tzinfo=timezone.utc)
    return {
        "userId": {"stringValue": uid},
        "type": {"stringValue": ttype},
        "amount": {"integerValue": str(int(amount_egp * 100))},
        "description": {"stringValue": description},
        "category": {"stringValue": category},
        "timestamp": {"integerValue": str(epoch_millis(dt))},
    }

def create_doc(uid, id_token, doc_id, fields):
    url = (f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}"
           f"/databases/(default)/documents/users/{urllib.parse.quote(uid, safe='')}"
           f"/transactions?documentId={doc_id}")
    code, resp = http_json(url, {"fields": fields}, headers={"Authorization": f"Bearer {id_token}"})
    return code, resp

def main():
    creds = load_creds()
    email = creds["primary_email"]
    password = creds["primary_password"]
    id_token, uid = sign_in(email, password)
    print(f"uid={uid}")

    # August 2026 dataset (mirrors the app's deterministic categories)
    rows = [
        ("a01", "EXPENSE", 4000, "Rent", "Housing", "2026-08-05T09:00:00+00:00"),
        ("a02", "EXPENSE", 1000, "Groceries", "Food", "2026-08-08T12:00:00+00:00"),
        ("a03", "EXPENSE", 500, "Restaurant", "Food", "2026-08-12T19:00:00+00:00"),
        ("a04", "EXPENSE", 150, "Taxi", "Transport", "2026-08-14T08:00:00+00:00"),
        ("a05", "EXPENSE", 300, "Internet", "Utilities", "2026-08-16T10:00:00+00:00"),
        ("a06", "EXPENSE", 50, "", "General", "2026-08-20T13:00:00+00:00"),
        ("a07", "INCOME", 7000, "Salary", "Salary", "2026-08-01T07:00:00+00:00"),
        ("a08", "INCOME", 1000, "Bonus", "Other Income", "2026-08-21T07:00:00+00:00"),
    ]
    for doc_id, ttype, amount, desc, cat, iso in rows:
        code, resp = create_doc(uid, id_token, doc_id, tx_fields(uid, ttype, amount, desc, cat, iso))
        ok = isinstance(resp, dict) and "name" in resp
        print(f"{doc_id} {ttype} {amount}EGP '{desc}' -> {code} {'OK' if ok else resp}")
        if not ok:
            sys.exit(1)

if __name__ == "__main__":
    main()