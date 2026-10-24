import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.deepak.photovault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepak.photovault"
        // Android 11+ only: batch delete/trash consent (MediaStore.createDeleteRequest) exists from API 30.
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("previewVersionCode").orNull?.toInt()
            ?: (1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) * 2)
        versionName = "0.0.1"
        resValue("string", "app_name", "Pixallery")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 64-bit only, like Veyra. The arm64 APK is the one for phones and tablets;
    // x86_64 is included only so CI emulators can run the tests.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // Real release identity, supplied outside the repository. Never commit these values.
        val releaseStore = providers.gradleProperty("releaseStoreFile").orNull ?: System.getenv("RELEASE_STORE_FILE")
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("releaseStorePassword").orNull ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("releaseKeyAlias").orNull ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("releaseKeyPassword").orNull ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        create("preview") {
            // PhotoVault's own non-production test identity (not Veyra's). Keep stable so preview builds update in place.
            storeFile = rootProject.file(".ci/preview-signing.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = KeyStore.getInstance("PKCS12").run {
                rootProject.file(".ci/preview-signing.p12").inputStream().use { load(it, "android".toCharArray()) }
                aliases().toList().single { isKeyEntry(it) }
            }
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "Pixallery Preview")
            signingConfig = signingConfigs.getByName("preview")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned unless a release key is configured above; never signed with the preview key.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // The test APK (24 Oct): optimised like the real app, so it is as smooth as the real thing (debug builds
        // run much slower, most of all the first time each screen opens). Same ID and key as before, so it
        // updates in place. Names are left readable so the crash screen still says where things went wrong.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "Pixallery Preview")
            signingConfig = signingConfigs.getByName("preview")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "preview-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    implementation(project(":core:security"))
    implementation(project(":core:smb"))
    implementation(project(":core:vpn"))
    // Shared with the PC app: design system and screens.
    implementation(project(":ui:shell"))

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Scheduled and background backups (Phase 4).
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Keeps date, camera and location details when Storage saver shrinks a photo.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Invites: draw the QR code, and scan one with Google's code scanner (no camera permission needed).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
}
