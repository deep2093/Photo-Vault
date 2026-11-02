package com.deepak.photovault.core.backup

/** Where a photo or video leaves the Secured folder to: its original album, as the gallery folder it came from. */
object SecuredPaths {
    /** The gallery folder (MediaStore RELATIVE_PATH, with a trailing slash) for a photo or video that came from [album]. */
    fun relativePath(album: String, isVideo: Boolean): String {
        val clean = album.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(60)
        return when {
            clean.isEmpty() || clean == "." || clean == ".." -> if (isVideo) "Movies/Pixallery/" else "Pictures/Pixallery/"
            clean.equals("Camera", ignoreCase = true) -> "DCIM/Camera/"
            isVideo -> "Movies/$clean/"
            else -> "Pictures/$clean/"
        }
    }
}
