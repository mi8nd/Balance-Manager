"""Phase 1 E2E: sign in with Google using the controlled test account.

State machine over text nodes (vision-free). Idempotent: re-running resumes from
the current screen. Key rule: never relaunch the app while a Google Play Services
modal is mid-flow - reloading kills the in-flight sign-in.
"""

from __future__ import annotations

import os, sys, time

sys.path.insert(0, os.path.dirname(__file__))
import uidriver as u

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVIDENCE = os.path.join(BASE, "evidence")
STATE_DIR = os.path.join(EVIDENCE, "phase1_ui")
os.makedirs(EVIDENCE, exist_ok=True)
os.makedirs(STATE_DIR, exist_ok=True)

# A dedicated Google test account is used for the Google sign-in pass; it is
# personal data, so it is supplied through the environment and never committed.
EMAIL = os.environ.get("E2E_GOOGLE_EMAIL", "")
NAME = os.environ.get("E2E_GOOGLE_NAME", "")

LANDING = ("Let's Get Started", "Balance Manager", "Welcome Back",
           "Enter your total available balance", "Set up your initial balance")

LOG = os.path.join(EVIDENCE, "phase1_signin.log")


def log(msg: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


iter_no = 0


def scan(tag: str) -> tuple[str, list[u.Node]]:
    global iter_no
    iter_no += 1
    try:
        top, ns = u.state()
    except RuntimeError as e:
        log(f"  [{tag}] dump error: {e}")
        return "", []
    with open(os.path.join(STATE_DIR, f"iter_{iter_no:03d}_{tag}.xml"), "w",
              encoding="utf-8") as f:
        f.write(u.dump())
    texts = [f"{n.text}@{n.package}" for n in ns if n.text or n.desc]
    log(f"  [{tag}] top={top} texts={texts[:24]}")
    return top, ns


def main() -> int:
    deadline = time.monotonic() + 300
    last_action = ""
    while time.monotonic() < deadline:
        top, ns = scan("scan")
        joined = " | ".join(n.text for n in ns)
        gms_visible = top == "com.google.android.gms" and "Choose an account" in joined

        if gms_visible:
            log("GMS picker present -> tapping test account")
            try:
                u.tap_text(EMAIL)
            except RuntimeError:
                try:
                    u.tap_text(NAME)
                except RuntimeError as e:
                    log(f"  account row not found: {e}")
                    last_action = "picker-no-row"
                    continue
            log("  tapped account; waiting for sign-in to settle...")
            # Let GMS complete: poll without any relaunch.
            settled = wait_after_account_tap()
            if settled is not None:
                return 0
            log("  sign-in did not complete; re-entering loop")
            last_action = "picker-after"
            continue

        if any(k in joined for k in LANDING) and "Welcome back to Balance Manager" not in joined and "Start managing your finances today" not in joined:
            log(f"RESULT: landed on account home: {joined[:200]}")
            u.screenshot_local(os.path.join(EVIDENCE, "20-after-signin.png"))
            return 0

        if "Welcome back to Balance Manager" in joined:
            log("on Login screen -> tap Continue with Google")
            try:
                u.tap_text("Continue with Google")
                last_action = "google"
                time.sleep(6)
            except RuntimeError as e:
                log(f"  google button tap failed: {e}")
                time.sleep(3)
            continue

        if "Start managing your finances today" in joined:
            log("on SignUp screen -> tap Log in")
            try:
                u.tap_text("Log in")
                last_action = "login"
                time.sleep(4)
            except RuntimeError:
                u.swipe(360, 1300, 360, 500)
                time.sleep(2)
            continue

        if top == "dev.mobile.maestro.test":
            log("stray maestro harness -> relaunch app")
            u.launch_app()
            time.sleep(3)
            continue

        if top != u.APP and last_action:
            log(f"app not foreground (top={top}) -> relaunch")
            u.launch_app()
            time.sleep(3)
            continue

        log(f"unhandled state, waiting... {joined[:120]}")
        time.sleep(4)

    log("RESULT: TIMEOUT during google sign-in")
    return 1


def wait_after_account_tap() -> str | None:
    """After tapping the account row, GMS may show a short interstitial and the
    app a loading/dashboard. Watch the screen for up to ~90s without relaunching.
    Returns the landing text if we reached an account home, else None."""
    deadline = time.monotonic() + 90
    seen_continue = False
    while time.monotonic() < deadline:
        top, ns = scan("settle")
        joined = " | ".join(n.text for n in ns)
        if any(k in joined for k in LANDING) and "Start managing your finances today" not in joined:
            log(f"RESULT: landed on account home: {joined[:200]}")
            u.screenshot_local(os.path.join(EVIDENCE, "20-after-signin.png"))
            return joined
        if "Welcome back to Balance Manager" in joined or "Start managing your finances today" in joined:
            return None
        if "Continue" in [n.text for n in ns] and not seen_continue and ("Signing in" in joined or "Confirm" in joined or "Just a sec" in joined):
            seen_continue = True
            try:
                u.tap_text("^Continue$")
                log("  tapped GMS Continue")
            except RuntimeError:
                pass
        time.sleep(4)
    return None


if __name__ == "__main__":
    if not EMAIL or not NAME:
        raise SystemExit(
            "Set E2E_GOOGLE_EMAIL and E2E_GOOGLE_NAME to the Google test account "
            "before running this pass (they are personal data and are not committed)."
        )
    sys.exit(main())