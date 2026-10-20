package com.winlator.cmod.feature.library

import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GameSettingsNavTest {
    @Test fun horizontalStripStopsAtItsEndsWithoutEnteringActionsOrContent() {
        val selected = mutableListOf<Int>()
        val nav = GameSettingsNav().apply {
            active = true
            horizontalSections = true
            sidebarCount = 3
            onSelectSection = { selected += it }
        }
        repeat(5) { nav.dpad(PANE_DIR_RIGHT) }
        assertEquals(2, nav.sidebarIndex)
        assertFalse(nav.onActionRow)
        assertFalse(nav.inContent)
        assertEquals(listOf(1, 2), selected)
        repeat(5) { nav.dpad(PANE_DIR_LEFT) }
        assertEquals(0, nav.sidebarIndex)
        assertEquals(listOf(1, 2, 1, 0), selected)
    }

    @Test fun firstControllerEventRevealsFocusWithoutActivatingAnAction() {
        var saves = 0
        val nav = GameSettingsNav().apply {
            horizontalSections = true
            sidebarCount = 3
            sidebarIndex = 3
            actionCol = 1
            onSave = { saves++ }
        }
        nav.dpad(PANE_DIR_ACTIVATE)
        assertEquals(0, saves)
        nav.dpad(PANE_DIR_ACTIVATE)
        assertEquals(1, saves)
    }

    @Test fun contentDirectionsStayWithTheExistingContentNavigator() {
        val nav = GameSettingsNav().apply {
            active = true
            horizontalSections = true
            sidebarCount = 3
        }
        nav.dpad(PANE_DIR_DOWN)
        val before = nav.contentSignal
        nav.dpad(PANE_DIR_RIGHT)
        assertEquals(before + 1, nav.contentSignal)
        assertEquals(PANE_DIR_RIGHT, nav.contentDir)
        assertEquals(0, nav.sidebarIndex)
        nav.dpad(PANE_DIR_UP)
        assertEquals(before + 2, nav.contentSignal)
        assertEquals(PANE_DIR_UP, nav.contentDir)
    }
}
