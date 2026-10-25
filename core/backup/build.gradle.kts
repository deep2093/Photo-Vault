plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // jvm("desktop")  PC target paused until the phone app is finished.

    sourceSets {
        all { languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi") }
        commonMain.dependencies {
            api(project(":core:household"))
            api(project(":core:media"))
        }
        androidMain.dependencies {
            // Private backup: Tink encryption lives in core:crypto; the shared backup code only sees PrivateVault.
            implementation(project(":core:crypto"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

kotlin.sourceSets.getByName("androidUnitTest").dependencies {
    implementation(kotlin("test"))
    implementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation(project(":core:crypto"))
}

android {
    namespace = "com.deepak.photovault.core.backup"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
