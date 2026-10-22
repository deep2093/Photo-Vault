plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // jvm("desktop")  PC target paused until the phone app is finished.

    sourceSets {
        commonMain.dependencies {
            api(project(":core:designsystem"))
            api(project(":core:media"))
            api(project(":core:household"))
            // Thumbnails and full photos. Coil 3 is multiplatform, so this stays shared code.
            implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        }
        androidMain.dependencies {
            // Video playback in the viewer (same player library as Veyra).
            implementation("androidx.media3:media3-exoplayer:1.6.1")
            implementation("androidx.media3:media3-ui:1.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.deepak.photovault.ui.shell"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
