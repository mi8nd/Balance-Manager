"""QA: drive the SECONDARY controlled account through in-app email/password
sign-up, mail.tm verification, and onboarding to the dashboard.

Resumable stage machine: on every scan it decides the next action from the
current screen, exactly like phase1b but parameterized for the secondary creds
and WITHOUT clearing app data or touching the primary account.

Stages: ready -> (sign up | verify | onboarding | dashboard) -> RESULT.
"""

from __future__ import annotations

import os, sys, time

sys.path.insert(0, os.path.dirname(__file__))
import uidriver as u
import creds as c
from phase1b_signup_email import (
    fill_signup, verify_by_link, set_value_field, texts_now, log,
)

EVIDENCE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "evidence")
os.makedirs(EVIDENCE, exist_ok=True)
LOG = os.path.join(EVIDENCE, "qa_secondary_signup.log")


def log(msg: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def main() -> int:
    cred = c.load()
    email = cred.get("secondary_email")
    pw = cred.get("secondary_password")
    balance = cred.get("secondary_initial_balance", "5000")
    if not email:
        print("no secondary_email in creds", flush=True)
        return 2
    log(f"secondary email={email}")

    deadline = time.monotonic() + 280
    stage = "ready"
    while time.monotonic() < deadline:
        u.launch_app()
        time.sleep(2)
        ts = texts_now("scan")
        joined = " | ".join(ts)
        log(f"  stage={stage} joined={joined[:140]}")

        if stage == "ready":
            if "Start managing your finances today" in joined:
                stage = "fill_signup"
                continue
            if "Verify Your Email" in joined or "Verify Email" in joined:
                stage = "post_register"
                continue
            if "Enter your total available balance" in joined or "Continue to Dashboard" in joined:
                stage = "onboarding_balance"
                continue
            if "Welcome back to Balance Manager" in joined:
                try:
                    u.tap_text("Sign up")
                    log("  tapped Sign up")
                except RuntimeError as e:
                    log(f"  tap Sign up failed: {e}")
                time.sleep(3)
                continue
            if "Balance Manager" in joined:
                log(f"RESULT: already on dashboard {joined[:120]}")
                return 0
            if "Account Already Exists" in joined:
                log("RESULT: secondary already exists; this run should not happen")
                # Dismiss and continue; caller decides.
                try:
                    u.tap_text("Dismiss")
                except RuntimeError:
                    pass
                time.sleep(2)
                continue
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
                log(f"  fill failed: {e}")
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
            ts2 = texts_now("postreg")
            j2 = " | ".join(ts2)
            if "Verify Your Email" in j2 or "Verify Email" in j2:
                log("  verify-email gate reached")
                if verify_by_link(email, pw):
                    log("  verification applied")
                else:
                    log("  verification NOT applied yet; will retry later")
                time.sleep(4)
                try:
                    u.tap_text("I've Verified - Continue")
                    log("  tapped 'I've Verified - Continue'")
                except RuntimeError:
                    log("  button not found yet, will retry")
                time.sleep(4)
                continue
            if "Enter your total available balance" in j2 or "Continue to Dashboard" in j2 or "Let's Get Started" in j2:
                stage = "onboarding_balance"
                continue
            if "Balance Manager" in j2:
                log(f"RESULT: dashboard reached {j2[:120]}")
                return 0
            if "Welcome back to Balance Manager" in j2:
                log("  bounced to login screen; switching to ready")
                stage = "ready"
                continue
            time.sleep(4)
            continue

        if stage == "onboarding_balance":
            ts2 = texts_now("onb")
            j2 = " | ".join(ts2)
            if "Balance Manager" in j2:
                log(f"RESULT: dashboard reached {j2[:120]}")
                return 0
            if "Enter your total available balance" in j2 or "0.00" in j2:
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
            time.sleep(4)
            continue

        if stage == "dashboard":
            ts2 = texts_now("dash")
            j2 = " | ".join(ts2)
            if "Balance Manager" in j2 or "Balance" in j2:
                log(f"RESULT: on dashboard {j2[:160]}")
                u.screenshot_local(os.path.join(EVIDENCE, "50-secondary-dashboard.png"))
                return 0
            time.sleep(4)
            continue

    log("RESULT: TIMEOUT in secondary sign-up")
    return 1


if __name__ == "__main__":
    sys.exit(main())