package com.tvchromecast.screenmirroringplus.tvremote

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal class AndroidTvCertificateStore(context: Context) {
    private val appContext = context.applicationContext

    fun sslContext(): SSLContext {
        val keyStore = keyStore()
        val privateKey = keyStore.getKey(KEY_ALIAS, KEY_PASSWORD) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        return SSLContext.getInstance("TLSv1.2").apply {
            init(
                arrayOf(SingleAliasKeyManager(privateKey, certificate)),
                arrayOf(TrustAllManager),
                SecureRandom()
            )
        }
    }

    fun certificate(): X509Certificate {
        return keyStore().getCertificate(KEY_ALIAS) as X509Certificate
    }

    private fun keyStore(): KeyStore {
        val file = certificateFile()
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        if (file.exists()) {
            try {
                FileInputStream(file).use { input ->
                    keyStore.load(input, KEY_PASSWORD)
                }
                if (keyStore.containsAlias(KEY_ALIAS)) return keyStore
            } catch (_: Throwable) {
                file.delete()
            }
        }

        keyStore.load(null, KEY_PASSWORD)
        val generated = SelfSignedCertificateGenerator.generate(
            commonName = "${appContext.packageName}.androidtvremote"
        )
        keyStore.setKeyEntry(
            KEY_ALIAS,
            generated.keyPair.private,
            KEY_PASSWORD,
            arrayOf(generated.certificate)
        )
        FileOutputStream(file).use { output ->
            keyStore.store(output, KEY_PASSWORD)
        }
        return keyStore
    }

    private fun certificateFile(): File {
        return File(appContext.filesDir, CERTIFICATE_FILE_NAME)
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
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val CERTIFICATE_FILE_NAME = "android_tv_remote_client_v1.p12"
        private const val KEY_ALIAS = "tv_cast_android_tv_remote_software_v1"
        private val KEY_PASSWORD = "tv_cast_remote".toCharArray()
    }
}

