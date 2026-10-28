package com.evilknivel.phonewhisper

import com.evilknivel.phonewhisper.BackgroundSettings.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSettingsTest {

    @Test
    fun `detects makers from manufacturer or brand`() {
        assertEquals(Vendor.XIAOMI, BackgroundSettings.vendor("Xiaomi", "POCO"))
        assertEquals(Vendor.XIAOMI, BackgroundSettings.vendor("", "Redmi"))
        assertEquals(Vendor.SAMSUNG, BackgroundSettings.vendor("samsung", "samsung"))
        assertEquals(Vendor.HONOR, BackgroundSettings.vendor("HONOR", "HONOR"))
        assertEquals(Vendor.HUAWEI, BackgroundSettings.vendor("HUAWEI", "HUAWEI"))
        assertEquals(Vendor.OPPO, BackgroundSettings.vendor("OnePlus", "OnePlus"))
        assertEquals(Vendor.OPPO, BackgroundSettings.vendor("realme", "realme"))
        assertEquals(Vendor.VIVO, BackgroundSettings.vendor("vivo", "iQOO"))
        assertEquals(Vendor.TRANSSION, BackgroundSettings.vendor("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(Vendor.OTHER, BackgroundSettings.vendor("Google", "google"))
    }

    @Test
    fun `every maker gets at least one step`() {
        for (vendor in Vendor.entries) {
            assertTrue(vendor.name, BackgroundSettings.steps(vendor, "com.example").isNotEmpty())
        }
    }

    @Test
    fun `xiaomi starts with autostart, others fall back to battery optimization`() {
        assertEquals("Allow Autostart", BackgroundSettings.steps(Vendor.XIAOMI, "com.example").first().title)
        assertTrue(BackgroundSettings.steps(Vendor.OTHER, "com.example").single().batteryOptimization)
    }

    @Test
    fun `recents lock only where the maker offers it`() {
        assertTrue(BackgroundSettings.hasRecentsLock(Vendor.XIAOMI))
        assertFalse(BackgroundSettings.hasRecentsLock(Vendor.SAMSUNG))
        assertFalse(BackgroundSettings.hasRecentsLock(Vendor.OTHER))
    }
}
