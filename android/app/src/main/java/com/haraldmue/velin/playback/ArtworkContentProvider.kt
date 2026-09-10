package com.haraldmue.velin.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.ArtworkRequestPolicy
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/** Serves paired cover files to Android Auto without putting tokens in browse URIs. */
class ArtworkContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only.")
        val ref = AutoArtworkUris.parse(uri) ?: throw FileNotFoundException("Unknown artwork.")
        val context = context ?: throw FileNotFoundException("Artwork is unavailable.")
        val credentials = AndroidKeyStoreCredentialStore(context).load()
            ?: throw FileNotFoundException("Pair this device before loading artwork.")
        val cacheDirectory = context.cacheDir.resolve("artwork")
        val cached = coilArtworkCacheFile(cacheDirectory, credentials, ref.coverId, ref.size)
        if (cached != null) {
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val httpUrl = runCatching {
            ArtworkRequestPolicy(credentials.serverUrl).urlFor(ref.coverId, ref.size)
        }.getOrNull() ?: throw FileNotFoundException("Invalid artwork.")
        val loader = AuthenticatedArtworkBitmapLoader(credentials)
        val bytes = try {
            loader.loadEmbeddedArtworkData(
                Uri.parse(httpUrl),
                MaxServedArtworkBytes,
            ).get(20, TimeUnit.SECONDS)
        } catch (_: Exception) {
            throw FileNotFoundException("Artwork could not be loaded.")
        } finally {
            loader.close()
        }
        val served = runCatching {
            writeCoilArtworkCache(cacheDirectory, httpUrl, bytes)
        }.getOrNull() ?: throw FileNotFoundException("Artwork could not be stored.")
        return ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        if (AutoArtworkUris.parse(uri) != null) "image/jpeg" else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
