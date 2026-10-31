"""Create a fresh throwaway account via the app's email flow: mail.tm inbox +
REST signUp, verify email, then the app drives onboarding. Keeps PRIMARY intact.

Modes:
  --new        : create++verify a brand-new throwaway account, print creds line
  --signin-ok  : after app sign-in, confirm dashboard reached (log only)
"""
from __future__ import annotations
import json, os, re, sys, time, urllib.error, urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE)
from lib import uidriver as u
sys.path.insert(0, os.path.join(BASE, "lib"))
import phase1b_signup_email as p  # reuse mail.tm + credentials helpers
import creds  # local, git-ignored Firebase config + runtime passwords

REST_BASE = "https://identitytoolkit.googleapis.com/v1/accounts:"

def api_key() -> str:
    return creds.firebase_web_api_key()

def rest(path, body, token=None, extra=None):
    url = "https://identitytoolkit.googleapis.com/v1/accounts:" + path + "?key=" + api_key()
    headers = {"Content-Type": "application/json"}
    if extra: headers.update(extra)
    if token: headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

ANDROID_EXTRA = {"X-Android-Package": "com.personal.balance",
                 "X-Android-Cert": creds.firebase_cert_hash()}


def verify_via_oob(email: str, pw: str) -> bool:
    """Firebase only sends the VERIFY_EMAIL mail when requested (app calls
    sendVerificationEmail). Replicate that: signIn -> sendOobCode(VERIFY_EMAIL)
    -> poll mail.tm for oobCode -> accounts:update. Returns verified bool."""
    s, b = rest("signInWithPassword", {"email": email, "password": pw,
                                       "returnSecureToken": True}, extra=ANDROID_EXTRA)
    if s != 200:
        print(f"[signIn] {s} {b[:120]}")
        return False
    idtoken = json.loads(b)["idToken"]
    s2, b2 = rest("sendOobCode", {"requestType": "VERIFY_EMAIL", "idToken": idtoken},
                  extra=ANDROID_EXTRA)
    print(f"[sendOobCode] {s2} {b2[:120]}")
    if s2 != 200:
        return False
    key = api_key()
    deadline = time.monotonic() + 170
    token = None
    while time.monotonic() < deadline:
        if token is None:
            token = p.mailtm_token(email, pw)
            if token is None:
                time.sleep(8)
                continue
        s3, b3 = p._http("https://api.mail.tm/messages", token=token)
        if s3 != 200:
            token = None
            time.sleep(8)
            continue
        try:
            msgs = json.loads(b3).get("hydra:member", [])
        except ValueError:
            msgs = []
        for m in msgs:
            if "verify" in (m.get("subject") or "").lower():
                s4, b4 = p._http(f"https://api.mail.tm/messages/{m['id']}", token=token)
                if s4 != 200:
                    continue
                detail = json.loads(b4)
                body = detail.get("html") or detail.get("text") or ""
                if isinstance(body, list):
                    body = " ".join(body)
                link = re.search(r"https?://[^\s\"'<>]+auth/action[^\s\"'<>]+", body)
                if not link:
                    print("  verify mail has no auth/action link")
                    return False
                oob = re.search(r"[?&]oobCode=([^&]+)", link.group(0).replace("&amp;", "&"))
                if not oob:
                    print("  no oobCode in verification link")
                    return False
                s5, _ = rest("update", {"oobCode": oob.group(1)}, extra=ANDROID_EXTRA)
                print(f"[accounts:update] {s5} oob={oob.group(1)[:12]}...")
                return s5 == 200
        time.sleep(8)
    print("  verification email did not arrive")
    return False


def new_throwaway():
    stamp = time.strftime("%Y%m%d%H%M%S")
    email = f"throwqa{stamp}@uberip.com"
    pw = creds.random_password()
    p.mailtm_ensure(email, pw)
    s, b = rest("signUp", {"email": email, "password": pw, "returnSecureToken": True},
                extra=ANDROID_EXTRA)
    print(f"[signUp] {s} {b[:200]}")
    if s != 200:
        raise SystemExit("signUp failed")
    if verify_via_oob(email, pw):
        print("EMAIL_VERIFIED")
    else:
        print("WARN email not verified")
    print(f"THROWAWAY email={email} password={pw}")
    return email, pw

def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--new"
    if mode == "--new":
        new_throwaway()
        return 0
    print("unknown mode", mode)
    return 2

if __name__ == "__main__":
    sys.exit(main())