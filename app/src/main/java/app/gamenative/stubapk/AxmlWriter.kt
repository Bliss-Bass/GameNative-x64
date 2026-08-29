package app.gamenative.stubapk

/**
 * Encodes a small XML document into the binary form the platform reads a manifest as.
 *
 * Only what a stub manifest needs: one namespace, elements, and attributes holding a string, an
 * integer, a boolean or a resource reference. There is no text content, no styles and no
 * comments, none of which appear in a manifest.
 */
internal class AxmlWriter {

    /** The android namespace, the only one a stub manifest declares. */
    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_BOOLEAN = 0x12
    }

    sealed interface Value {
        data class Str(val value: String) : Value
        data class Int(val value: kotlin.Int) : Value
        data class Bool(val value: Boolean) : Value
        /** A reference to a resource in this package's own table. */
        data class Ref(val id: kotlin.Int) : Value
    }

    /**
     * [resId] is the framework attribute id, which the platform resolves the attribute by; an
     * attribute without one (only `package` here) is matched by name instead.
     */
    data class Attribute(val name: String, val resId: kotlin.Int?, val value: Value)

    data class Element(
        val name: String,
        val attributes: List<Attribute> = emptyList(),
        val children: List<Element> = emptyList(),
    )

    fun encode(root: Element): ByteArray {
        val pool = StringPool()

        // Attribute names first, in resource map order: the map is indexed by string index, so
        // an attribute id at index i claims whatever string sits at i.
        val attributeIds = LinkedHashMap<String, kotlin.Int>()
        collectAttributeIds(root, attributeIds)
        for (name in attributeIds.keys) pool.add(name)
        val resourceMapCount = pool.size

        pool.add(ANDROID_NS)
        pool.add("android")
        collectStrings(root, pool)

        val map = Le()
        for (id in attributeIds.values) map.u32(id)

        val body = Le()
        body.bytes(pool.encode())
        body.bytes(ResChunk.wrap(ResChunk.TYPE_XML_RESOURCE_MAP, 8, ByteArray(0), map.toByteArray()))
        body.bytes(namespace(ResChunk.TYPE_XML_START_NAMESPACE, pool))
        body.bytes(element(root, pool, resourceMapCount))
        body.bytes(namespace(ResChunk.TYPE_XML_END_NAMESPACE, pool))

        return ResChunk.wrap(ResChunk.TYPE_XML, 8, ByteArray(0), body.toByteArray())
    }

    private fun collectAttributeIds(element: Element, into: MutableMap<String, kotlin.Int>) {
        for (attribute in element.attributes) {
            val id = attribute.resId ?: continue
            val existing = into.put(attribute.name, id)
            require(existing == null || existing == id) {
                "attribute ${attribute.name} used with two ids"
            }
        }
        for (child in element.children) collectAttributeIds(child, into)
    }

    private fun collectStrings(element: Element, pool: StringPool) {
        pool.add(element.name)
        for (attribute in element.attributes) {
            pool.add(attribute.name)
            (attribute.value as? Value.Str)?.let { pool.add(it.value) }
        }
        for (child in element.children) collectStrings(child, pool)
    }

    private fun namespace(type: kotlin.Int, pool: StringPool): ByteArray {
        val header = Le()
        header.u32(1) // lineNumber
        header.u32(-1) // no comment
        val body = Le()
        body.u32(pool["android"])
        body.u32(pool[ANDROID_NS])
        return ResChunk.wrap(type, 16, header.toByteArray(), body.toByteArray())
    }

    private fun element(element: Element, pool: StringPool, resourceMapCount: kotlin.Int): ByteArray {
        val header = Le()
        header.u32(1) // lineNumber
        header.u32(-1) // no comment

        val start = Le()
        start.u32(-1) // no namespace on element names in a manifest
        start.u32(pool[element.name])
        start.u16(20) // attributeStart, relative to this struct
        start.u16(20) // attributeSize
        start.u16(element.attributes.size)
        start.u16(0) // id attribute index, 1-based, 0 for none
        start.u16(0) // class attribute index
        start.u16(0) // style attribute index

        for (attribute in element.attributes) {
            // An attribute with an id is namespaced; `package` is not, and is the only one here.
            start.u32(if (attribute.resId != null) pool[ANDROID_NS] else -1)
            start.u32(pool[attribute.name])
            when (val value = attribute.value) {
                is Value.Str -> {
                    val index = pool[value.value]
                    start.u32(index) // rawValue, which tooling prints as Raw:
                    typedValue(start, TYPE_STRING, index)
                }
                // A non-string has no source text to keep, and -1 says so.
                is Value.Int -> {
                    start.u32(-1)
                    typedValue(start, TYPE_INT_DEC, value.value)
                }
                is Value.Bool -> {
                    start.u32(-1)
                    typedValue(start, TYPE_INT_BOOLEAN, if (value.value) -1 else 0)
                }
                is Value.Ref -> {
                    start.u32(-1)
                    typedValue(start, TYPE_REFERENCE, value.id)
                }
            }
        }

        val out = Le()
        out.bytes(ResChunk.wrap(ResChunk.TYPE_XML_START_ELEMENT, 16, header.toByteArray(), start.toByteArray()))
        for (child in element.children) out.bytes(element(child, pool, resourceMapCount))

        val end = Le()
        end.u32(-1)
        end.u32(pool[element.name])
        out.bytes(ResChunk.wrap(ResChunk.TYPE_XML_END_ELEMENT, 16, header.toByteArray(), end.toByteArray()))
        return out.toByteArray()
    }

    private fun typedValue(out: Le, dataType: kotlin.Int, data: kotlin.Int) {
        out.u16(8) // size of this struct
        out.u8(0) // res0, always zero
        out.u8(dataType)
        out.u32(data)
    }
}
