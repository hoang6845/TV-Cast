package com.tvchromecast.screenmirroringplus.tvremote

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

internal object AndroidTvProtocolCodec {
    private const val STATUS_OK = 200
    private const val PROTOCOL_VERSION = 2
    private const val ROLE_INPUT = 1
    private const val ENCODING_HEXADECIMAL = 3

    private const val FEATURE_PING = 1
    private const val FEATURE_KEY = 2
    private const val FEATURE_IME = 4
    private const val FEATURE_POWER = 32
    private const val FEATURE_VOLUME = 64
    private const val FEATURE_APP_LINK = 512

    private const val REMOTE_DIRECTION_SHORT = 3
    private const val PACKAGE_NAME = "atvremote"
    private const val APP_VERSION = "1.0.0"

    val activeFeatures: Int = FEATURE_PING or
            FEATURE_KEY or
            FEATURE_IME or
            FEATURE_POWER or
            FEATURE_VOLUME or
            FEATURE_APP_LINK

    fun pairingRequest(clientName: String): ByteArray {
        return outer(
            ProtobufCodec.message(
                10,
                ProtobufCodec.concat(
                    ProtobufCodec.string(1, "atvremote"),
                    ProtobufCodec.string(2, clientName)
                )
            )
        )
    }

    fun pairingOptions(): ByteArray {
        val encoding = ProtobufCodec.concat(
            ProtobufCodec.uint32(1, ENCODING_HEXADECIMAL),
            ProtobufCodec.uint32(2, 6)
        )
        return outer(
            ProtobufCodec.message(
                20,
                ProtobufCodec.concat(
                    ProtobufCodec.message(1, encoding),
                    ProtobufCodec.uint32(3, ROLE_INPUT)
                )
            )
        )
    }

    fun pairingConfiguration(): ByteArray {
        val encoding = ProtobufCodec.concat(
            ProtobufCodec.uint32(1, ENCODING_HEXADECIMAL),
            ProtobufCodec.uint32(2, 6)
        )
        return outer(
            ProtobufCodec.message(
                30,
                ProtobufCodec.concat(
                    ProtobufCodec.message(1, encoding),
                    ProtobufCodec.uint32(2, ROLE_INPUT)
                )
            )
        )
    }

    fun pairingSecret(
        pairingCode: String,
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate
    ): ByteArray {
        val normalizedCode = pairingCode.trim().uppercase()
        require(normalizedCode.length == 6) { "Pairing code must contain 6 hex characters" }
        val pinBytes = normalizedCode.hexToBytes()
        val clientPublicKey = clientCertificate.publicKey as RSAPublicKey
        val serverPublicKey = serverCertificate.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(clientPublicKey.modulus.toUnsignedBytes())
        digest.update(clientPublicKey.publicExponent.toUnsignedBytes())
        digest.update(serverPublicKey.modulus.toUnsignedBytes())
        digest.update(serverPublicKey.publicExponent.toUnsignedBytes())
        digest.update(pinBytes.copyOfRange(1, pinBytes.size))

        val secret = digest.digest()
        if ((secret[0].toInt() and 0xFF) != (pinBytes[0].toInt() and 0xFF)) {
            throw TvRemoteException("Incorrect pairing code")
        }

        return outer(ProtobufCodec.message(40, ProtobufCodec.bytes(1, secret)))
    }

    fun parsePairingMessage(body: ByteArray): PairingMessageType {
        val fields = ProtobufCodec.parse(body)
        val status = fields.firstOrNull { it.number == 2 }?.varint?.toInt() ?: STATUS_OK
        if (status != STATUS_OK) return PairingMessageType.Error(status)
        return when {
            fields.any { it.number == 11 } -> PairingMessageType.RequestAck
            fields.any { it.number == 20 } -> PairingMessageType.Options
            fields.any { it.number == 31 } -> PairingMessageType.ConfigurationAck
            fields.any { it.number == 41 } -> PairingMessageType.SecretAck
            else -> PairingMessageType.Unknown
        }
    }

