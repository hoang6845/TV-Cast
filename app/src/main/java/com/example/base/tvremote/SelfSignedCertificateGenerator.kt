package com.example.base.tvremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object SelfSignedCertificateGenerator {
    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME_OID = "2.5.4.3"
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    private const val TEN_YEARS_MS = 10L * 365L * ONE_DAY_MS

    data class GeneratedCertificate(
        val keyPair: KeyPair,
        val certificate: X509Certificate
    )

    fun generate(commonName: String): GeneratedCertificate {
        val secureRandom = SecureRandom()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, secureRandom)
        }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - ONE_DAY_MS)
        val notAfter = Date(now + TEN_YEARS_MS)
        val serial = BigInteger(128, secureRandom).abs()
        val algorithm = algorithmIdentifier()
        val name = name(commonName)
        val validity = derSequence(utcTime(notBefore), utcTime(notAfter))

        val tbsCertificate = derSequence(
            derExplicit(0, derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            algorithm,
            name,
            validity,
            name,
            keyPair.public.encoded
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        val certificateDer = derSequence(
            tbsCertificate,
            algorithm,
            derBitString(signature)
        )
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
        certificate.verify(keyPair.public)
        return GeneratedCertificate(keyPair, certificate)
    }

    private fun algorithmIdentifier(): ByteArray {
        return derSequence(derOid(SHA256_WITH_RSA_OID), derNull())
    }

    private fun name(commonName: String): ByteArray {
        return derSequence(
            derSet(
                derSequence(
                    derOid(COMMON_NAME_OID),
                    derUtf8String(commonName)
                )
            )
        )
    }

    private fun derExplicit(tag: Int, content: ByteArray): ByteArray {
        return der(0xA0 + tag, content)
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        return der(0x30, concat(*parts))
    }

    private fun derSet(vararg parts: ByteArray): ByteArray {
        return der(0x31, concat(*parts))
    }

    private fun derInteger(value: BigInteger): ByteArray {
        return der(0x02, value.toByteArray())
    }

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        parts.drop(2).forEach { part ->
            val stack = mutableListOf((part and 0x7F).toInt())
            var value = part ushr 7
            while (value > 0) {
                stack += ((value and 0x7F) or 0x80).toInt()
                value = value ushr 7
            }
            stack.asReversed().forEach(out::write)
        }
        return der(0x06, out.toByteArray())
    }

    private fun derNull(): ByteArray {
        return der(0x05, ByteArray(0))
    }

    private fun derUtf8String(value: String): ByteArray {
        return der(0x0C, value.toByteArray(Charsets.UTF_8))
    }

    private fun utcTime(value: Date): ByteArray {
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return der(0x17, formatter.format(value).toByteArray(Charsets.US_ASCII))
    }

    private fun derBitString(value: ByteArray): ByteArray {
        return der(0x03, byteArrayOf(0) + value)
    }

    private fun der(tag: Int, value: ByteArray): ByteArray {
        return byteArrayOf(tag.toByte()) + derLength(value.size) + value
    }

    private fun derLength(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        var remaining = length
        val bytes = mutableListOf<Byte>()
        while (remaining > 0) {
            bytes += (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.asReversed().toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

