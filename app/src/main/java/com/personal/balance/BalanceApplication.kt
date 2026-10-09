package com.personal.balance

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

/**
 * Application entry point. Initializes Firebase and installs Firebase App Check
 * before any Firebase AI Logic request can be made.
 *
 * reCAPTCHA Enterprise is the sole App Check provider for both debug and
 * release builds. There is no Debug provider, no debug token, no debug-token
 * registration workflow, and no Play Integrity provider. The App Check
 * reCAPTCHA site key is a public client configuration value supplied through
 * the Android string resource `res/values/appcheck.xml`
 * (`R.string.app_check_recaptcha_site_key`) — it is never hardcoded in code.
 *
 * If the site key resource is empty, the provider is NOT installed (an empty
 * key would break the reCAPTCHA client) and a warning is logged; Firebase AI
 * Logic requests then fail with `AiResult.Error.AppCheckFailure`, keeping App
 * Check enforcement from being silently bypassed while the key is unset.
 */
class BalanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        val siteKey = getString(R.string.app_check_recaptcha_site_key)
        if (siteKey.isBlank()) {
            Log.w(TAG, "Firebase initialized; App Check provider=recaptcha-enterprise but site key is not configured (set R.string.app_check_recaptcha_site_key in res/values/appcheck.xml); AI requests will fail with AppCheckFailure until it is set")
        } else {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                RecaptchaAppCheckProviderFactory.getInstance(siteKey)
            )
            Log.i(TAG, "Firebase initialized; App Check provider=recaptcha-enterprise")
        }
    }

    private companion object {
        const val TAG = "BalanceApplication"
    }
}