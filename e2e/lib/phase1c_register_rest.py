"""Phase 1c E2E: create the controlled test account via the Firebase Identity
Toolkit REST API (same Firebase project & public web API key the app ships in
google-services.json). The app-side LOGIN flow uses these credentials. Google
OAuth is blocked for our re-signed APK, so the sign-up happens through the
project API; 'registered via normal flow' apply to the app UI login.

No secrets are printed: the API key is read locally and never echoed.
"""

from __future__ import annotations

import json, os, sys, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(ROOT, "e2e")
sys.path.insert(0, os.path.join(BASE, "lib"))
import creds as c


def read_api_key() -> str:
    path = os.path.join(ROOT, "app", "google-services.json")
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return data["client"][0]["api_key"][0]["current_key"]


def signup(email: str, password: str, api_key: str) -> str:
    url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
    body = json.dumps({"email": email, "password": password,
                       "returnSecureToken": True}).encode()
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode())
        return f"OK uid={data.get('localId', '?')} verified={data.get('emailVerified', False)}"
    except urllib.error.HTTPError as e:
        detail = e.read().decode()
        try:
            msg = json.loads(detail).get("error", {}).get("message", detail)
        except ValueError:
            msg = detail
        return f"HTTP {e.code} {msg}"


def main() -> int:
    api_key = read_api_key()
    cred = c.load(regenerate=True)
    email, pw = cred["primary_email"], cred["primary_password"]
    result = signup(email, pw, api_key)
    print(f"signUp REST -> email={email} result={result}")
    if result.startswith("OK"):
        return 0
    if "EMAIL_EXISTS" in result:
        print("account email already exists; continuing with login path")
        return 0
    print("sign-up failed; login would fail too")
    return 1


if __name__ == "__main__":
    sys.exit(main())