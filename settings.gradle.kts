pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PhotoVault"

include(":app")
// The Windows PC app (desktop/) is paused until the phone app is finished and refined.
// To resume: include(":desktop") and add jvm("desktop") back to the shared modules (docs/ARCHITECTURE.md).

// Shared code, kept in Kotlin Multiplatform form so the PC port stays a small step.
include(":core:designsystem")
include(":core:media")
include(":core:drive")
include(":core:household")
include(":core:backup")
include(":ui:shell")

// Android-only for now. Ported from Veyra (deep2093/SmbVideoPlayer). See docs/REUSE_FROM_VEYRA.md
// and docs/ARCHITECTURE.md ("Running on a PC") for how they are split when the PC app needs them.
include(":core:security")
include(":core:smb")
include(":core:vpn")
