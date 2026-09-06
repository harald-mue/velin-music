package com.haraldmue.velin.ui

import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.ui.library.LibraryUiState
import com.haraldmue.velin.ui.library.LibraryTab
import com.haraldmue.velin.ui.layout.VelinWidthClass
import com.haraldmue.velin.ui.layout.albumGridColumns
import com.haraldmue.velin.ui.layout.usesNavigationRail
import com.haraldmue.velin.ui.layout.usesSplitDetail
import com.haraldmue.velin.ui.layout.widthClassFor
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun primaryNavigationOrderIsStable() {
        assertEquals(
            listOf("Home", "Queue", "Library"),
            Destination.entries.map { it.label },
        )
    }

    @Test
    fun libraryTabsIncludeSearch() {
        assertEquals(
            listOf("Albums", "Artists", "Tracks", "Search"),
            LibraryTab.entries.map { it.label },
        )
    }

    @Test
    fun widthBreakpointsMatchPlan() {
        assertEquals(VelinWidthClass.Compact, widthClassFor(599))
        assertEquals(VelinWidthClass.Medium, widthClassFor(600))
        assertEquals(VelinWidthClass.Expanded, widthClassFor(840))
        assertEquals(2, albumGridColumns(VelinWidthClass.Compact))
        assertEquals(3, albumGridColumns(VelinWidthClass.Medium))
        assertEquals(4, albumGridColumns(VelinWidthClass.Expanded))
        assertEquals(false, usesNavigationRail(VelinWidthClass.Compact, landscape = true))
        assertEquals(true, usesNavigationRail(VelinWidthClass.Medium, landscape = true))
        assertEquals(true, usesNavigationRail(VelinWidthClass.Expanded, landscape = false))
        assertEquals(false, usesSplitDetail(VelinWidthClass.Compact, landscape = true))
        assertEquals(true, usesSplitDetail(VelinWidthClass.Medium, landscape = true))
    }

    @Test
    fun syncFailureDoesNotMarkReachableServerOffline() {
        assertEquals(
            ServerReachability.Connected,
            serverReachability(
                LibraryUiState(
                    loading = false,
                    status = ServerStatus("Velin", "ok", "test"),
                    error = "Snapshot verification failed.",
                ),
            ),
        )
        assertEquals(
            ServerReachability.Unreachable,
            serverReachability(LibraryUiState(loading = false, statusError = true)),
        )
    }
}
