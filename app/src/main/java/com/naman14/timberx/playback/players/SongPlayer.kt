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
package com.naman14.timberx.playback.players

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.util.Log
import androidx.core.net.toUri
import com.naman14.timberx.R
import com.naman14.timberx.constants.Constants.ACTION_REPEAT_QUEUE
import com.naman14.timberx.constants.Constants.ACTION_REPEAT_SONG
import com.naman14.timberx.constants.Constants.REPEAT_MODE
import com.naman14.timberx.constants.Constants.SHUFFLE_MODE
import com.naman14.timberx.db.QueueDao
import com.naman14.timberx.db.QueueEntity
import com.naman14.timberx.extensions.asString
import com.naman14.timberx.extensions.isPlaying
import com.naman14.timberx.extensions.position
import com.naman14.timberx.extensions.toSongIDs
import com.naman14.timberx.models.Song
import com.naman14.timberx.repository.SongsRepository
import com.naman14.timberx.util.MusicUtils
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor
import kotlin.math.min

typealias OnIsPlaying = SongPlayer.(playing: Boolean) -> Unit

/**
 * A wrapper around [MusicPlayer] that specifically manages playing [Song]s and
 * links up with [Queue].
 *
 * @author Aidan Follestad (@afollestad)
 */
interface SongPlayer {

    fun setQueue(
        data: LongArray = LongArray(0),
        title: String = ""
    )

    fun setOnNowPlayingListener(onNowPlayingListener: OnNowPlayingListener)

    fun getSession(): MediaSessionCompat

    fun playSong()

    fun playSong(id: Long)

    fun playSong(song: Song)

    fun seekTo(position: Int)

    fun pause()

    fun nextSong()

    fun repeatSong()

    fun repeatQueue()

    fun previousSong()

    fun playNext(id: Long)

    fun swapQueueSongs(from: Int, to: Int)

    fun removeFromQueue(id: Long)

    fun stop()

    fun release()

    fun onPlayingState(playing: OnIsPlaying)

    fun onPrepared(prepared: OnPrepared<SongPlayer>)

    fun onError(error: OnError<SongPlayer>)

    fun onCompletion(completion: OnCompletion<SongPlayer>)

    fun updatePlaybackState(applier: PlaybackStateCompat.Builder.() -> Unit)

    fun setPlaybackState(state: PlaybackStateCompat)

    fun restoreFromQueueData(queueData: QueueEntity)
}

interface OnNowPlayingListener {
    fun onNowPlaying(metadata: MediaMetadataCompat, position: Long, playing: Boolean)
}

