package com.haraldmue.velin.data.cache

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryCacheDao {
    @Query("SELECT * FROM cache_state WHERE namespace = :namespace")
    fun observeState(namespace: String): Flow<CacheStateEntity?>

    @Query("SELECT * FROM cache_state WHERE namespace = :namespace")
    suspend fun state(namespace: String): CacheStateEntity?

    @Upsert
    suspend fun upsertState(state: CacheStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArtists(artists: List<CachedArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlbums(albums: List<CachedAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTracks(tracks: List<CachedTrackEntity>)

    @Query(
        """
        SELECT artist.* FROM cached_artists AS artist
        INNER JOIN cache_state AS state
          ON state.namespace = artist.namespace
         AND state.active_generation = artist.generation
        WHERE artist.namespace = :namespace
        ORDER BY artist.server_order
        """,
    )
    fun artistsPagingSource(namespace: String): PagingSource<Int, CachedArtistEntity>

    @Query(
        """
        SELECT album.* FROM cached_albums AS album
        INNER JOIN cache_state AS state
          ON state.namespace = album.namespace
         AND state.active_generation = album.generation
        WHERE album.namespace = :namespace
        ORDER BY album.server_order
        """,
    )
    fun albumsPagingSource(namespace: String): PagingSource<Int, CachedAlbumEntity>

    @Query(
        """
        SELECT track.* FROM cached_tracks AS track
        INNER JOIN cache_state AS state
          ON state.namespace = track.namespace
         AND state.active_generation = track.generation
        WHERE track.namespace = :namespace
        ORDER BY track.server_order
        """,
    )
    fun tracksPagingSource(namespace: String): PagingSource<Int, CachedTrackEntity>

    @Query(
        """
        SELECT album.* FROM cached_albums AS album
        INNER JOIN cache_state AS state
          ON state.namespace = album.namespace
         AND state.active_generation = album.generation
        WHERE album.namespace = :namespace
          AND album.added_at_ms IS NOT NULL
        ORDER BY album.added_at_ms DESC, album.id
        LIMIT :limit
        """,
    )
    fun observeRecentlyAddedAlbums(namespace: String, limit: Int): Flow<List<CachedAlbumEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT album.* FROM cached_albums AS album
            INNER JOIN cache_state AS state
              ON state.namespace = album.namespace
             AND state.active_generation = album.generation
            WHERE album.namespace = :namespace AND album.id >= :startID
            ORDER BY album.id
            LIMIT :limit
        )
        UNION ALL
        SELECT * FROM (
            SELECT album.* FROM cached_albums AS album
            INNER JOIN cache_state AS state
              ON state.namespace = album.namespace
             AND state.active_generation = album.generation
            WHERE album.namespace = :namespace AND album.id < :startID
            ORDER BY album.id
            LIMIT :limit
        )
        LIMIT :limit
        """,
    )
    fun observeDiscoveryAlbums(
        namespace: String,
        startID: String,
        limit: Int,
    ): Flow<List<CachedAlbumEntity>>

    @Query(
        """
        SELECT track.* FROM cached_tracks AS track
        INNER JOIN cache_state AS state
          ON state.namespace = track.namespace
         AND state.active_generation = track.generation
        WHERE track.namespace = :namespace AND track.album_id = :albumId
        ORDER BY
          CASE WHEN track.disc_number IS NULL THEN 2147483647 ELSE track.disc_number END,
          CASE WHEN track.track_number IS NULL THEN 2147483647 ELSE track.track_number END,
          track.title COLLATE NOCASE,
          track.id
        """,
    )
    fun observeAlbumTracks(namespace: String, albumId: String): Flow<List<CachedTrackEntity>>

    @Query(
        """
        SELECT track.* FROM cached_tracks AS track
        INNER JOIN cache_state AS state
          ON state.namespace = track.namespace
         AND state.active_generation = track.generation
        WHERE track.namespace = :namespace AND track.artist_id = :artistId
        ORDER BY track.server_order
        """,
    )
    fun observeArtistTracks(namespace: String, artistId: String): Flow<List<CachedTrackEntity>>

    @Query(
        """
        SELECT album.* FROM cached_albums AS album
        INNER JOIN cache_state AS state
          ON state.namespace = album.namespace
         AND state.active_generation = album.generation
        WHERE album.namespace = :namespace AND album.id = :albumId
        """,
    )
    suspend fun activeAlbum(namespace: String, albumId: String): CachedAlbumEntity?

    @Query(
        """
        SELECT artist.* FROM cached_artists AS artist
        INNER JOIN cache_state AS state
          ON state.namespace = artist.namespace
         AND state.active_generation = artist.generation
        WHERE artist.namespace = :namespace AND artist.id = :artistId
        """,
    )
    suspend fun activeArtist(namespace: String, artistId: String): CachedArtistEntity?

    @Query("DELETE FROM cached_artists WHERE namespace = :namespace AND generation = :generation")
    suspend fun deleteArtistGeneration(namespace: String, generation: String)

    @Query("DELETE FROM cached_albums WHERE namespace = :namespace AND generation = :generation")
    suspend fun deleteAlbumGeneration(namespace: String, generation: String)

    @Query("DELETE FROM cached_tracks WHERE namespace = :namespace AND generation = :generation")
    suspend fun deleteTrackGeneration(namespace: String, generation: String)

    @Query("DELETE FROM cached_artists WHERE namespace = :namespace")
    suspend fun deleteAllArtists(namespace: String)

    @Query("DELETE FROM cached_albums WHERE namespace = :namespace")
    suspend fun deleteAllAlbums(namespace: String)

    @Query("DELETE FROM cached_tracks WHERE namespace = :namespace")
    suspend fun deleteAllTracks(namespace: String)

    @Query("DELETE FROM cache_state WHERE namespace = :namespace")
    suspend fun deleteState(namespace: String)

    @Query("DELETE FROM cached_artists WHERE namespace = :namespace AND generation != :generation")
    suspend fun deleteOtherArtistGenerations(namespace: String, generation: String)

    @Query("DELETE FROM cached_albums WHERE namespace = :namespace AND generation != :generation")
    suspend fun deleteOtherAlbumGenerations(namespace: String, generation: String)

    @Query("DELETE FROM cached_tracks WHERE namespace = :namespace AND generation != :generation")
    suspend fun deleteOtherTrackGenerations(namespace: String, generation: String)
}

@Database(
    entities = [
        CacheStateEntity::class,
        CachedArtistEntity::class,
        CachedAlbumEntity::class,
        CachedTrackEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LibraryCacheDatabase : RoomDatabase() {
    abstract fun libraryCacheDao(): LibraryCacheDao

    companion object {
        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_albums ADD COLUMN added_at_ms INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_albums_namespace_generation_added_at_ms " +
                        "ON cached_albums(namespace, generation, added_at_ms)",
                )
                db.execSQL(
                    "UPDATE cache_state SET active_revision = 'resync-v2:' || active_revision " +
                        "WHERE active_revision IS NOT NULL",
                )
            }
        }

        @Volatile
        private var instance: LibraryCacheDatabase? = null

        fun get(context: Context): LibraryCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryCacheDatabase::class.java,
                    "library-cache.db",
                ).addMigrations(Migration1To2).build().also { instance = it }
            }
    }
}
