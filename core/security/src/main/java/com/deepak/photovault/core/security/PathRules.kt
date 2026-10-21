package com.deepak.photovault.core.security

/** Pure path checks, unit-testable without Android. Same rules as Veyra's SettingsStore.normalizeRoot. */
object PathRules {
    /** Forward slashes, no leading/trailing slash, no empty, "." or ".." segments. "" means the share root. */
    fun normalizeRoot(raw: String): String {
        val result = raw.trim().replace('\\', '/').trim('/')
        require(result.isEmpty() || result.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Invalid starting folder" }
        return result
    }
}
