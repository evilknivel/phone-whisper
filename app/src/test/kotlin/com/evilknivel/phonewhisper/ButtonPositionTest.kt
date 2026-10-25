package com.evilknivel.phonewhisper

import com.evilknivel.phonewhisper.Prefs.ButtonPosition
import com.evilknivel.phonewhisper.Prefs.buttonPosition
import com.evilknivel.phonewhisper.Prefs.keepScreenOn
import org.junit.Assert.*
import org.junit.Test

class ButtonPositionTest {

    @Test fun `default is the middle of the right edge`() {
        assertEquals(ButtonPosition(true, 0.5f), FakePrefs().buttonPosition(landscape = false))
        assertEquals(ButtonPosition(true, 0.5f), FakePrefs().buttonPosition(landscape = true))
    }

    @Test fun `portrait and landscape positions are kept apart`() {
        val prefs = FakePrefs(
            "button_right_edge_portrait" to false, "button_y_portrait" to 0.8f,
            "button_right_edge_landscape" to true, "button_y_landscape" to 0.2f,
        )
        assertEquals(ButtonPosition(false, 0.8f), prefs.buttonPosition(landscape = false))
        assertEquals(ButtonPosition(true, 0.2f), prefs.buttonPosition(landscape = true))
    }

    @Test fun `landscape starts from the portrait spot until it is placed`() {
        val prefs = FakePrefs("button_right_edge_portrait" to false, "button_y_portrait" to 0.3f)
        assertEquals(ButtonPosition(false, 0.3f), prefs.buttonPosition(landscape = true))
    }

    @Test fun `height is clamped to the screen`() {
        assertEquals(1f, FakePrefs("button_y_portrait" to 1.7f).buttonPosition(false).yFraction)
    }

    @Test fun `keep screen on is the default`() {
        assertTrue(FakePrefs().keepScreenOn())
        assertFalse(FakePrefs(Prefs.KEEP_SCREEN_ON to false).keepScreenOn())
    }
}
