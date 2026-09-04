package com.haraldmue.velin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun primaryNavigationOrderIsStable() {
        assertEquals(
            listOf("Home", "Search", "Library"),
            Destination.entries.map { it.label },
        )
    }
}
