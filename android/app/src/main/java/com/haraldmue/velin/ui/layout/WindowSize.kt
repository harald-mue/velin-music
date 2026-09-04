package com.haraldmue.velin.ui.layout

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

enum class VelinWidthClass {
    Compact,
    Medium,
    Expanded,
}

@Composable
@ReadOnlyComposable
fun velinWidthClass(): VelinWidthClass = widthClassFor(LocalConfiguration.current.screenWidthDp)

@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

internal fun widthClassFor(widthDp: Int): VelinWidthClass = when {
    widthDp < 600 -> VelinWidthClass.Compact
    widthDp < 840 -> VelinWidthClass.Medium
    else -> VelinWidthClass.Expanded
}

internal fun usesNavigationRail(widthClass: VelinWidthClass, landscape: Boolean): Boolean =
    widthClass == VelinWidthClass.Expanded || (landscape && widthClass != VelinWidthClass.Compact)

internal fun albumGridColumns(widthClass: VelinWidthClass): Int = when (widthClass) {
    VelinWidthClass.Compact -> 2
    VelinWidthClass.Medium -> 3
    VelinWidthClass.Expanded -> 4
}

internal fun usesSplitDetail(widthClass: VelinWidthClass, landscape: Boolean): Boolean =
    landscape && widthClass != VelinWidthClass.Compact
