import json, os, re, sys, urllib.request, urllib.error

sys.path.insert(0, os.path.dirname(__file__))
import creds

KEY = creds.firebase_web_api_key()
CERT = creds.firebase_cert_hash()
PKG = "com.personal.balance"

_local = creds.load()
addr = sys.argv[1] if len(sys.argv) > 1 else _local["primary_email"]
pw = sys.argv[2] if len(sys.argv) > 2 else _local["primary_password"]


def call(url, body=None, tok=None, android=False):
    h = {"Content-Type": "application/json"}
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    if android:
        h["X-Android-Package"] = PKG
        h["X-Android-Cert"] = CERT
    req = urllib.request.Request(url, data=(json.dumps(body).encode() if body is not None else None),
                                 method=("POST" if body is not None else "GET"), headers=h)
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400]


s, b = call("https://api.mail.tm/token", {"address": addr, "password": pw})
tok = json.loads(b)["token"]
s, b = call("https://api.mail.tm/messages", tok=tok)
msgs = json.loads(b)["hydra:member"]
mid = [m["id"] for m in msgs if "verify" in (m.get("subject") or "").lower()]
print("verify mails:", len(mid))
s, b = call(f"https://api.mail.tm/messages/{mid[0]}", tok=tok)
d = json.loads(b)
body = d.get("html") or d.get("text") or ""
body = " ".join(body) if isinstance(body, list) else body
link = re.search(r"https?://[^\s\"'<>]+auth/action[^\s\"'<>]+", body).group(0).replace("&amp;", "&")
oob = re.search(r"[?&]oobCode=([^&]+)", link).group(1)
print("oobCode len", len(oob))
s, b = call(f"https://identitytoolkit.googleapis.com/v1/accounts:update?key={KEY}",
            {"oobCode": oob}, android=True)
print("apply", s, b[:260])
