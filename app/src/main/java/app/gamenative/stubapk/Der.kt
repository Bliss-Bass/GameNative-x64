package app.gamenative.stubapk

/**
 * Just enough DER to write an X.509 certificate.
 *
 * A certificate is unavoidable: both signature schemes the platform accepts carry one, and the
 * framework exposes no way to build one -- it can parse certificates and sign bytes, but not
 * issue. Only the handful of types a self-signed certificate uses are here.
 */
internal object Der {

    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val NULL = 0x05
    private const val OBJECT_IDENTIFIER = 0x06
    private const val UTF8_STRING = 0x0c
    private const val UTC_TIME = 0x17
    private const val GENERALIZED_TIME = 0x18
    private const val SEQUENCE = 0x30
    private const val SET = 0x31

    /** A tag-length-value, with the length in the shortest form DER allows. */
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 0x80 -> out.write(value.size)
            else -> {
                // Long form: how many length bytes follow, then the length, big-endian.
                var length = value.size
                val bytes = ArrayList<Int>()
                while (length > 0) {
                    bytes.add(0, length and 0xff)
                    length = length ushr 8
                }
                out.write(0x80 or bytes.size)
                for (byte in bytes) out.write(byte)
            }
        }
        out.write(value)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(SEQUENCE, parts.concat())

    fun set(vararg parts: ByteArray): ByteArray = tlv(SET, parts.concat())

    /** An explicitly tagged, constructed context value: `[n] { ... }`. */
    fun tagged(number: Int, vararg parts: ByteArray): ByteArray =
        tlv(0xa0 or number, parts.concat())

    fun integer(value: Int): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>()
        do {
            bytes.add(0, (remaining and 0xff).toByte())
            remaining = remaining shr 8
        } while (remaining != 0 && remaining != -1)
        // A leading bit of 1 would read as negative, so a zero byte goes in front.
        if (value > 0 && (bytes[0].toInt() and 0x80) != 0) bytes.add(0, 0)
        return tlv(INTEGER, bytes.toByteArray())
    }

    /** A positive integer from raw bytes, for a serial number that does not fit an Int. */
    fun positiveInteger(bytes: ByteArray): ByteArray {
        val stripped = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        val trimmed = if (stripped.isEmpty()) byteArrayOf(0) else stripped
        val padded = if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
        return tlv(INTEGER, padded)
    }

    /** A bit string with no unused trailing bits, which is the only case here. */
    fun bitString(value: ByteArray): ByteArray = tlv(BIT_STRING, byteArrayOf(0) + value)

    fun nullValue(): ByteArray = tlv(NULL, ByteArray(0))

    fun utf8String(value: String): ByteArray = tlv(UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    /** [value] as yyMMddHHmmssZ, the form UTCTime takes. */
    fun utcTime(value: String): ByteArray = tlv(UTC_TIME, value.toByteArray(Charsets.US_ASCII))

    /**
     * [value] as yyyyMMddHHmmssZ. UTCTime has only two digits for the year and is defined to
     * mean 1950 through 2049, so anything later has to be written this way.
     */
    fun generalizedTime(value: String): ByteArray =
        tlv(GENERALIZED_TIME, value.toByteArray(Charsets.US_ASCII))

    /** An object identifier from its dotted form. */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        require(parts.size >= 2) { "not an oid: $dotted" }
        val out = java.io.ByteArrayOutputStream()
        // The first two arcs share a byte, which is why they are encoded together.
        out.write(parts[0] * 40 + parts[1])
        for (part in parts.drop(2)) {
            // Base-128, high bit set on every byte but the last.
            var value = part
            val group = ArrayList<Int>()
            do {
                group.add(0, value and 0x7f)
                value = value ushr 7
            } while (value != 0)
            for (index in group.indices) {
                out.write(if (index == group.size - 1) group[index] else group[index] or 0x80)
            }
        }
        return tlv(OBJECT_IDENTIFIER, out.toByteArray())
    }

    /** Wraps an already-encoded structure, such as a key's own DER form, unchanged. */
    fun raw(value: ByteArray): ByteArray = value

    private fun Array<out ByteArray>.concat(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in this) out.write(part)
        return out.toByteArray()
    }
}
