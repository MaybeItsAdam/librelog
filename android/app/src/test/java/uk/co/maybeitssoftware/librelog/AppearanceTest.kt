package uk.co.maybeitssoftware.librelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.maybeitssoftware.librelog.ui.Appearance

class AppearanceTest {
    @Test fun unsetIsTheDefault() = assertEquals(Appearance(), Appearance.from { null })

    @Test fun readsEachKey() {
        val stored = mapOf(
            "appearance.theme" to "dark",
            "appearance.accent" to "amber",
            "appearance.font" to "mono",
            "appearance.text_size" to "larger",
        )
        val a = Appearance.from { stored[it] }
        assertEquals(Appearance.Theme.Dark, a.theme)
        assertEquals(Appearance.Accent.Amber, a.accent)
        assertEquals(Appearance.ReadingFont.Mono, a.font)
        assertEquals(1.3f, a.textSize.scale)
        assertTrue(a.accent.inkOnFill)
    }

    @Test fun unknownValuesFallBack() {
        val a = Appearance.from { "chartreuse" }
        assertEquals(Appearance(), a)
    }
}
