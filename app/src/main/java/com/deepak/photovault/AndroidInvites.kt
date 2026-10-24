package com.deepak.photovault

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deepak.photovault.ui.shell.InvitePlatform
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.File

/** Invites on Android: file picking and sharing, QR drawing (ZXing) and scanning (Google's code scanner, no camera permission). */
class AndroidInvites(
    private val activity: MainActivity,
    private val pick: ((Uri?) -> Unit) -> Unit,
) : InvitePlatform {
    override fun pickTextFile(onText: (String?) -> Unit) {
        pick { uri ->
            if (uri == null) return@pick onText(null)
            val text = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_BYTES).toByteArray().decodeToString() }
            }.getOrNull()
            onText(text)
        }
    }

    override fun shareInviteFile(text: String) {
        val dir = File(activity.cacheDir, "drive-shared").apply { mkdirs() }
        val file = File(dir, "Pixallery-invite.pxinvite").apply { writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.pixallery.invite")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Your Pixallery invite. Open it in Pixallery: Connect your drive > I have an invite. I'll tell you the code separately.")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(Intent.createChooser(send, "Share invite").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    override fun qr(text: String): List<List<Boolean>>? = runCatching {
        if (text.length > QR_MAX_CHARS) return null
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        List(m.height) { y -> List(m.width) { x -> m.get(x, y) } }
    }.getOrNull()

    override val canScan: Boolean = true

    override fun scanQr(onText: (String?) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { onText(it.rawValue) }
            .addOnFailureListener { onText(null) }
            .addOnCanceledListener { onText(null) }
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024
        const val QR_MAX_CHARS = 2000
    }
}
