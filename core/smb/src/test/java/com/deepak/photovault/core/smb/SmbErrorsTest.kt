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
