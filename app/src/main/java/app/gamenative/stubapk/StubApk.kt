package app.gamenative.stubapk

import app.gamenative.stubapk.AxmlWriter.Attribute
import app.gamenative.stubapk.AxmlWriter.Element
import app.gamenative.stubapk.AxmlWriter.Value

/**
 * Builds an unsigned APK that does nothing but appear in the launcher and hand a command back.
 *
 * This is how a Linux application gets an entry in the all-apps list, which Android offers no
 * other way to do: only an installed package appears there, so each application needs one. What
 * varies between stubs is the manifest and the icon; the code is the same trampoline every time,
 * which is why the caller passes in a dex rather than one being generated.
 */
object StubApk {

    /** The framework attribute ids the stub manifest uses, as aapt2 resolves them. */
    private object Attr {
        const val THEME = 0x01010000
        const val LABEL = 0x01010001
        const val ICON = 0x01010002
        const val NAME = 0x01010003
        const val EXPORTED = 0x01010010
        const val VALUE = 0x01010024
        const val HAS_CODE = 0x0101000c
        const val MIN_SDK_VERSION = 0x0101020c
        const val VERSION_CODE = 0x0101021b
        const val VERSION_NAME = 0x0101021c
        const val TARGET_SDK_VERSION = 0x01010270
    }

    private const val ICON_PATH = "res/icon.png"

    /**
     * [activityClass] is the trampoline in [dex], and [metadata] is handed to it through the
     * manifest -- the command to run, typically, which keeps a stub independent of any state in
     * the app that generated it.
     */
    data class Spec(
        val packageName: String,
        val label: String,
        val versionCode: Int = 1,
        val versionName: String = "1.0",
        val minSdkVersion: Int = 26,
        val targetSdkVersion: Int = 34,
        val activityClass: String,
        val metadata: Map<String, String> = emptyMap(),
        val iconPng: ByteArray,
        val dex: ByteArray,
    )

    fun build(spec: Spec): ByteArray {
        val zip = ZipWriter()
        zip.add("AndroidManifest.xml", AxmlWriter().encode(manifest(spec)))
        // Read by mapping the archive, so it cannot be compressed, and must be word aligned.
        zip.add("resources.arsc", ArscWriter.encode(spec.packageName, ICON_PATH), alignment = 4)
        zip.add(ICON_PATH, spec.iconPng)
        zip.add("classes.dex", spec.dex)
        return zip.finish()
    }

    private fun manifest(spec: Spec): Element {
        val metadata = spec.metadata.map { (name, value) ->
            Element(
                name = "meta-data",
                attributes = listOf(
                    Attribute("name", Attr.NAME, Value.Str(name)),
                    Attribute("value", Attr.VALUE, Value.Str(value)),
                ),
            )
        }

        val activity = Element(
            name = "activity",
            attributes = listOf(
                Attribute("name", Attr.NAME, Value.Str(spec.activityClass)),
                Attribute("label", Attr.LABEL, Value.Str(spec.label)),
                // Reachable from the launcher, so it has to say so on modern targets.
                Attribute("exported", Attr.EXPORTED, Value.Bool(true)),
                // No window of its own: it forwards an intent and finishes, and a theme with a
                // background would flash before it did.
                Attribute("theme", Attr.THEME, Value.Ref(android.R.style.Theme_Translucent_NoTitleBar)),
            ),
            children = listOf(
                Element(
                    name = "intent-filter",
                    children = listOf(
                        Element(
                            "action",
                            listOf(Attribute("name", Attr.NAME, Value.Str("android.intent.action.MAIN"))),
                        ),
                        Element(
                            "category",
                            listOf(Attribute("name", Attr.NAME, Value.Str("android.intent.category.LAUNCHER"))),
                        ),
                    ),
                ),
            ) + metadata,
        )

        return Element(
            name = "manifest",
            attributes = listOf(
                Attribute("versionCode", Attr.VERSION_CODE, Value.Int(spec.versionCode)),
                Attribute("versionName", Attr.VERSION_NAME, Value.Str(spec.versionName)),
                Attribute("package", null, Value.Str(spec.packageName)),
            ),
            children = listOf(
                Element(
                    name = "uses-sdk",
                    attributes = listOf(
                        Attribute("minSdkVersion", Attr.MIN_SDK_VERSION, Value.Int(spec.minSdkVersion)),
                        Attribute("targetSdkVersion", Attr.TARGET_SDK_VERSION, Value.Int(spec.targetSdkVersion)),
                    ),
                ),
                Element(
                    name = "application",
                    attributes = listOf(
                        Attribute("label", Attr.LABEL, Value.Str(spec.label)),
                        Attribute("icon", Attr.ICON, Value.Ref(ArscWriter.ICON_ID)),
                        Attribute("hasCode", Attr.HAS_CODE, Value.Bool(true)),
                    ),
                    children = listOf(activity),
                ),
            ),
        )
    }
}
