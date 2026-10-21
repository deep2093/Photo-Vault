pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PhotoVault"

// Android app and the Windows PC app share everything under core/designsystem and ui/.
include(":app")
include(":desktop")

// Shared (Android + PC), Kotlin Multiplatform.
include(":core:designsystem")
include(":ui:shell")

// Android-only for now. Ported from Veyra (deep2093/SmbVideoPlayer). See docs/REUSE_FROM_VEYRA.md
// and docs/ARCHITECTURE.md ("Running on a PC") for how they are split when the PC app needs them.
include(":core:security")
include(":core:smb")
include(":core:vpn")
