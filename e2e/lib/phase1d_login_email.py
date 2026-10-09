"""Phase 1d E2E: log into the app UI with the REST-created controlled account
and land on the Dashboard (handling the verify-email gate and initial-balance
setup). Evidence: staged UI dumps + a dashboard screenshot.
"""

from __future__ import annotations

import os, sys, time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(BASE, "lib"))
import uidriver as u
import creds as c
from phase1b_signup_email import type_field, log

PKG = "com.personal.balance"
EVID = os.path.join(BASE, "evidence", "phase1d_login_email")

def state() -> str:
    ts = time.strftime("%H%M%S")
    texts = u.texts()
    joined = " | ".join(texts)
    if "Continue to Dashboard" in joined:
        return "initial_balance"
    if "I've Verified - Continue" in joined:
        return "verify_gate"
    if "Email not verified" in joined:
        return "email_not_verified"
    if "Welcome back to Balance Manager" in joined:
        return "login"
    if "Statistics" in joined or "R$" in joined or "Recent Activity" in joined:
        return "dashboard"
    if "Sign Up" in joined or "Create Account" in joined:
        return "signup"
    return "unknown"

def main() -> int:
    os.makedirs(EVID, exist_ok=True)
    cred = c.load(regenerate=False)
    email, pw = cred["primary_email"], cred["primary_password"]
    initial = cred["primary_initial_balance"]
    log(f"account email={email} initial={initial}")

    u.force_stop(PKG)
    u.launch_app(PKG)
    time.sleep(6)

    stage = "start"
    for i in range(30):
        st = state()
        ts = time.strftime("%H%M%S")
        if st != stage:
            log(f"[{ts}] stage: {stage} -> {st}")
            stage = st
        if st == "dashboard":
            time.sleep(2)
            u.screenshot_local(os.path.join(EVID, "dashboard.png"))
            log(f"[{ts}] DASHBOARD REACHED")
            return 0
        if st == "login":
            type_field(0, email, "email")
            time.sleep(0.5)
            type_field(1, pw, "password")
            time.sleep(0.5)
            u.hide_keyboard()
            time.sleep(0.5)
            try:
                u.reveal_and_tap_text("Log In")
                log("  tapped Log In")
            except RuntimeError as e:
                log(f"  Log In tap failed: {e}; waiting")
            time.sleep(6)
        elif st in ("verify_gate", "email_not_verified"):
            u.reveal_and_tap_text("Continue")
            log("  tapped I've Verified - Continue")
            time.sleep(5)
        elif st == "initial_balance":
            try:
                type_field(0, initial, "balance")
                log(f"  set initial balance {initial}")
            except RuntimeError as e:
                log(f"  balance set failed: {e}")
            u.hide_keyboard()
            time.sleep(0.6)
            try:
                u.reveal_and_tap_text("Continue to Dashboard")
                log("  tapped Continue to Dashboard")
            except RuntimeError as e:
                log(f"  continue tap failed: {e}")
            time.sleep(6)
        elif st == "signup":
            u.reveal_and_tap_text("Log in")
            time.sleep(4)
        else:
            time.sleep(2)
        if i == 15:
            u.screenshot_local(os.path.join(EVID, "mid_loop.png"))

    u.screenshot_local(os.path.join(EVID, "timeout.png"))
    log("RESULT: TIMEOUT reaching dashboard")
    return 1

if __name__ == "__main__":
    sys.exit(main())
