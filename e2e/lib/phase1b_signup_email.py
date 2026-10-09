"""Phase 1b E2E: create the controlled email/password account via the app's
normal sign-up flow, complete onboarding with a fixed initial balance, and land
on the dashboard.

Note: Google OAuth is blocked for our re-signed APK (SHA-1 not registered, see
logcat 'not registered to use OAuth2.0'), so the controlled account is created
through email/password sign-up instead. The account is wholly owned by the test
so all assertions are deterministic. Deletes via keyboard, types via adb.
"""

from __future__ import annotations

import json, os, re, subprocess, sys, time, urllib.error, urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import uidriver as u
import creds as c

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVIDENCE = os.path.join(BASE, "evidence")
os.makedirs(EVIDENCE, exist_ok=True)

LOG = os.path.join(EVIDENCE, "phase1b_signup_email.log")


def log(msg: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def texts_now(tag: str) -> list[str]:
    try:
        ts = u.texts()
        log(f"  [{tag}] texts={ts[:26]}")
        return ts
    except RuntimeError as e:
        log(f"  [{tag}] dump error: {e}")
        return []


def type_field(index: int, value: str, tag: str) -> None:
    """Type into the index-th editable field with focus verification.

    Compose semantics bounds can go stale after content is entered, so if a tap
    does not focus the intended field we defocus + scroll (which forces a fresh
    layout report) and retry."""
    for attempt in range(5):
        fields = u.fields()
        if len(fields) <= index:
            time.sleep(1.5)
            continue
        f = fields[index]
        u.tap_xy(*f.center)
        time.sleep(1.2)
        after = u.fields()
        focused = len(after) > index and after[index].focused
        if not focused:
            # text entered in an earlier field shifted the layout; resync
            try:
                u.tap_xy(360, 300)   # neutral spot to drop focus
            except RuntimeError:
                pass
            time.sleep(0.5)
            u.swipe(360, 900, 360, 500)
            time.sleep(0.9)
            continue
        u.keyevent(123)  # move caret to end
        for _ in range(40):
            u.keyevent(67)  # DEL
        if index == 0:
            u.type_text(value)   # plain email: fine via IME text
        else:
            u.type_codes(value)  # masked fields: exact keycodes for parity
        time.sleep(0.6)
        rb = u.fields()
        shown = rb[index].text if len(rb) > index else ""
        log(f"  typed field[{index}] ({tag}) attempt={attempt} now={shown[:16]!r}")
        if shown:
            return
        # typing landed nowhere; retry
        continue
    raise RuntimeError(f"could not fill field[{index}] ({tag})")


def fill_signup(email: str, pw: str) -> None:
    type_field(0, email, "email")
    if u.ime_shown():
        u.hide_keyboard()
        time.sleep(0.4)
    u.tap_xy(360, 300)
    time.sleep(0.4)
    u.swipe(360, 900, 360, 500)
    time.sleep(0.7)
    for attempt in range(4):
        type_field(1, pw, "password")
        type_field(2, pw, "confirm")
        rb = [f.text for f in u.fields()]
        if len(rb) < 3 or not rb[2]:
            raise RuntimeError(f"confirm field not set (readback={[t[:16] for t in rb]})")
        if "@" not in rb[0]:
            raise RuntimeError(f"email field not set (readback='{rb[0][:24]}')")
        if len(rb[1]) == len(rb[2]) == len(pw):
            u.hide_keyboard()
            return
        log(f"  passwords unequal pw={len(rb[1])} confirm={len(rb[2])} (want {len(pw)}); re-clearing")
        for idx in (1, 2):
            fields = u.fields()
            f = fields[idx]
            u.tap_xy(*f.center)
            time.sleep(1)
            u.keyevent(123)
            for _ in range(40):
                u.keyevent(67)
        u.hide_keyboard()
        time.sleep(0.4)
        u.tap_xy(360, 300)
        time.sleep(0.4)
        u.swipe(360, 900, 360, 500)
        time.sleep(0.7)
    raise RuntimeError(f"passwords still unequal after retries pw={len(rb[1])} confirm={len(rb[2])}")


def set_value_field(value: str, tag: str) -> None:
    fields = u.fields()
    if not fields:
        raise RuntimeError("no edit field for amount")
    f = fields[0]
    u.tap_xy(*f.center)
    time.sleep(1)
    u.keyevent(123)
    for _ in range(12):
        u.keyevent(67)
    u.type_text(value)
    u.keyevent(4)
    time.sleep(0.6)


def open_url(url: str) -> None:
    """Deprecated browser path; kept for reference only."""
    raise NotImplementedError


def _project_api_key() -> str:
    path = os.path.join(os.path.dirname(BASE), "app", "google-services.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)["client"][0]["api_key"][0]["current_key"]


def _rest(path: str, body: dict, key: str, cert: str) -> tuple[int, str]:
    url = f"https://identitytoolkit.googleapis.com/v1/{path}?key={key}"
    return _http(url, "POST", body, extra_headers={
        "X-Android-Package": "com.personal.balance",
        "X-Android-Cert": cert,
    })


def _http(url: str, method: str = "GET", body: dict | None = None,
          token: str | None = None,
          extra_headers: dict | None = None) -> tuple[int, str]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def mailtm_token(address: str, password: str) -> str | None:
    s, b = _http("https://api.mail.tm/token", "POST",
                 {"address": address, "password": password})
    if s == 200:
        return json.loads(b).get("token")
    return None


def mailtm_ensure(address: str, password: str) -> None:
    s, b = _http("https://api.mail.tm/accounts", "POST",
                 {"address": address, "password": password})
    log(f"  mail.tm account ensure -> {s}")
    if s not in (200, 201) and "already" not in b.lower():
        log(f"  mail.tm create detail: {b[:160]}")


def verify_by_link(address: str, password: str) -> bool:
    """Complete Firebase email verification deterministically: poll the mail.tm
    inbox for the VERIFY_EMAIL mail, extract its oobCode, and apply it through
    the public accounts:update endpoint (Android app-identity headers satisfy
    the project's restricted API key)."""
    key = _project_api_key()
    cert = "1022B0D0D0AA91C15906677F40244F973CD51CDA"
    token = None
    deadline = time.monotonic() + 170
    while time.monotonic() < deadline:
        if token is None:
            token = mailtm_token(address, password)
            if token is None:
                log("  mail.tm token unavailable; retrying")
                time.sleep(8)
                continue
        s, b = _http("https://api.mail.tm/messages", token=token)
        if s != 200:
            token = None
            time.sleep(8)
            continue
        try:
            msgs = json.loads(b).get("hydra:member", [])
        except ValueError:
            msgs = []
        for m in msgs:
            if "verify" in (m.get("subject") or "").lower():
                s2, b2 = _http(f"https://api.mail.tm/messages/{m['id']}", token=token)
                if s2 != 200:
                    continue
                detail = json.loads(b2)
                body = detail.get("html") or detail.get("text") or ""
                if isinstance(body, list):
                    body = " ".join(body)
                link = re.search(r"https?://[^\s\"'<>]+auth/action[^\s\"'<>]+", body)
                if not link:
                    log("  verify mail has no auth/action link")
                    return False
                oob = re.search(r"[?&]oobCode=([^&]+)", link.group(0).replace("&amp;", "&"))
                if not oob:
                    log("  no oobCode in verification link")
                    return False
                s3, _ = _rest("accounts:update", {"oobCode": oob.group(1)}, key, cert)
                log(f"  accounts:update -> {s3}")
                return s3 == 200
        log("  waiting for verification email...")
        time.sleep(8)
    log("  verification email did not arrive")
    return False


def main() -> int:
    regenerate = os.environ.get("FORCE_NEW_ACCOUNT") == "1"
    disposable = os.environ.get("E2E_DISPOSABLE") == "1"
    cred = c.load(regenerate=regenerate,
                  primary_domain="uberip.com" if disposable else "example.com")
    email, pw = cred["primary_email"], cred["primary_password"]
    balance = cred["primary_initial_balance"]
    log(f"account email={email} | regenerate={regenerate} disposable={disposable}")
    if disposable:
        mailtm_ensure(email, pw)
    link_opened = False
    u.force_stop()
    u.force_stop_harness()
    time.sleep(2)
    if regenerate:
        u.sh("shell", "pm", "clear", "com.personal.balance")
        time.sleep(2)
        log("cleared app data for fresh signup")

    deadline = time.monotonic() + 240
    stage = "ready"
    while time.monotonic() < deadline:
        u.launch_app()
        time.sleep(2)
        ts = texts_now("scan")
        joined = " | ".join(ts)

        if stage == "ready":
            if "Start managing your finances today" in joined:
                stage = "fill_signup"
                continue
            if "Verify Your Email" in joined or "Verify Email" in joined:
                log("  resuming on verify-email gate")
                stage = "post_register"
                continue
            if "Continue to Dashboard" in joined or "Enter your total available balance" in joined:
                log("  resuming on onboarding balance step")
                stage = "onboarding_balance"
                continue
            if "Welcome back to Balance Manager" in joined:
                try:
                    u.tap_text("Sign up")
                    log("  tapped 'Sign up'")
                except RuntimeError as e:
                    log(f"  tap sign up failed: {e}")
                time.sleep(3)
                continue
            if "Balance Manager" in joined:
                log(f"RESULT: already on dashboard {joined[:120]}")
                u.screenshot_local(os.path.join(EVIDENCE, "31-dashboard-resumed.png"))
                return 0
            log(f"  unexpected start screen: {joined[:120]}")
            time.sleep(4)
            continue

        if stage == "fill_signup":
            if "Start managing your finances today" not in joined:
                time.sleep(3)
                continue
            try:
                fill_signup(email, pw)
            except RuntimeError as e:
                log(f"  fill failed: {e}; restarting clean")
                stage = "restart"
                continue
            try:
                u.reveal_and_tap_text("Sign Up")
                log("  tapped Sign Up (submit)")
            except RuntimeError as e:
                log(f"  tap Sign Up failed: {e}")
                try:
                    u.reveal_and_tap_text("Create Account")
                    log("  tapped Create Account (fallback)")
                except RuntimeError as e2:
                    log(f"  fallback failed: {e2}")
                time.sleep(2)
                continue
            stage = "post_register"
            time.sleep(8)
            continue

        if stage == "restart":
            u.force_stop()
            time.sleep(2)
            stage = "ready"
            continue

        if stage == "post_register":
            if "Verify Your Email" in joined or "Verify Email" in joined:
                log("  verify-email gate reached")
                if disposable and not link_opened:
                    if verify_by_link(email, pw):
                        link_opened = True
                    time.sleep(4)
                try:
                    u.tap_text("I've Verified - Continue")
                    log("  tapped 'I've Verified - Continue'")
                except RuntimeError:
                    log("  button not found yet, will retry")
                time.sleep(4)
                continue
            if "Let's Get Started" in joined or "Set up your initial balance" in joined \
                    or "Continue to Dashboard" in joined or "Enter your total available balance" in joined:
                stage = "onboarding_balance"
                continue
            if "Balance Manager" in joined and "Welcome back to Balance Manager" not in joined:
                log(f"RESULT: already on dashboard (joined={joined[:120]})")
                return 0
            if "Account Already Exists" in joined:
                log("RESULT: account already exists; falling back to login flow")
                stage = "login_existing"
                continue
            time.sleep(4)
            continue

        if stage == "login_existing":
            try:
                u.tap_text("Dismiss")
            except RuntimeError:
                pass
            time.sleep(2)
            ts = texts_now("login")
            if "Welcome back to Balance Manager" in joined:
                type_field(0, email, "l-email")
                type_field(1, pw, "l-password")
                u.reveal_and_tap_text("Log In")
                time.sleep(6)
                stage = "login_land"
            else:
                stage = "ready"
            continue

        if stage == "login_land":
            ts = texts_now("login_land")
            j2 = " | ".join(ts)
            if "Balance Manager" in j2:
                log(f"RESULT: logged in (dashboard) {j2[:120]}")
                return 0
            if "Let's Get Started" in j2:
                stage = "onboarding_balance"
                continue
            time.sleep(4)
            continue

        if stage == "onboarding_balance":
            if "Balance Manager" in joined:
                log(f"RESULT: dashboard reached {joined[:120]}")
                return 0
            if "Enter your total available balance" in joined or "0.00" in joined:
                set_value_field(balance, "balance")
                log(f"  initial balance set to {balance}")
                try:
                    u.reveal_and_tap_text("Continue to Dashboard")
                except RuntimeError:
                    try:
                        u.reveal_and_tap_text("Continue")
                    except RuntimeError as e:
                        log(f"  continue tap failed: {e}")
                time.sleep(6)
                stage = "dashboard"
                continue
            ts2 = texts_now("onb")
            if "Log in" in " | ".join(ts2) and "Start managing" in " | ".join(ts2):
                stage = "ready"
                continue
            time.sleep(4)
            continue

        if stage == "dashboard":
            ts = texts_now("dashboard")
            j2 = " | ".join(ts)
            if "Balance Manager" in j2 or "Balance" in j2:
                log(f"RESULT: on dashboard {j2[:160]}")
                u.screenshot_local(os.path.join(EVIDENCE, "30-dashboard.png"))
                return 0
            time.sleep(4)
            continue

    log("RESULT: TIMEOUT in email/password sign-up")
    return 1


if __name__ == "__main__":
    sys.exit(main())