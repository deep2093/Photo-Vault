package com.deepak.photovault.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class SecuredPathsTest {
    @Test
    fun photosAndVideosGoBackToTheirOwnAlbum() {
        assertEquals("DCIM/Camera/", SecuredPaths.relativePath("Camera", false))
        assertEquals("DCIM/Camera/", SecuredPaths.relativePath("camera", true))
        assertEquals("Pictures/Family trip/", SecuredPaths.relativePath("Family trip", false))
        assertEquals("Movies/Family trip/", SecuredPaths.relativePath("Family trip", true))
        assertEquals("Pictures/Screenshots/", SecuredPaths.relativePath(" Screenshots ", false))
    }

    @Test
    fun oddOrMissingNamesFallBackToPixallery() {
        assertEquals("Pictures/Pixallery/", SecuredPaths.relativePath("", false))
        assertEquals("Movies/Pixallery/", SecuredPaths.relativePath("  ", true))
        assertEquals("Pictures/Pixallery/", SecuredPaths.relativePath("..", false))
        assertEquals("Pictures/ab/", SecuredPaths.relativePath("a/b", false))
    }
}
