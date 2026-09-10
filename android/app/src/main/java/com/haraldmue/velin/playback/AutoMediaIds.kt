package com.haraldmue.velin.playback

internal const val AutoRootId = "root"
internal const val AutoRecentlyAddedId = "category:recently-added"
internal const val AutoDiscoverId = "category:discover"
internal const val AutoAlbumsId = "category:albums"
internal const val AutoArtistsId = "category:artists"

internal val AutoBrowsableCategoryIds = listOf(
    AutoRootId,
    AutoRecentlyAddedId,
    AutoDiscoverId,
    AutoAlbumsId,
    AutoArtistsId,
)

internal sealed class AutoMediaId {
    data object Root : AutoMediaId()
    data object RecentlyAdded : AutoMediaId()
    data object Discover : AutoMediaId()
    data object Albums : AutoMediaId()
    data object Artists : AutoMediaId()
    data class Album(val id: String) : AutoMediaId()
    data class Artist(val id: String) : AutoMediaId()
    data class Track(val id: String) : AutoMediaId()
}

internal fun albumMediaId(id: String): String = "album:$id"

internal fun artistMediaId(id: String): String = "artist:$id"

internal fun trackMediaId(id: String): String = "track:$id"

internal fun parseAutoMediaId(mediaId: String): AutoMediaId? {
    val value = mediaId.trim()
    return when {
        value == AutoRootId -> AutoMediaId.Root
        value == AutoRecentlyAddedId -> AutoMediaId.RecentlyAdded
        value == AutoDiscoverId -> AutoMediaId.Discover
        value == AutoAlbumsId -> AutoMediaId.Albums
        value == AutoArtistsId -> AutoMediaId.Artists
        value.startsWith("album:") -> opaqueSuffix(value, "album:")?.let(AutoMediaId::Album)
        value.startsWith("artist:") -> opaqueSuffix(value, "artist:")?.let(AutoMediaId::Artist)
        value.startsWith("track:") -> opaqueSuffix(value, "track:")?.let(AutoMediaId::Track)
        else -> null
    }
}

internal fun parsePlaybackMediaId(mediaId: String): AutoMediaId? {
    parseAutoMediaId(mediaId)?.let { return it }
    return opaqueId(mediaId.trim())?.let(AutoMediaId::Track)
}

private fun opaqueSuffix(value: String, prefix: String): String? =
    opaqueId(value.removePrefix(prefix))

internal fun opaqueId(value: String): String? =
    value.takeIf { it.isNotEmpty() && it.length <= 128 && '/' !in it && '\n' !in it }

internal fun isAndroidAutoBrowserPackage(packageName: String): Boolean {
    val name = packageName.trim()
    return name == "com.google.android.projection.gearhead" ||
        name.startsWith("com.google.android.projection.gearhead.") ||
        name == "com.google.android.apps.googleaas.car" ||
        name.contains("android.car", ignoreCase = true)
}
