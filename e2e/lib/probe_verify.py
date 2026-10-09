import json, re, sys, urllib.request, urllib.error

KEY = "REDACTED_API_KEY"
CERT = "1022B0D0D0AA91C15906677F40244F973CD51CDA"
PKG = "com.personal.balance"

addr = sys.argv[1] if len(sys.argv) > 1 else "e2eqa20260917080349@uberip.com"
pw = sys.argv[2] if len(sys.argv) > 2 else "RUNTIME_PASSWORD"


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
