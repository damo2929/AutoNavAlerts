// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.testing

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A software stand-in for the Android Keystore, so `SecretBox` (DESIGN.md §6.1 step 1: the PGW login and
 * session encrypted with a Keystore key) runs under Robolectric, which has no "AndroidKeyStore" provider.
 * Keys live in a static map by alias; the cipher itself is the JDK's AES-GCM, as on the phone. Test thread
 * only. Upholds nothing by itself: it lets the PGW tests use the real `SettingsStore` (hard rule 6: the
 * login is always the user's own, here a made-up one).
 */
object FakeAndroidKeyStore {
    internal val keys: MutableMap<String, SecretKey> = Collections.synchronizedMap(HashMap())

    /** Registers the provider once per JVM sandbox; later calls do nothing. */
    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeKeyStoreProvider())
    }
}

/** The JCA provider named "AndroidKeyStore": a key store and an AES key generator. */
class FakeKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "Test stand-in for the Android Keystore") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", FakeAesKeyGeneratorSpi::class.java.name)
    }
}

/** Generates a random AES-256 key and files it under the spec's alias, as the Keystore does. */
class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) { alias = (params as KeyGenParameterSpec).keystoreAlias }
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineGenerateKey(): SecretKey {
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        alias?.let { FakeAndroidKeyStore.keys[it] = key }
        return key
    }
}

/** Reads the keys the generator filed; only what `SecretBox` calls does anything. */
class FakeKeyStoreSpi : KeyStoreSpi() {
    private val keys get() = FakeAndroidKeyStore.keys
    override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
        keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineSetKeyEntry(alias: String, key: Key?, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key as SecretKey }
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
    override fun engineContainsAlias(alias: String) = alias in keys
    override fun engineSize() = keys.size
    override fun engineIsKeyEntry(alias: String) = alias in keys
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
