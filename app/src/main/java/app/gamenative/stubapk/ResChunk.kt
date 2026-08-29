package app.gamenative.stubapk

import java.io.ByteArrayOutputStream

/**
 * Primitives for the binary resource format used by AndroidManifest.xml and resources.arsc.
 *
 * The format is a tree of length-prefixed chunks, so everything here is composed bottom-up: a
 * chunk is built from its finished children, which is what lets sizes be written once, up
 * front, instead of being patched back in afterwards.
 *
 * Reference: frameworks/base ResourceTypes.h.
 */
internal object ResChunk {
    const val TYPE_STRING_POOL = 0x0001
    const val TYPE_TABLE = 0x0002
    const val TYPE_XML = 0x0003
    const val TYPE_XML_START_NAMESPACE = 0x0100
    const val TYPE_XML_END_NAMESPACE = 0x0101
    const val TYPE_XML_START_ELEMENT = 0x0102
    const val TYPE_XML_END_ELEMENT = 0x0103
    const val TYPE_XML_RESOURCE_MAP = 0x0180
    const val TYPE_TABLE_PACKAGE = 0x0200
    const val TYPE_TABLE_TYPE = 0x0201
    const val TYPE_TABLE_TYPE_SPEC = 0x0202

    /** Wraps [body] in a chunk header whose own size is [headerSize]. */
    fun wrap(type: Int, headerSize: Int, headerRest: ByteArray, body: ByteArray): ByteArray {
        require(headerRest.size == headerSize - 8) {
            "header is ${headerRest.size + 8} bytes, declared $headerSize"
        }
        val out = Le()
        out.u16(type)
        out.u16(headerSize)
        out.u32(headerSize + body.size)
        out.bytes(headerRest)
        out.bytes(body)
        return out.toByteArray()
    }
}

/** A little-endian byte sink, which is the byte order the resource format uses throughout. */
internal class Le {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(value: Int) {
        out.write(value and 0xff)
    }

    fun u16(value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    fun u32(value: Int) {
        u16(value and 0xffff)
        u16((value ushr 16) and 0xffff)
    }

    fun bytes(value: ByteArray) = out.write(value)

    /** Zero-pads to a four byte boundary, which chunk boundaries must sit on. */
    fun pad4() {
        while (out.size() % 4 != 0) out.write(0)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * The string table a chunk tree refers to by index.
 *
 * Strings are interned, and the index a string is given is stable from the moment it is added.
 * That matters for XML: the attribute names must occupy the first indices, in the same order as
 * the resource map that gives them their attribute ids.
 *
 * Encoded as UTF-16, which is the older of the two layouts the platform accepts and the one
 * without the two-length encoding UTF-8 pools use.
 */
internal class StringPool {
    private val indices = LinkedHashMap<String, Int>()

    val size: Int get() = indices.size

    /** Interns [value] and returns its index. */
    fun add(value: String): Int = indices.getOrPut(value) { indices.size }

    /** The index of [value], which must already have been added. */
    operator fun get(value: String): Int =
        indices[value] ?: error("string not in pool: $value")

    fun encode(): ByteArray {
        val offsets = Le()
        val strings = Le()
        for (value in indices.keys) {
            offsets.u32(strings.size)
            strings.u16(value.length)
            for (char in value) strings.u16(char.code)
            strings.u16(0)
        }
        strings.pad4()

        val headerSize = 28
        val header = Le()
        header.u32(indices.size)
        header.u32(0) // styleCount
        header.u32(0) // flags: UTF-16, not sorted
        header.u32(headerSize + offsets.size) // stringsStart
        header.u32(0) // stylesStart, unused with no styles

        val body = Le()
        body.bytes(offsets.toByteArray())
        body.bytes(strings.toByteArray())
        return ResChunk.wrap(ResChunk.TYPE_STRING_POOL, headerSize, header.toByteArray(), body.toByteArray())
    }
}