class RealSongPlayer(
    private val context: Application,
    private var musicPlayer: MusicPlayer,
    private var nextMusicPlayer: MusicPlayer,
    private val songsRepository: SongsRepository,
    private val queueDao: QueueDao,
    private val queue: Queue
) : SongPlayer, AudioManager.OnAudioFocusChangeListener {

    private var isInitialized: Boolean = false

    private var isPlayingCallback: OnIsPlaying = {}
    private var preparedCallback: OnPrepared<SongPlayer> = {}
    private var errorCallback: OnError<SongPlayer> = {}
    private var completionCallback: OnCompletion<SongPlayer> = {}
    private var nextMusicPlayerID = -1L
    private var onNowPlayingListener: OnNowPlayingListener? = null

    private var metadataBuilder = MediaMetadataCompat.Builder()
    private var stateBuilder = createDefaultPlaybackState()
    private var wasPlaying = false

    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    private var mediaSession = MediaSessionCompat(context, context.getString(R.string.app_name)).apply {
        setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(MediaSessionCallback(this, this@RealSongPlayer, songsRepository, queueDao))
        setPlaybackState(stateBuilder.build())

        val sessionIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val sessionActivityPendingIntent = PendingIntent.getActivity(context, 0, sessionIntent, 0)
        setSessionActivity(sessionActivityPendingIntent)
        isActive = true
    }

    init {
        queue.setMediaSession(mediaSession)

        musicPlayer.onPrepared {
            preparedCallback(this@RealSongPlayer)
            playSong()
            seekTo(getSession().position().toInt())
        }

        musicPlayer.onCompletion {
            completionCallback(this@RealSongPlayer)
            val controller = getSession().controller
            when (controller.repeatMode) {
                REPEAT_MODE_ONE -> {
                    controller.transportControls.sendCustomAction(ACTION_REPEAT_SONG, null)
                }
                REPEAT_MODE_ALL -> {
                    controller.transportControls.sendCustomAction(ACTION_REPEAT_QUEUE, null)
                }
                else -> nextSong()
            }
        }

        nextMusicPlayer.onPrepared {
            Log.i("debugmsg", "PREPARED NEXT SONG")
        }

        nextMusicPlayer.onCompletion {
            completionCallback(this@RealSongPlayer)
            val controller = getSession().controller
            when (controller.repeatMode) {
                REPEAT_MODE_ONE -> {
                    controller.transportControls.sendCustomAction(ACTION_REPEAT_SONG, null)
                }
                REPEAT_MODE_ALL -> {
                    controller.transportControls.sendCustomAction(ACTION_REPEAT_QUEUE, null)
                }
                else -> {
                    Log.i("debugmsg", "SOMEONE IS NOT PREPARED")
                    controller.transportControls.skipToNext()
                }
            }
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(this@RealSongPlayer, Handler(Looper.getMainLooper()))
                build()
            }
        }

    }

    override fun setQueue(
        data: LongArray,
        title: String
    ) {
        Timber.d("""setQueue: ${data.asString()} ("$title"))""")
        this.queue.ids = data
        this.queue.title = title
    }

    override fun setOnNowPlayingListener(onNowPlayingListener: OnNowPlayingListener) {
        this.onNowPlayingListener = onNowPlayingListener
    }

    override fun getSession(): MediaSessionCompat = mediaSession

    override fun playSong() {
        Timber.d("playSong()")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        queue.ensureCurrentId()

        if (isInitialized) {
            updatePlaybackState {
                setState(STATE_PLAYING, mediaSession.position(), 1F)
            }
            musicPlayer.play()
            return
        }
        musicPlayer.reset()

        musicPlayer.onPrepared {
            preparedCallback(this@RealSongPlayer)
            playSong()
            seekTo(getSession().position().toInt())
        }

        val path = MusicUtils.getSongUri(queue.currentSongId).toString()
        val isSourceSet = if (path.startsWith("content://")) {
            musicPlayer.setSource(path.toUri())
        } else {
            musicPlayer.setSource(path)
        }
        if (isSourceSet) {
            isInitialized = true
            musicPlayer.prepare()
        }

        if (queue.nextSongId != null && mediaSession.controller.repeatMode != REPEAT_MODE_ONE) {
            nextMusicPlayer.reset()

            nextMusicPlayer.onPrepared {
                Log.i("debugmsg", "PREPARED NEXT SONG")
            }
            val nextPath = MusicUtils.getSongUri(queue.nextSongId!!).toString()
            nextMusicPlayerID = queue.nextSongId!!
            val isNextSourceSet = if (nextPath.startsWith("content://")) {
                nextMusicPlayer.setSource(nextPath.toUri())
            } else {
                nextMusicPlayer.setSource(nextPath)
            }
            Timber.d("setNextMusicPlayer")
            if (isNextSourceSet) {
                nextMusicPlayer.getMediaPlayer().prepare()
                musicPlayer.setNextMusicPlayer(nextMusicPlayer)
            }
        }
    }

    override fun playSong(id: Long) {
        Timber.d("playSong(): $id")
        val song = songsRepository.getSongForId(id)
        playSong(song)
    }

    override fun playSong(song: Song) {
        Timber.d("playSong(): ${song.title}")
        if (queue.currentSongId != song.id) {
            if (nextMusicPlayerID == song.id && nextMusicPlayer.isPrepared()) {
                Log.i("dbugmsg", "PLAYING PREPARED SONG")
                val tmp = musicPlayer
                musicPlayer = nextMusicPlayer
                musicPlayer.setNextMusicPlayer(null);
                nextMusicPlayer = tmp
                nextMusicPlayer.reset()
                nextMusicPlayerID = -1L;
                //nextMusicPlayer = MusicPlayer
                isInitialized = true
            }
            else {
                Log.i("dbugmsg", "NOPE, NO PREPARED SONG")
                nextMusicPlayer.reset()
                isInitialized = false
            }
            updatePlaybackState {
                setState(STATE_STOPPED, 0, 1F)
            }
            queue.currentSongId = song.id
        }
        setMetaData(song)
        playSong()
    }

    override fun seekTo(position: Int) {
        Timber.d("seekTo(): $position")
        if (isInitialized) {
            musicPlayer.seekTo(position)
            updatePlaybackState {
                setState(
                        mediaSession.controller.playbackState.state,
                        position.toLong(),
                        1F
                )
            }
        }
    }

    override fun pause() {
        Timber.d("pause()")
        if (musicPlayer.isPlaying() && isInitialized) {
            musicPlayer.pause()
            updatePlaybackState {
                setState(STATE_PAUSED, mediaSession.position(), 1F)
            }
        }
    }

    override fun nextSong() {
        Timber.d("nextSong()")
        queue.nextSongId?.let {
            playSong(it)
        } ?: pause()
    }

    override fun repeatSong() {
        Timber.d("repeatSong()")
        updatePlaybackState {
            setState(STATE_STOPPED, 0, 1F)
        }
        playSong(queue.currentSong())
    }

    override fun repeatQueue() {
        Timber.d("repeatQueue()")
        if (queue.currentSongId == queue.lastId())
            playSong(queue.firstId())
        else {
            nextSong()
        }
    }

    override fun previousSong() {
        Timber.d("previousSong()")
        queue.previousSongId?.let(::playSong)
    }

    override fun playNext(id: Long) {
        Timber.d("playNext(): $id")
        queue.moveToNext(id)
    }

    override fun swapQueueSongs(from: Int, to: Int) {
        Timber.d("swapQueueSongs(): $from -> $to")
        queue.swap(from, to)
    }

    override fun removeFromQueue(id: Long) {
        Timber.d("removeFromQueue(): $id")
        queue.remove(id)
    }

    override fun stop() {
        Timber.d("stop()")
        musicPlayer.stop()
        updatePlaybackState {
            setState(STATE_NONE, 0, 1F)
        }
    }

    override fun release() {
        Timber.d("release()")
        mediaSession.apply {
            isActive = false
            release()
        }
        musicPlayer.release()
        queue.reset()
    }

    override fun onPlayingState(playing: OnIsPlaying) {
        this.isPlayingCallback = playing
    }

    override fun onPrepared(prepared: OnPrepared<SongPlayer>) {
        this.preparedCallback = prepared
    }

    override fun onError(error: OnError<SongPlayer>) {
        this.errorCallback = error
        musicPlayer.onError { throwable ->
            errorCallback(this@RealSongPlayer, throwable)
        }
    }

    override fun onCompletion(completion: OnCompletion<SongPlayer>) {
        this.completionCallback = completion
    }

    override fun updatePlaybackState(applier: PlaybackStateCompat.Builder.() -> Unit) {
        applier(stateBuilder)
        setPlaybackState(stateBuilder.build())
    }

    override fun setPlaybackState(state: PlaybackStateCompat) {
        mediaSession.setPlaybackState(state)
        state.extras?.let { bundle ->
            if (bundle.containsKey(REPEAT_MODE)) mediaSession.setRepeatMode(bundle.getInt(REPEAT_MODE))
            if (bundle.containsKey(SHUFFLE_MODE)) mediaSession.setShuffleMode(bundle.getInt(SHUFFLE_MODE))
        }
        if (state.isPlaying) {
            isPlayingCallback(this, true)
        } else {
            isPlayingCallback(this, false)
        }
        Log.i("debugmsg", "SET PLAYBACK STATE: " + state.isPlaying + ", " + state.position)
        val song = songsRepository.getSongForId(queue.currentSongId)
        val path = context.getExternalFilesDir(null)
        val artFile = File(path, "${song.albumId}.jpg")
        val mediaMetadataNoArt = MediaMetadataCompat.Builder().apply {
            putString(METADATA_KEY_ALBUM, song.album)
            putString(METADATA_KEY_ARTIST, song.artist)
            putString(METADATA_KEY_TITLE, song.title)
            putString(METADATA_KEY_ALBUM_ART_URI, song.albumId.toString())
            putString(METADATA_KEY_MEDIA_ID, song.id.toString())
            putString(METADATA_KEY_ART_URI, artFile.absolutePath)
            putLong(METADATA_KEY_DURATION, song.duration.toLong())
        }.build()
        onNowPlayingListener!!.onNowPlaying(mediaMetadataNoArt, state.position, state.isPlaying)
    }

    override fun restoreFromQueueData(queueData: QueueEntity) {
        queue.currentSongId = queueData.currentId ?: -1
        val playbackState = queueData.playState ?: STATE_NONE
        val currentPos = queueData.currentSeekPos ?: 0
        val repeatMode = queueData.repeatMode ?: REPEAT_MODE_NONE
        val shuffleMode = queueData.shuffleMode ?: SHUFFLE_MODE_NONE

        val queueIds = queueDao.getQueueSongsSync().toSongIDs()
        setQueue(queueIds, queueData.queueTitle)
        setMetaData(queue.currentSong())

        val extras = Bundle().apply {
            putInt(REPEAT_MODE, repeatMode)
            putInt(SHUFFLE_MODE, shuffleMode)
        }
        updatePlaybackState {
            setState(playbackState, currentPos, 1F)
            setExtras(extras)
        }
    }
    private fun setMetaData(song: Song) {
        // TODO make music utils injectable
        var artwork = MusicUtils.getAlbumArtBitmap(context, song.albumId)
        val mediaMetadata = MediaMetadataCompat.Builder().apply {
            putString(METADATA_KEY_ALBUM, song.album)
            putString(METADATA_KEY_ARTIST, song.artist)
            putString(METADATA_KEY_TITLE, song.title)
            putString(METADATA_KEY_ALBUM_ART_URI, song.albumId.toString())
            putBitmap(METADATA_KEY_ALBUM_ART, artwork)
            putString(METADATA_KEY_MEDIA_ID, song.id.toString())
            putLong(METADATA_KEY_DURATION, song.duration.toLong())
        }.build()
        Log.i("debugmsg", "UPDATE METADATA")
        val path = context.getExternalFilesDir(null)
        val artFile = File(path, "${song.albumId}.jpg")
        if (!artFile.exists() && artwork != null) {
            Log.i("debugmsg", "WRITING FILE DATA")
            val stream = ByteArrayOutputStream();
            //val wScale = 130.0f / artwork.width
            //val hScale = 130.0f / artwork.height
            //val scale = min(wScale, hScale)
            artwork.compress(Bitmap.CompressFormat.JPEG, 75, stream);
            val byteArray = stream.toByteArray();
            val output = FileOutputStream(artFile)
            output.write(byteArray)
            output.close()
            Log.i("debugmsg", "FINISHED WRITING FILE DATA")
        }
        val mediaMetadataNoArt = MediaMetadataCompat.Builder().apply {
            putString(METADATA_KEY_ALBUM, song.album)
            putString(METADATA_KEY_ARTIST, song.artist)
            putString(METADATA_KEY_TITLE, song.title)
            putString(METADATA_KEY_ALBUM_ART_URI, song.albumId.toString())
            putString(METADATA_KEY_ART_URI, artFile.absolutePath)
            putString(METADATA_KEY_MEDIA_ID, song.id.toString())
            putLong(METADATA_KEY_DURATION, song.duration.toLong())
        }.build()
        mediaSession.setMetadata(mediaMetadata)
        onNowPlayingListener!!.onNowPlaying(mediaMetadataNoArt, mediaSession.position(), mediaSession.isPlaying())
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (musicPlayer.isPlaying()) {
                    wasPlaying = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (musicPlayer.isPlaying()) {
                    wasPlaying = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlaying) {
                    wasPlaying = false
                    playSong()
                }
            }
        }
    }
}

private fun createDefaultPlaybackState(): PlaybackStateCompat.Builder {
    return PlaybackStateCompat.Builder().setActions(
            ACTION_PLAY
                    or ACTION_PAUSE
                    or ACTION_PLAY_FROM_SEARCH
                    or ACTION_PLAY_FROM_MEDIA_ID
                    or ACTION_PLAY_PAUSE
                    or ACTION_SKIP_TO_NEXT
                    or ACTION_SKIP_TO_PREVIOUS
                    or ACTION_SET_SHUFFLE_MODE
                    or ACTION_SET_REPEAT_MODE)
            .setState(STATE_NONE, 0, 1f)
}
