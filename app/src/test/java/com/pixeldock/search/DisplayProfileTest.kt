package com.pixeldock.search

import com.pixeldock.search.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayProfileTest {

    @Test
    fun resolvePhoneAndFoldableCoverScreen() {
        assertEquals(DisplayProfile.COMPACT_PORTRAIT, DisplayProfile.resolve(412, 915))
        assertEquals(DisplayProfile.COMPACT_LANDSCAPE, DisplayProfile.resolve(915, 412))
        assertEquals(DisplayProfile.COMPACT_PORTRAIT, DisplayProfile.resolve(380, 840))
    }

    @Test
    fun resolveFoldableUnfoldedScreen() {
        assertEquals(DisplayProfile.EXPANDED_PORTRAIT, DisplayProfile.resolve(712, 840))
        assertEquals(DisplayProfile.EXPANDED_LANDSCAPE, DisplayProfile.resolve(840, 712))
    }

    @Test
    fun resolveTabletScreen() {
        assertEquals(DisplayProfile.TABLET_PORTRAIT, DisplayProfile.resolve(880, 1280))
        assertEquals(DisplayProfile.TABLET_LANDSCAPE, DisplayProfile.resolve(1280, 880))
    }
}
