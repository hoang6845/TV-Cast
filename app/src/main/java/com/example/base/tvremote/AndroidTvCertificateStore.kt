package com.example.base.tvremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal class AndroidTvCertificateStore(context: Context) {
    private val appContext = context.applicationContext

    fun sslContext(): SSLContext {
        ensureCertificate()
        val keyStore = keyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            arrayOf(SingleAliasKeyManager(privateKey, certificate)),
            arrayOf(TrustAllManager),
            SecureRandom()
        )
        return sslContext
    }

    fun certificate(): X509Certificate {
        ensureCertificate()
        return keyStore().getCertificate(KEY_ALIAS) as X509Certificate
    }

    private fun ensureCertificate() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEY_STORE
        )
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or
                    KeyProperties.PURPOSE_VERIFY or
                    KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
        )
            .setCertificateSubject(X500Principal("CN=${appContext.packageName}"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - ONE_DAY_MS))
            .setCertificateNotAfter(Date(now + TEN_YEARS_MS))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    private class SingleAliasKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> {
            return arrayOf(KEY_ALIAS)
        }

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: Socket?
        ): String = KEY_ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: Socket?
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = privateKey

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?
        ): String = KEY_ALIAS
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tv_cast_android_tv_remote_v2"
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val TEN_YEARS_MS = 10L * 365L * ONE_DAY_MS
    }
}

