package com.deepak.photovault

import android.content.Context
import android.util.Base64
import com.deepak.photovault.core.backup.PrivateVault
import com.deepak.photovault.core.backup.TinkVault
import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.security.SecretBox

/**
 * The Private person's key on this phone, for code that has no screen (the backup worker, picture loading).
 * Same storage as [AndroidEncryption]: the root key sealed by a non-exportable Android Keystore key.
 */
object PrivateKeys {
    fun load(context: Context): MemberKeys? {
        val prefs = context.applicationContext.getSharedPreferences("pixallery_private_keys", Context.MODE_PRIVATE)
        return prefs.getString("root", null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { SecretBox("pixallery_member_root_v1").open(it) }
            ?.takeIf { it.size == 32 }
            ?.let { MemberKeys(it) }
    }

    fun vault(context: Context, householdId: String, memberId: String): PrivateVault? =
        load(context)?.let { TinkVault(it, KeyContext(householdId, memberId)) }
}
