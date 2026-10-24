// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM with a 256-bit key held in the Android Keystore, no user authentication required, so background
 * sync can decrypt (DESIGN.md §6.1 step 1). Used by `SettingsStore` for the Maps API key (§1.4), the PGW
 * username, password and session cookies (§6.1), and, when the people finder is built, HA tokens (§17.2).
 * The key never leaves the Keystore, so the ciphertext is useless off the phone and after a reinstall
 * (which is why the settings backup carries the secrets in plain text, §13.4). Pure object, any thread;
 * each call does its own Keystore lookup, which is cheap next to the DataStore read around it.
 */
object SecretBox {
    /** Keystore alias. The `_v1` suffix lets a future scheme change coexist with old entries. */
    private const val ALIAS = "autonav_secrets_v1"
    /** The cipher: AES in GCM mode, authenticated, no padding needed. */
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** The Keystore key, generated on first use: AES-256, GCM only, encrypt and decrypt, no auth prompt. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * Encrypts [plain] to a single Base64 string: the Keystore's random 12-byte IV, then the ciphertext with
     * its 16-byte GCM tag. A fresh IV every call, so the same secret never encrypts the same way twice.
     */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * The plain text of a string made by [encrypt], or null when it can't be read: bad Base64, a tag that
     * doesn't verify, or a Keystore key that no longer exists (after a reinstall or a Keystore reset). Callers
     * treat null as "not set", so a lost key means re-entering the secret, never a crash.
     */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        // 128-bit tag; the IV is the first 12 bytes, the ciphertext and tag the rest.
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
