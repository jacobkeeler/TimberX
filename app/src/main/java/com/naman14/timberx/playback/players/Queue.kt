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
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat.REPEAT_MODE_ALL
import android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL
import com.naman14.timberx.R
import com.naman14.timberx.db.QueueDao
import com.naman14.timberx.extensions.moveElement
import com.naman14.timberx.extensions.toQueue
import com.naman14.timberx.models.Song
import com.naman14.timberx.repository.SongsRepository
import java.util.Random

const val SONG_ID_NONE = -1L

// Keeps track of previous shuffle index to reduce getting the same indices
// over and over. This is how large that tracker list can be.
private const val MAX_SHUFFLE_BUFFER_SIZE = 16

/**
 * Manages all things queues for [SongPlayer].
 *
 * @author Aidan Follestad (@afollestad)
 */
interface Queue {

    var ids: LongArray
    var title: String
    var currentSongId: Long
    val currentSongIndex: Int

    val previousSongId: Long?
    val nextSongIndex: Int?
    val nextSongId: Long?

    fun setMediaSession(session: MediaSessionCompat)

    fun swap(from: Int, to: Int)

    fun moveToNext(id: Long)

    fun firstId(): Long

    fun lastId(): Long

    fun remove(id: Long)

    fun asQueueItems(): List<MediaSessionCompat.QueueItem>

    fun currentSong(): Song

    fun ensureCurrentId()

    fun reset()
}

class RealQueue(
    private val context: Application,
    private val songsRepository: SongsRepository,
    private val queueDao: QueueDao
) : Queue {

    private lateinit var session: MediaSessionCompat

    override var ids = LongArray(0)
        set(value) {
            field = value
            if (session.controller.shuffleMode == SHUFFLE_MODE_ALL) {
                shuffledIds = ids.clone()
                shuffledIds.shuffle()
                shuffledIds = shuffledIds.toMutableList()
                        .moveElement(shuffledIds.indexOf(currentSongId), 0)
                        .toLongArray()
            }
            if (value.isNotEmpty()) {
                session.setQueue(asQueueItems())
            }
        }
    private var shuffledIds = LongArray(0)
        set(value) {
            field = value
            if (value.isNotEmpty() && session.controller.shuffleMode == SHUFFLE_MODE_ALL) {
                session.setQueue(asQueueItems())
            }
        }

    private fun activeIds(): LongArray
    {
        val controller = session.controller
        return if (controller.shuffleMode == SHUFFLE_MODE_ALL) shuffledIds else ids
    }

    override var title: String = context.getString(R.string.all_songs)
        set(value) {
            val previousValue = field
            field = if (value.isEmpty()) {
                context.getString(R.string.all_songs)
            } else {
                value
            }
            if (value != previousValue) {
                session.setQueueTitle(value)
            }
        }

    override var currentSongId: Long = SONG_ID_NONE
    override val currentSongIndex: Int
        get() {
            return activeIds().indexOf(currentSongId)
        }

    override val previousSongId: Long?
        get() {
            val previousIndex = currentSongIndex - 1
            return when {
                previousIndex >= 0 -> activeIds()[previousIndex]
                else -> null
            }
        }

    override val nextSongIndex: Int?
        get() {
            val nextIndex = currentSongIndex + 1

            return when {
                nextIndex < activeIds().size -> nextIndex
                session.controller.repeatMode == REPEAT_MODE_ALL -> 0
                else -> null
            }
        }
    override val nextSongId: Long?
        get() {
            val nextIndex = nextSongIndex
            return when {
                nextIndex != null -> activeIds()[nextIndex]
                else -> null
            }
        }

    override fun setMediaSession(session: MediaSessionCompat) {
        this.session = session
        val callback = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(shuffleMode: Int) {
                super.onShuffleModeChanged(shuffleMode)
                if (shuffleMode == SHUFFLE_MODE_ALL) {
                    shuffledIds = ids.clone()
                    shuffledIds.shuffle()
                    shuffledIds = shuffledIds.toMutableList()
                            .moveElement(shuffledIds.indexOf(currentSongId), 0)
                            .toLongArray()
                }
                else {
                    shuffledIds = LongArray(0)
                }

                if (activeIds().isNotEmpty()) {
                    session.setQueue(asQueueItems())
                }
            }
        }
        session.controller.registerCallback(callback)
        if (session.controller.shuffleMode == SHUFFLE_MODE_ALL) {
            shuffledIds = ids.clone()
            shuffledIds.shuffle()
            //currentSongId = shuffledIds[0]
        }
    }

    override fun swap(from: Int, to: Int) {
        val controller = session.controller
        if (controller.shuffleMode == SHUFFLE_MODE_ALL) {
            shuffledIds = shuffledIds.toMutableList()
                    .moveElement(from, to)
                    .toLongArray()
        }
        else {
            ids = ids.toMutableList()
                .moveElement(from, to)
                .toLongArray()
        }
    }

    override fun moveToNext(id: Long) {
        val nextIndex = currentSongIndex + 1
        val controller = session.controller
        if (controller.shuffleMode == SHUFFLE_MODE_ALL) {
            val list = shuffledIds.toMutableList().apply {
                remove(id)
                add(nextIndex, id)
            }
            shuffledIds = list.toLongArray()
        }
        else {
            val list = ids.toMutableList().apply {
                remove(id)
                add(nextIndex, id)
            }
            ids = list.toLongArray()
        }
    }

    override fun firstId() = activeIds().first()

    override fun lastId() = activeIds().last()

    override fun remove(id: Long) {
        val list = ids.toMutableList().apply {
            remove(id)
        }
        ids = list.toLongArray()
        val controller = session.controller
        if (controller.shuffleMode == SHUFFLE_MODE_ALL) {
            val list2 = shuffledIds.toMutableList().apply {
                remove(id)
            }
            shuffledIds = list2.toLongArray()
        }
    }

    override fun asQueueItems(): List<MediaSessionCompat.QueueItem> {
        return activeIds().toQueue(songsRepository)
    }

    override fun currentSong(): Song {
        return songsRepository.getSongForId(currentSongId)
    }

    override fun ensureCurrentId() {
        if (currentSongId == -1L) {
            val queueData = queueDao.getQueueDataSync()
            currentSongId = queueData?.currentId ?: SONG_ID_NONE
        }
    }

    override fun reset() {
        ids = LongArray(0)
        shuffledIds = LongArray(0)
        currentSongId = SONG_ID_NONE
    }

    /*private fun getShuffleIndex(): Int {
        val newIndex = shuffleRandom.nextInt(ids.size - 1)
        if (previousShuffles.contains(newIndex)) {
            return getShuffleIndex()
        }

        previousShuffles.add(newIndex)
        if (previousShuffles.size > MAX_SHUFFLE_BUFFER_SIZE) {
            previousShuffles.removeAt(0)
        }
        return newIndex
    }*/
}