    fun remoteConfigureResponse(): ByteArray {
        val deviceInfo = ProtobufCodec.concat(
            ProtobufCodec.uint32(3, 1),
            ProtobufCodec.string(4, "1"),
            ProtobufCodec.string(5, PACKAGE_NAME),
            ProtobufCodec.string(6, APP_VERSION)
        )
        return ProtobufCodec.message(
            1,
            ProtobufCodec.concat(
                ProtobufCodec.uint32(1, activeFeatures),
                ProtobufCodec.message(2, deviceInfo)
            )
        )
    }

    fun remoteSetActiveResponse(): ByteArray {
        return ProtobufCodec.message(2, ProtobufCodec.uint32(1, activeFeatures))
    }

    fun remotePingResponse(value: Int): ByteArray {
        return ProtobufCodec.message(9, ProtobufCodec.uint32(1, value))
    }

    fun remoteKey(key: TvRemoteKey): ByteArray {
        return ProtobufCodec.message(
            10,
            ProtobufCodec.concat(
                ProtobufCodec.uint32(1, key.androidKeyCode),
                ProtobufCodec.uint32(2, REMOTE_DIRECTION_SHORT)
            )
        )
    }

    fun remoteText(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        val cursor = (text.length - 1).coerceAtLeast(0)
        val imeObject = ProtobufCodec.concat(
            ProtobufCodec.uint32(1, cursor),
            ProtobufCodec.uint32(2, cursor),
            ProtobufCodec.string(3, text)
        )
        val editInfo = ProtobufCodec.concat(
            ProtobufCodec.uint32(1, 1),
            ProtobufCodec.message(2, imeObject)
        )
        return ProtobufCodec.message(
            21,
            ProtobufCodec.concat(
                ProtobufCodec.uint32(1, imeCounter),
                ProtobufCodec.uint32(2, fieldCounter),
                ProtobufCodec.message(3, editInfo)
            )
        )
    }

    fun remoteLaunchApp(packageNameOrDeepLink: String): ByteArray {
        val appLink = if (packageNameOrDeepLink.contains("://")) {
            packageNameOrDeepLink
        } else {
            "market://launch?id=$packageNameOrDeepLink"
        }
        return ProtobufCodec.message(90, ProtobufCodec.string(1, appLink))
    }

    fun parseRemoteMessage(body: ByteArray): RemoteIncomingMessage {
        val fields = ProtobufCodec.parse(body)
        fields.firstOrNull { it.number == 8 }?.let { ping ->
            val value = ProtobufCodec.parse(ping.bytes).firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
            return RemoteIncomingMessage.Ping(value)
        }
        fields.firstOrNull { it.number == 21 }?.let { batch ->
            val batchFields = ProtobufCodec.parse(batch.bytes)
            return RemoteIncomingMessage.ImeCounters(
                imeCounter = batchFields.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0,
                fieldCounter = batchFields.firstOrNull { it.number == 2 }?.varint?.toInt() ?: 0
            )
        }
        return when {
            fields.any { it.number == 1 } -> RemoteIncomingMessage.Configure
            fields.any { it.number == 2 } -> RemoteIncomingMessage.SetActive
            fields.any { it.number == 40 } -> RemoteIncomingMessage.Started
            else -> RemoteIncomingMessage.Unknown
        }
    }

    private fun outer(payload: ByteArray): ByteArray {
        return ProtobufCodec.concat(
            ProtobufCodec.uint32(1, PROTOCOL_VERSION),
            ProtobufCodec.uint32(2, STATUS_OK),
            payload
        )
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}

internal sealed interface PairingMessageType {
    data object RequestAck : PairingMessageType
    data object Options : PairingMessageType
    data object ConfigurationAck : PairingMessageType
    data object SecretAck : PairingMessageType
    data object Unknown : PairingMessageType
    data class Error(val status: Int) : PairingMessageType
}

internal sealed interface RemoteIncomingMessage {
    data object Configure : RemoteIncomingMessage
    data object SetActive : RemoteIncomingMessage
    data object Started : RemoteIncomingMessage
    data object Unknown : RemoteIncomingMessage
    data class Ping(val value: Int) : RemoteIncomingMessage
    data class ImeCounters(val imeCounter: Int, val fieldCounter: Int) : RemoteIncomingMessage
}

