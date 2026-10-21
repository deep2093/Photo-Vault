import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":ui:shell"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.deepak.photovault.desktop.MainKt"
        nativeDistributions {
            // Windows installer later; Phase 1 CI ships a ready-to-run folder (createDistributable).
            targetFormats(TargetFormat.Msi)
            packageName = "PhotoVault"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "PhotoVault"
                // Stable ID so a future installer upgrades in place.
                upgradeUuid = "6a0f3f5e-3c1b-4c8e-9d1e-1f6b8a0c5d21"
            }
        }
    }
}
