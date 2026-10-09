"""QA helper: apply the Firebase VERIFY_EMAIL oobCode for the primary account."""
from __future__ import annotations

import os, sys, time

sys.path.insert(0, os.path.dirname(__file__))
import creds as c
from phase1b_signup_email import verify_by_link, log

cred = c.load(regenerate=False)
email, pw = cred["primary_email"], cred["primary_password"]
log(f"verifying primary={email}")
ok = verify_by_link(email, pw)
log(f"verify result={ok}")
sys.exit(0 if ok else 1)