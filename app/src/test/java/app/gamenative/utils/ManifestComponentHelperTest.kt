package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestComponentHelperTest {
    private val listed = setOf("dxvk-2.6.1-gplasync", "dxvk-2.7.1", "vkd3d-3.0b")

    @Test
    fun keepsADxvkBuildThatCanBeInstalled() {
        assertEquals(
            "dxvk-2.7.1",
            ManifestComponentHelper.selectAvailableComponent("dxvk-2.7.1", "dxvk-2.6.1-gplasync", listed),
        )
    }

    @Test
    fun fallsBackWhenTheSelectedBuildIsOnlyAContentProfileId() {
        assertEquals(
            "dxvk-2.6.1-gplasync",
            ManifestComponentHelper.selectAvailableComponent(
                "dxvk-2.7.1-1-gplasync-0",
                "dxvk-2.6.1-gplasync",
                listed,
            ),
        )
    }
}
