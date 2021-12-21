/*
 * Copyright (c) 2019 Naman Dwivedi.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package com.naman14.timberx.playback

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
import android.util.Log
import androidx.annotation.Nullable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.material.snackbar.Snackbar
import com.naman14.timberx.R
import com.naman14.timberx.constants.Constants
import com.naman14.timberx.constants.Constants.ACTION_NEXT
import com.naman14.timberx.constants.Constants.ACTION_NOW_PLAYING
import com.naman14.timberx.constants.Constants.ACTION_PREVIOUS
import com.naman14.timberx.constants.Constants.ACTION_TOGGLE_REPEAT
import com.naman14.timberx.constants.Constants.ACTION_TOGGLE_SHUFFLE
import com.naman14.timberx.constants.Constants.ALBUM_ID
import com.naman14.timberx.constants.Constants.APP_PACKAGE_NAME
import com.naman14.timberx.constants.Constants.ARTIST_ID
import com.naman14.timberx.constants.Constants.PLAYING
import com.naman14.timberx.constants.Constants.PLAYLIST_ID
import com.naman14.timberx.constants.Constants.POSITION
import com.naman14.timberx.constants.Constants.REPEAT_MODE
import com.naman14.timberx.constants.Constants.SHUFFLE_MODE
import com.naman14.timberx.constants.Constants.SONG
import com.naman14.timberx.constants.Constants.SONG_METADATA
import com.naman14.timberx.db.QueueEntity
import com.naman14.timberx.db.QueueHelper
import com.naman14.timberx.extensions.*
import com.naman14.timberx.models.MediaID
import com.naman14.timberx.models.MediaID.Companion.CALLER_OTHER
import com.naman14.timberx.models.MediaID.Companion.CALLER_SELF
import com.naman14.timberx.notifications.Notifications
import com.naman14.timberx.permissions.PermissionsManager
import com.naman14.timberx.playback.players.OnNowPlayingListener
import com.naman14.timberx.playback.players.SongPlayer
import com.naman14.timberx.repository.AlbumRepository
import com.naman14.timberx.repository.ArtistRepository
import com.naman14.timberx.repository.GenreRepository
import com.naman14.timberx.repository.PlaylistRepository
import com.naman14.timberx.repository.SongsRepository
import com.naman14.timberx.sdl.SdlService
import com.naman14.timberx.ui.viewmodels.MainViewModel
import com.naman14.timberx.util.MusicUtils
import com.naman14.timberx.util.Utils.EMPTY_ALBUM_ART_URI
import io.reactivex.functions.Consumer
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.standalone.KoinComponent
import timber.log.Timber
import timber.log.Timber.d as log

// TODO pull out media logic to separate class to make this more readable
class TimberMusicService : MediaBrowserServiceCompat(), KoinComponent, LifecycleOwner {

    companion object {
        const val MEDIA_ID_ARG = "MEDIA_ID"
        const val MEDIA_TYPE_ARG = "MEDIA_TYPE"
        const val MEDIA_CALLER = "MEDIA_CALLER"
        const val MEDIA_ID_ROOT = -1
        const val TYPE_ALL_ARTISTS = 0
        const val TYPE_ALL_ALBUMS = 1
        const val TYPE_ALL_SONGS = 2
        const val TYPE_ALL_PLAYLISTS = 3
        const val TYPE_SONG = 9
        const val TYPE_ALBUM = 10
        const val TYPE_ARTIST = 11
        const val TYPE_PLAYLIST = 12
        const val TYPE_LINK = 13
        const val TYPE_ALL_FOLDERS = 14
        const val TYPE_ALL_GENRES = 15
        const val TYPE_GENRE = 16

        const val NOTIFICATION_ID = 888
    }

    private val TAG = "Timber"

    private val notifications by inject<Notifications>()
    private val albumRepository by inject<AlbumRepository>()
    private val artistRepository by inject<ArtistRepository>()
    private val songsRepository by inject<SongsRepository>()
    private val genreRepository by inject<GenreRepository>()
    private val playlistRepository by inject<PlaylistRepository>()
    //private val sdlService by inject<SdlService>()

    private lateinit var player: SongPlayer
    private val queueHelper by inject<QueueHelper>()
    private val permissionsManager by inject<PermissionsManager>()

    private lateinit var becomingNoisyReceiver: BecomingNoisyReceiver
    private val lifecycle = LifecycleRegistry(this)

    override fun getLifecycle() = lifecycle

    override fun onCreate() {
        super.onCreate()
        lifecycle.currentState = Lifecycle.State.RESUMED
        log("onCreate()")

        // We get it here so we don't end up lazy-initializing it from a non-UI thread.
        player = get()

        // We wait until the permission is granted to set the initial queue.
        // This observable will immediately emit if the permission is already granted.
        permissionsManager.requestStoragePermission(waitForGranted = true)
                .subscribe(Consumer {
                    GlobalScope.launch(IO) {
                        player.setQueue()
                    }
                })
                .attachLifecycle(this)

        sessionToken = player.getSession().sessionToken
        becomingNoisyReceiver = BecomingNoisyReceiver(this, sessionToken!!)

        player.onPlayingState { isPlaying ->
            if (isPlaying) {
                becomingNoisyReceiver.register()
                startForeground(NOTIFICATION_ID, notifications.buildNotification(getSession()))
            } else {
                becomingNoisyReceiver.unregister()
                stopForeground(false)
                saveCurrentData()
            }
            notifications.updateNotification(player.getSession())
        }

        player.onCompletion {
            notifications.updateNotification(player.getSession())
        }

        val context = this
        val initialIntent = Intent(context, SdlService::class.java)
        context.startService(initialIntent)
        player.setOnNowPlayingListener(object : OnNowPlayingListener {
            override fun onNowPlaying(metadata: MediaMetadataCompat, position: Long, playing: Boolean) {
                val actionIntent = Intent(context, SdlService::class.java).apply {
                    action = ACTION_NOW_PLAYING
                }
                actionIntent.putExtra(SONG_METADATA, metadata)
                actionIntent.putExtra(POSITION, position)
                actionIntent.putExtra(PLAYING, playing)

                val mediaSession = player.getSession()
                val controller = mediaSession.controller
                actionIntent.putExtra(SHUFFLE_MODE, controller.shuffleMode)
                actionIntent.putExtra(REPEAT_MODE, controller.repeatMode)

                log("EVENT_NOW_PLAYING: ${metadata.description}, ${metadata.getString(METADATA_KEY_ALBUM_ART_URI)}")
                context.startService(actionIntent)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand(): ${intent?.action}")
        if (intent == null) {
            return START_STICKY
        }

        val mediaSession = player.getSession()
        val controller = mediaSession.controller

        when (intent.action) {
            Constants.ACTION_PLAY_PAUSE -> {
                controller.playbackState?.let { playbackState ->
                    when {
                        playbackState.isPlaying -> controller.transportControls.pause()
                        playbackState.isPlayEnabled -> controller.transportControls.play()
                    }
                }
            }
            Constants.ACTION_PLAY -> {
                controller.transportControls.play()
            }
            Constants.ACTION_SHUFFLE_ALL -> {
                val allSongs = songsRepository.loadSongs("SDL")
                Timber.i("all songs length: %s", allSongs.size)
                val songQueue = MusicUtils.shuffleWithLinks(allSongs, playlistRepository)
                val extras = getExtraBundle(songQueue.toSongIds(), getString(R.string.all_songs))
                Timber.i("queue length: %s", songQueue.size)
                if (songQueue.isEmpty()) {
                    Timber.w(getString(R.string.shuffle_no_songs_error))
                } else {
                    controller.transportControls.playFromMediaId(songQueue[0].mediaId, extras)
                    controller.transportControls.seekTo(0L)
                }
            }
            Constants.ACTION_PLAY_ARTIST -> {
                if (intent.hasExtra(ARTIST_ID)) {
                    val artistID = intent.getLongExtra(ARTIST_ID, -1)
                    val songs = artistRepository.getSongsForArtist(artistID, "SDL")
                    //val songQueue = MusicUtils.shuffleWithLinks(allSongs, playlistRepository)
                    if (songs.isEmpty()) {
                        Timber.w("No songs for artist with ID " + artistID)
                    } else {
                        val extras = getExtraBundle(songs.toSongIds(), artistRepository.getArtist(artistID).name)
                        controller.transportControls.playFromMediaId(songs[0].mediaId, extras)
                        controller.transportControls.seekTo(0L)
                    }
                }
            }
            Constants.ACTION_PLAY_ALBUM -> {
                if (intent.hasExtra(ALBUM_ID)) {
                    val albumID = intent.getLongExtra(ALBUM_ID, -1)
                    val songs = albumRepository.getSongsForAlbum(albumID, "SDL").sortedBy { it.trackNumber }
                    //val songQueue = MusicUtils.shuffleWithLinks(allSongs, playlistRepository)
                    if (songs.isEmpty()) {
                        Timber.w("No songs for album with ID " + albumID)
                    } else {
                        val extras = getExtraBundle(songs.toSongIds(), albumRepository.getAlbum(albumID).title)
                        controller.transportControls.playFromMediaId(songs[0].mediaId, extras)
                        controller.transportControls.seekTo(0L)
                    }
                }
            }
            Constants.ACTION_PLAY_PLAYLIST -> {
                if (intent.hasExtra(PLAYLIST_ID)) {
                    val playlistID = intent.getLongExtra(PLAYLIST_ID, -1)
                    val songs = playlistRepository.getSongsInPlaylist(playlistID, "SDL")
                    //val songQueue = MusicUtils.shuffleWithLinks(allSongs, playlistRepository)
                    if (songs.isEmpty()) {
                        Timber.w("No songs for playlist with ID " + playlistID)
                    } else {
                        Timber.w(songs.size.toString() + " songs for playlist with ID " + playlistID)
                        val extras = getExtraBundle(songs.toSongIds(), playlistRepository.getPlaylists("SDL").find { it.id == playlistID }!!.name)
                        controller.transportControls.playFromMediaId(songs[0].mediaId, extras)
                        controller.transportControls.seekTo(0L)
                    }
                }
            }
            ACTION_NEXT -> {
                controller.transportControls.skipToNext()
            }
            ACTION_PREVIOUS -> {
                controller.transportControls.skipToPrevious()
            }
            ACTION_TOGGLE_SHUFFLE -> {
                val shuffleMode = when (controller.shuffleMode) {
                    PlaybackStateCompat.SHUFFLE_MODE_ALL -> PlaybackStateCompat.SHUFFLE_MODE_NONE
                    else -> PlaybackStateCompat.SHUFFLE_MODE_ALL
                }
                Timber.i("SHUFFLE MODE: " + shuffleMode)
                controller.transportControls.setShuffleMode(shuffleMode)
            }
            ACTION_TOGGLE_REPEAT -> {
                val repeatMode = when (controller.repeatMode) {
                    PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ONE
                    PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_NONE
                    else -> PlaybackStateCompat.REPEAT_MODE_ALL
                }
                Timber.i("REPEAT MODE: " + repeatMode)
                controller.transportControls.setRepeatMode(repeatMode)
            }
        }

        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        log("onDestroy()")
        saveCurrentData()
        player.release()
        super.onDestroy()
    }

    //media browser
    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        result.detach()

        // Wait to load media item children until we have the storage permission, this prevents crashes
        // and allows us to automatically finish loading once the permission is granted by the user.
        permissionsManager.requestStoragePermission(waitForGranted = true)
                .subscribe(Consumer {
                    GlobalScope.launch(Main) {
                        val mediaItems = withContext(IO) {
                            loadChildren(parentId)
                        }
                        result.sendResult(mediaItems)
                    }
                })
                .attachLifecycle(this)
    }

    @Nullable
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot? {
        val caller = if (clientPackageName == APP_PACKAGE_NAME) {
            CALLER_SELF
        } else {
            CALLER_OTHER
        }
        return MediaBrowserServiceCompat.BrowserRoot(MediaID(MEDIA_ID_ROOT.toString(), null, caller).asString(), null)
    }

    private fun addMediaRoots(mMediaRoot: MutableList<MediaBrowserCompat.MediaItem>, caller: String) {
        mMediaRoot.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().apply {
                    setMediaId(MediaID(TYPE_ALL_ARTISTS.toString(), null, caller).asString())
                    setTitle(getString(R.string.artists))
                    setIconUri(EMPTY_ALBUM_ART_URI.toUri())
                    setSubtitle(getString(R.string.artists))
                }.build(), FLAG_BROWSABLE
        ))

        mMediaRoot.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().apply {
                    setMediaId(MediaID(TYPE_ALL_ALBUMS.toString(), null, caller).asString())
                    setTitle(getString(R.string.albums))
                    setIconUri(EMPTY_ALBUM_ART_URI.toUri())
                    setSubtitle(getString(R.string.albums))
                }.build(), FLAG_BROWSABLE
        ))

        mMediaRoot.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().apply {
                    setMediaId(MediaID(TYPE_ALL_SONGS.toString(), null, caller).asString())
                    setTitle(getString(R.string.songs))
                    setIconUri(EMPTY_ALBUM_ART_URI.toUri())
                    setSubtitle(getString(R.string.songs))
                }.build(), FLAG_BROWSABLE
        ))

        mMediaRoot.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().apply {
                    setMediaId(MediaID(TYPE_ALL_PLAYLISTS.toString(), null, caller).asString())
                    setTitle(getString(R.string.playlists))
                    setIconUri(EMPTY_ALBUM_ART_URI.toUri())
                    setSubtitle(getString(R.string.playlists))
                }.build(), FLAG_BROWSABLE
        ))

        mMediaRoot.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().apply {
                    setMediaId(MediaID(TYPE_ALL_GENRES.toString(), null, caller).asString())
                    setTitle(getString(R.string.genres))
                    setIconUri(EMPTY_ALBUM_ART_URI.toUri())
                    setSubtitle(getString(R.string.genres))
                }.build(), FLAG_BROWSABLE
        ))
    }

    private fun loadChildren(parentId: String): ArrayList<MediaBrowserCompat.MediaItem> {
        val mediaItems = ArrayList<MediaBrowserCompat.MediaItem>()
        val mediaIdParent = MediaID().fromString(parentId)

        val mediaType = mediaIdParent.type
        val mediaId = mediaIdParent.mediaId
        val caller = mediaIdParent.caller

        if (mediaType == MEDIA_ID_ROOT.toString()) {
            addMediaRoots(mediaItems, caller!!)
        } else {
            when (mediaType?.toInt() ?: 0) {
                TYPE_ALL_ARTISTS -> {
                    mediaItems.addAll(artistRepository.getAllArtists(caller))
                }
                TYPE_ALL_ALBUMS -> {
                    mediaItems.addAll(albumRepository.getAllAlbums(caller))
                }
                TYPE_ALL_SONGS -> {
                    mediaItems.addAll(songsRepository.loadSongs(caller))
                }
                TYPE_ALL_GENRES -> {
                    mediaItems.addAll(genreRepository.getAllGenres(caller))
                }
                TYPE_ALL_PLAYLISTS -> {
                    mediaItems.addAll(playlistRepository.getPlaylists(caller))
                }
                TYPE_ALBUM -> {
                    mediaId?.let {
                        mediaItems.addAll(albumRepository.getSongsForAlbum(it.toLong(), caller))
                    }
                }
                TYPE_ARTIST -> {
                    mediaId?.let {
                        mediaItems.addAll(artistRepository.getSongsForArtist(it.toLong(), caller))
                    }
                }
                TYPE_PLAYLIST -> {
                    mediaId?.let {
                        mediaItems.addAll(playlistRepository.getSongsInPlaylist(it.toLong(), caller))
                    }
                }
                TYPE_GENRE -> {
                    mediaId?.let {
                        mediaItems.addAll(genreRepository.getSongsForGenre(it.toLong(), caller))
                    }
                }
            }
        }

        return if (caller == CALLER_SELF) {
            mediaItems
        } else {
            mediaItems.toRawMediaItems()
        }
    }

    private fun saveCurrentData() {
        GlobalScope.launch(IO) {
            val mediaSession = player.getSession()
            val controller = mediaSession.controller
            if (controller == null ||
                    controller.playbackState == null ||
                    controller.playbackState.state == STATE_NONE) {
                return@launch
            }

            val queue = controller.queue
            Log.i("Debugmsg", "saveCurrentData: " + queue[0].description + ", " + queue[0].queueId + ", " + queue.toIDList()[0])
            val currentId = controller.metadata?.getString(METADATA_KEY_MEDIA_ID)
            queueHelper.updateQueueSongs(queue?.toIDList(), currentId?.toLong())

            val queueEntity = QueueEntity().apply {
                this.currentId = currentId?.toLong()
                currentSeekPos = controller.playbackState?.position
                repeatMode = controller.repeatMode
                shuffleMode = controller.shuffleMode
                playState = controller.playbackState?.state
                queueTitle = controller.queueTitle?.toString() ?: getString(R.string.all_songs)
            }
            queueHelper.updateQueueData(queueEntity)
        }
    }
}
