pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PhotoVault"

include(":app")
// Ported from Veyra (deep2093/SmbVideoPlayer). See docs/REUSE_FROM_VEYRA.md.
include(":core:security")
include(":core:smb")
include(":core:vpn")
