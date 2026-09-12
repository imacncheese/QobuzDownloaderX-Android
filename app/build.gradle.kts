plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qbdlx.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qbdlx.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.8.1"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A local signing key is generated on first build so the project is
    // buildable straight after a clone. The keystore is NOT committed: shipping
    // an APK signing key with a published password would let anyone sign an
    // update for this application id.
    signingConfigs {
        create("local") {
            val ks = rootProject.file("keystore/local.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("QBdlxKeyStorePassword") ?: "android"
                keyAlias = System.getenv("QBdlxKeyAlias") ?: "androiddebugkey"
                keyPassword = System.getenv("QBdlxKeyPassword") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Falls back to the standard debug key when no local key exists.
            if (signingConfigs.getByName("local").storeFile != null) {
                signingConfig = signingConfigs.getByName("local")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.getByName("local").storeFile != null) {
                signingConfig = signingConfigs.getByName("local")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Share the audio/image fixtures between the JVM tests (classpath) and
        // the on-device tests (assets).
        getByName("androidTest").assets.srcDir("src/test/resources")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests {
            // MetadataTagger logs through android.util.Log; without this the
            // JVM test harness throws "Method w in android.util.Log not mocked"
            // instead of exercising the real tagging code.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.jaudiotagger)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    // The lyrics service is exercised over a real HTTP stack rather than by
    // stubbing the repository, so the request shape and JSON parsing are covered.
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    // Track rows are only wrong at the Compose level: a nested clickable that
    // covers the title swallows the tap, and nothing below the UI can see that.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Compose UI tests pull Espresso 3.5.0 transitively. That version injects
    // touches through InputManager.getInstance(), which no longer exists on
    // Android 17, so every click throws on a current phone while passing on an
    // older emulator. Pinning a current Espresso is what makes these tests
    // meaningful on the device they are meant to protect.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
