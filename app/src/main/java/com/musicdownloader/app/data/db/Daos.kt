package com.musicdownloader.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistWithSongs(id: Long): Flow<PlaylistWithSongs?>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistWithSongsOnce(id: Long): PlaylistWithSongs?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE playlistId = :playlistId ORDER BY orderIndex")
    fun getSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs WHERE playlistId = :playlistId")
    suspend fun countForPlaylist(playlistId: Long): Int

    @Insert
    suspend fun insert(song: SongEntity): Long

    @Insert
    suspend fun insertAll(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    @Delete
    suspend fun delete(song: SongEntity)

    @Delete
    suspend fun deleteAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE songs SET loudnessDb = :loudnessDb WHERE id = :id")
    suspend fun updateLoudness(id: Long, loudnessDb: Float)
}
