package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamSourceCompatTest {
    @Test
    fun parseProtonTier_readsTheSummaryTier() {
        val body = """{"tier":"platinum","confidence":"strong","total":1205}"""
        assertEquals(ProtonTier.PLATINUM, SteamSourceCompatService.parseProtonTier(body))
        assertEquals(ProtonTier.BORKED, SteamSourceCompatService.parseProtonTier("""{"tier":"borked"}"""))
        assertEquals(ProtonTier.UNKNOWN, SteamSourceCompatService.parseProtonTier("{}"))
    }

    @Test
    fun parseSteamCategories_readsDeckAndSteamOs() {
        val body = """
            {"success":1,"results":{"resolved_category":3,"steamos_resolved_category":2}}
        """.trimIndent()
        val (deck, steamOs) = SteamSourceCompatService.parseSteamCategories(body)
        assertEquals(SteamCompatCategory.VERIFIED, deck)
        assertEquals(SteamCompatCategory.PLAYABLE, steamOs)
    }

    @Test
    fun parseSteamCategories_unknownWhenTheRequestDidNotSucceed() {
        val (deck, steamOs) = SteamSourceCompatService.parseSteamCategories("""{"success":0}""")
        assertEquals(SteamCompatCategory.UNKNOWN, deck)
        assertEquals(SteamCompatCategory.UNKNOWN, steamOs)
    }
}
