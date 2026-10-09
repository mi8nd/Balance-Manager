import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Local-only release signing. Secrets live in the Git-ignored app/keystore.properties
// (storePassword/keyPassword/keyAlias) + app/keystore/balance-release.jks. Nothing is
// hardcoded here. If the file is absent (e.g. a fresh clone), release builds fall back
// to an unsigned APK and debug/test builds are unaffected — the user regenerates this
// file locally with the stable keystore.
val releaseSigningProps = file("keystore.properties")

android {
    namespace = "com.personal.balance"
    compileSdk = 34

    signingConfigs {
        if (releaseSigningProps.exists()) {
            create("release") {
                val props = Properties().apply {
                    releaseSigningProps.inputStream().use { load(it) }
                }
                storeFile = file(props.getProperty("storeFile", "keystore/balance-release.jks"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias", "balance")
                keyPassword = props.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.personal.balance"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningProps.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Firebase BoM
    // Bumped 32.8.0 -> 34.18.0 to obtain firebase-ai (Firebase AI Logic) and the
    // current App Check artifacts. auth and firestore move to the versions mapped
    // by this BoM (24.2.0 / 26.6.0); their public behavior is unchanged.
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Firebase AI Logic (Gemini Developer API, no-cost on Spark) + App Check (reCAPTCHA Enterprise — sole provider)
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-recaptcha")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // DataStore for theme persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
