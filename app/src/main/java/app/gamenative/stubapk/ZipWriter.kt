package app.gamenative.stubapk

import java.util.zip.CRC32

/**
 * Writes the archive a stub APK is.
 *
 * Purpose-built rather than ZipOutputStream because two things matter here that the stream does
 * not expose: every entry is stored uncompressed, and an entry's data can be aligned. The
 * platform maps resources.arsc straight out of the archive and refuses to install one that is
 * compressed or misaligned. Stubs are a few kilobytes, so storing everything costs nothing.
 */
internal class ZipWriter {

    private data class Entry(
        val name: ByteArray,
        val crc: Int,
        val size: Int,
        val offset: Int,
    )

    private val out = Le()
    private val entries = mutableListOf<Entry>()

    /** Adds [data] as [name], aligning its contents to [alignment] bytes. */
    fun add(name: String, data: ByteArray, alignment: Int = 1) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value.toInt()
        val offset = out.size

        // The extra field is the only padding a reader will skip, so alignment is bought by
        // sizing it so the data lands where it should.
        val headerSize = 30 + nameBytes.size
        val extraSize = ((alignment - (offset + headerSize) % alignment) % alignment)

        out.u32(0x04034b50)
        out.u16(20) // version needed
        out.u16(0) // flags
        out.u16(0) // stored
        out.u16(0) // modification time
        out.u16(0x0021) // modification date, 1980-01-01
        out.u32(crc)
        out.u32(data.size) // compressed size, the same when stored
        out.u32(data.size)
        out.u16(nameBytes.size)
        out.u16(extraSize)
        out.bytes(nameBytes)
        out.bytes(ByteArray(extraSize))
        out.bytes(data)

        entries += Entry(nameBytes, crc, data.size, offset)
    }

    fun finish(): ByteArray {
        val directoryOffset = out.size
        for (entry in entries) {
            out.u32(0x02014b50)
            out.u16(20) // version made by
            out.u16(20) // version needed
            out.u16(0) // flags
            out.u16(0) // stored
            out.u16(0)
            out.u16(0x0021)
            out.u32(entry.crc)
            out.u32(entry.size)
            out.u32(entry.size)
            out.u16(entry.name.size)
            out.u16(0) // no extra field here, only in the local header
            out.u16(0) // no comment
            out.u16(0) // disk number
            out.u16(0) // internal attributes
            out.u32(0) // external attributes
            out.u32(entry.offset)
            out.bytes(entry.name)
        }
        val directorySize = out.size - directoryOffset

        out.u32(0x06054b50)
        out.u16(0) // this disk
        out.u16(0) // disk with the directory
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(directorySize)
        out.u32(directoryOffset)
        out.u16(0) // no comment
        return out.toByteArray()
    }
}
