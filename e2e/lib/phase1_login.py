"""Reusable E2E login flow: brings the app to the Dashboard for the controlled
account, handling the signup screen (if present), the login screen, the
verify-email gate, and initial-balance onboarding.

Usage:  python phase1_login.py            # primary account, expects verified
        E2E_DISPOSABLE=1 python ...       # also completes verification if gated
"""

from __future__ import annotations

import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import uidriver as u
import creds as c
from phase1b_signup_email import type_field, log, verify_by_link, mailtm_ensure

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVIDENCE = os.path.join(BASE, "evidence")
os.makedirs(EVIDENCE, exist_ok=True)
PKG = "com.personal.balance"


def screen(joined: str) -> str:
    if "Continue to Dashboard" in joined or "Enter your total available balance" in joined:
        return "onboarding"
    if "Verify Your Email" in joined or "Verify Email" in joined:
        return "gate"
    if "Welcome back to Balance Manager" in joined:
        return "login"
    if "Start managing your finances today" in joined:
        return "signup"
    if "Balance Manager" in joined:
        return "dashboard"
    return "unknown"


def run(email: str, pw: str, initial: str, disposable: bool) -> int:
    link_opened = False
    deadline = time.monotonic() + 300
    last = None
    while time.monotonic() < deadline:
        texts = u.texts()
        joined = " | ".join(texts)
        st = screen(joined)
        if st != last:
            log(f"screen: {st} | {joined[:140]}")
            last = st
        if st == "dashboard":
            u.screenshot_local(os.path.join(EVIDENCE, "40-dashboard.png"))
            log("RESULT: dashboard reached")
            return 0
        if st == "login":
            type_field(0, email, "email")
            type_field(1, pw, "password")
            u.hide_keyboard()
            time.sleep(0.5)
            try:
                u.reveal_and_tap_text("Log In")
                log("tapped Log In")
            except RuntimeError as e:
                log(f"Log In tap failed: {e}")
            time.sleep(7)
        elif st == "signup":
            try:
                u.reveal_and_tap_text("Log in")
                log("tapped 'Log in' link")
            except RuntimeError as e:
                log(f"login-link tap failed: {e}")
            time.sleep(4)
        elif st == "gate":
            if disposable and not link_opened:
                log("gate: completing verification via REST")
                if verify_by_link(email, pw):
                    link_opened = True
                time.sleep(3)
            try:
                u.reveal_and_tap_text("I've Verified - Continue")
                log("tapped I've Verified - Continue")
            except RuntimeError as e:
                log(f"gate tap failed: {e}")
            time.sleep(5)
        elif st == "onboarding":
            try:
                type_field(0, initial, "balance")
                log(f"set initial balance {initial}")
            except RuntimeError as e:
                log(f"balance set failed: {e}")
            u.hide_keyboard()
            time.sleep(0.6)
            try:
                u.reveal_and_tap_text("Continue to Dashboard")
                log("tapped Continue to Dashboard")
            except RuntimeError as e:
                log(f"continue tap failed: {e}")
            time.sleep(6)
        else:
            time.sleep(2)
    u.screenshot_local(os.path.join(EVIDENCE, "40-timeout.png"))
    d = u.dump()
    with open(os.path.join(EVIDENCE, "40-timeout.xml"), "w", encoding="utf-8") as f:
        f.write(d)
    log("RESULT: TIMEOUT reaching dashboard")
    return 1


def main() -> int:
    disposable = os.environ.get("E2E_DISPOSABLE") == "1"
    cred = c.load(regenerate=False)
    email, pw = cred["primary_email"], cred["primary_password"]
    initial = cred["primary_initial_balance"]
    log(f"login as {email} (disposable={disposable})")
    if disposable:
        mailtm_ensure(email, pw)
    u.force_stop()
    u.force_stop_harness()
    time.sleep(1)
    u.launch_app()
    time.sleep(6)
    return run(email, pw, initial, disposable)


if __name__ == "__main__":
    sys.exit(main())
