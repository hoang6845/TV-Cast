package com.example.base.tvremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal data class ProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long = 0L,
    val bytes: ByteArray = ByteArray(0)
)

internal object ProtobufCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    fun uint32(fieldNumber: Int, value: Int): ByteArray {
        return tag(fieldNumber, WIRE_VARINT) + varint(value.toLong())
    }

    fun bool(fieldNumber: Int, value: Boolean): ByteArray {
        return uint32(fieldNumber, if (value) 1 else 0)
    }

    fun string(fieldNumber: Int, value: String): ByteArray {
        return bytes(fieldNumber, value.toByteArray(StandardCharsets.UTF_8))
    }

    fun bytes(fieldNumber: Int, value: ByteArray): ByteArray {
        return tag(fieldNumber, WIRE_LENGTH_DELIMITED) + varint(value.size.toLong()) + value
    }

    fun message(fieldNumber: Int, value: ByteArray): ByteArray = bytes(fieldNumber, value)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun parse(data: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var index = 0
        while (index < data.size) {
            val tagResult = readVarint(data, index)
            index = tagResult.nextIndex
            val tag = tagResult.value.toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                WIRE_VARINT -> {
                    val value = readVarint(data, index)
                    index = value.nextIndex
                    fields += ProtoField(fieldNumber, wireType, varint = value.value)
                }

                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint(data, index)
                    index = length.nextIndex
                    val end = index + length.value.toInt()
                    if (end > data.size) throw EOFException("Invalid protobuf length")
                    fields += ProtoField(fieldNumber, wireType, bytes = data.copyOfRange(index, end))
                    index = end
                }

                else -> throw IllegalArgumentException("Unsupported protobuf wire type: $wireType")
            }
        }
        return fields
    }

    fun readFrame(input: InputStream): ByteArray {
        val length = readVarint(input)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) throw EOFException("Socket closed while reading protobuf frame")
            offset += read
        }
        return body
    }

    fun writeFrame(output: OutputStream, body: ByteArray) {
        output.write(varint(body.size.toLong()))
        output.write(body)
        output.flush()
    }

    private fun tag(fieldNumber: Int, wireType: Int): ByteArray {
        return varint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while (true) {
            if ((remaining and 0x7FL.inv()) == 0L) {
                out.write(remaining.toInt())
                return out.toByteArray()
            }
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }

    private fun readVarint(input: InputStream): Int {
        var shift = 0
        var result = 0
        while (shift < 32) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Socket closed while reading varint")
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Varint is too long")
    }

    private fun readVarint(data: ByteArray, startIndex: Int): VarintResult {
        var index = startIndex
        var shift = 0
        var result = 0L
        while (shift < 64 && index < data.size) {
            val byte = data[index++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if ((byte and 0x80) == 0) return VarintResult(result, index)
            shift += 7
        }
        throw IllegalArgumentException("Invalid protobuf varint")
    }

    private data class VarintResult(val value: Long, val nextIndex: Int)
}

