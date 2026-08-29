package app.gamenative.stubapk

/**
 * Writes a resources.arsc holding a single drawable, which is all a stub needs.
 *
 * A launcher icon has to be a resource reference -- there is no way to point `android:icon` at a
 * bare file in the archive -- so a stub cannot skip having a table, however little is in it. The
 * table produced here is the smallest one that resolves: package 0x7f, type 1, entry 0, which is
 * the id `@0x7f010000` the manifest refers to.
 */
internal object ArscWriter {

    const val PACKAGE_ID = 0x7f
    const val TYPE_ID = 0x01

    /** The resource id the single drawable gets, for the manifest to reference. */
    const val ICON_ID = (PACKAGE_ID shl 24) or (TYPE_ID shl 16) or 0x0000

    private const val TYPE_STRING = 0x03

    /**
     * [packageName] must match the manifest, and [iconPath] is the archive path of the icon,
     * which is what the drawable's value actually holds.
     */
    fun encode(packageName: String, iconPath: String): ByteArray {
        // Resource file paths live in the table-wide pool, not the package's.
        val files = StringPool().apply { add(iconPath) }
        val types = StringPool().apply { add("drawable") }
        val keys = StringPool().apply { add("icon") }

        val typesEncoded = types.encode()
        val keysEncoded = keys.encode()

        val packageHeaderSize = 288
        val packageHeader = Le()
        packageHeader.u32(PACKAGE_ID)
        // A fixed 128 char field, not a pooled string.
        require(packageName.length < 128) { "package name too long: $packageName" }
        for (index in 0 until 128) {
            packageHeader.u16(packageName.getOrNull(index)?.code ?: 0)
        }
        packageHeader.u32(packageHeaderSize) // typeStrings, from the start of this chunk
        packageHeader.u32(0) // lastPublicType, unused
        packageHeader.u32(packageHeaderSize + typesEncoded.size) // keyStrings
        packageHeader.u32(0) // lastPublicKey, unused
        packageHeader.u32(0) // typeIdOffset

        val packageBody = Le()
        packageBody.bytes(typesEncoded)
        packageBody.bytes(keysEncoded)
        packageBody.bytes(typeSpec())
        packageBody.bytes(type(files[iconPath]))

        val tableHeader = Le()
        tableHeader.u32(1) // packageCount

        val tableBody = Le()
        tableBody.bytes(files.encode())
        tableBody.bytes(
            ResChunk.wrap(
                ResChunk.TYPE_TABLE_PACKAGE,
                packageHeaderSize,
                packageHeader.toByteArray(),
                packageBody.toByteArray(),
            ),
        )

        return ResChunk.wrap(ResChunk.TYPE_TABLE, 12, tableHeader.toByteArray(), tableBody.toByteArray())
    }

    /** Declares the type and, per entry, which configurations it varies by -- none here. */
    private fun typeSpec(): ByteArray {
        val header = Le()
        header.u8(TYPE_ID)
        header.u8(0) // res0
        header.u16(0) // res1
        header.u32(1) // entryCount

        val body = Le()
        body.u32(0) // no configuration axis affects this entry
        return ResChunk.wrap(ResChunk.TYPE_TABLE_TYPE_SPEC, 16, header.toByteArray(), body.toByteArray())
    }

    /** The entries for one configuration, which for a stub is the default one. */
    private fun type(fileIndex: Int): ByteArray {
        val headerSize = 84
        val header = Le()
        header.u8(TYPE_ID)
        header.u8(0) // flags
        header.u16(0) // reserved
        header.u32(1) // entryCount
        header.u32(headerSize + 4) // entriesStart, past the one offset below
        header.bytes(defaultConfig())

        val body = Le()
        body.u32(0) // offset of entry 0 within the entry data

        body.u16(8) // entry size
        body.u16(0) // flags: a plain value, not a map
        body.u32(0) // key index, "icon"

        body.u16(8) // value size
        body.u8(0) // res0
        body.u8(TYPE_STRING)
        body.u32(fileIndex)

        return ResChunk.wrap(ResChunk.TYPE_TABLE_TYPE, headerSize, header.toByteArray(), body.toByteArray())
    }

    /**
     * A ResTable_config that matches everything, meaning zero in every field. Only its leading
     * size is meaningful; the platform reads whatever it understands out of the rest.
     */
    private fun defaultConfig(): ByteArray {
        val size = 64
        val config = Le()
        config.u32(size)
        while (config.size < size) config.u8(0)
        return config.toByteArray()
    }
}
