plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepak.photovault.core.smb"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        // smbj logs through Android's Log class, which is only a stub in local tests.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // The real-share tests run only where CI has started a throwaway Samba share (tools/start_test_share.sh).
            test.environment("PV_SMB_FIXTURE", System.getenv("PV_SMB_FIXTURE") ?: "")
        }
    }
}

dependencies {
    // Same SMB client as Veyra.
    api("com.hierynomus:smbj:0.14.0")
    api(project(":core:drive"))
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
