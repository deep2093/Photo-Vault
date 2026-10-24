package com.deepak.photovault.core.smb


import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SmbErrorsTest {
    @Test fun aMovedDriveIsLookedForNearbyOnTheSameNetworkOnly() {
        assertTrue(SmbErrors.isUnreachable(java.net.SocketTimeoutException("timeout")))
        assertTrue(SmbErrors.isUnreachable(IOException(java.net.ConnectException("refused"))))
        // A server that answered "no" (wrong password, bad share) isn't looked for elsewhere.
        assertFalse(SmbErrors.isUnreachable(IOException(IllegalArgumentException("bad"))))
        assertFalse(SmbErrors.isUnreachable(IOException(SmbSecurityException())))
        val found = listOf("192.168.71.1", "192.168.71.249", "192.168.71.250", "192.168.71.253", "10.0.0.249")
        assertEquals(listOf("192.168.71.249", "192.168.71.253", "192.168.71.1"), SmbErrors.nearbyFirst("192.168.71.250", found))
        assertTrue(SmbErrors.nearbyFirst("nas.local", found).isEmpty())
        // Deepak's home network is 192.168.68.0/22: a phone on .68.x and the drive on .71.x are both home.
        fun ip(a: Int, b: Int, c: Int, d: Int) = (a shl 24) or (b shl 16) or (c shl 8) or d
        assertTrue(SmbErrors.sameNetwork(ip(192, 168, 68, 77), ip(192, 168, 71, 250), 22))
        assertFalse(SmbErrors.sameNetwork(ip(192, 168, 68, 77), ip(192, 168, 72, 1), 22))
        assertFalse(SmbErrors.sameNetwork(ip(192, 168, 68, 77), ip(192, 168, 71, 250), 24))
        // Find drive searches the whole home network (a Deco /22 is four blocks), never more than a /22.
        assertEquals(listOf("192.168.68", "192.168.69", "192.168.70", "192.168.71"), LanDiscovery.blocksOf("192.168.69.20", 22))
        assertEquals(listOf("192.168.1"), LanDiscovery.blocksOf("192.168.1.20", 24))
        assertEquals(4, LanDiscovery.blocksOf("10.0.5.9", 16).size)
        // Server IDs: blank or all zeros can't identify a drive.
        assertEquals(null, SmbErrors.usableServerId("00000000-0000-0000-0000-000000000000"))
        assertEquals(null, SmbErrors.usableServerId(" "))
        assertEquals("5f0c2b1e-1111-4a2b-9c3d-abcdef012345", SmbErrors.usableServerId("5F0C2B1E-1111-4A2B-9C3D-ABCDEF012345"))
    }

    @Test fun permanentOrCancelledCausesAreNotRetried() {
        assertFalse(
            SmbErrors.isTransient(
                IOException(
                    com.deepak.photovault.core.smb
                        .SmbSecurityException(),
                ),
            ),
        )
        assertFalse(SmbErrors.isTransient(IOException(IllegalArgumentException("Destination already exists"))))
        assertFalse(SmbErrors.isTransient(java.io.InterruptedIOException("cancelled")))
        assertTrue(SmbErrors.isTransient(java.net.SocketTimeoutException("timeout")))
    }

    @Test fun explainsKnownValidationWithoutExposingArbitraryMessages() {
        assertTrue(SmbErrors.describe(IllegalArgumentException("Destination already exists")).contains("already exists"))
        assertTrue(
            SmbErrors
                .describe(
                    IOException(IllegalArgumentException("Source and destination are the same")),
                ).contains("different destination"),
        )
        val secret = "smb://person:secret-password@private-server/photo.jpg"
        assertFalse(SmbErrors.describe(IllegalArgumentException(secret)).contains(secret))
        assertFalse(SmbErrors.describe(IOException(secret)).contains("secret-password"))
    }
    @org.junit.Test fun missingFileIsPermanentAndDoesNotExposeItsPath() {
        val error=java.io.FileNotFoundException("secret/path/photo.jpg")
        org.junit.Assert.assertFalse(com.deepak.photovault.core.smb.SmbErrors.isTransient(error))
        org.junit.Assert.assertFalse(com.deepak.photovault.core.smb.SmbErrors.describe(error).contains("secret"))
    }

}

class SmbProfileTest {
    @Test fun toStringNeverContainsThePassword() {
        val profile = SmbProfile(host = "192.168.1.1", share = "drive", rootPath = "PhotoVault", username = "family", password = "hunter2-secret")
        assertFalse(profile.toString().contains("hunter2-secret"))
        assertTrue(profile.toString().contains("192.168.1.1"))
    }
}
