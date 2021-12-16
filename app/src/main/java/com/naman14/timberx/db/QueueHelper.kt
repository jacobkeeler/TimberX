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
package com.naman14.timberx.db

import android.util.Log
import com.naman14.timberx.extensions.equalsBy
import com.naman14.timberx.extensions.toSongEntityList
import com.naman14.timberx.repository.SongsRepository

interface QueueHelper {

    fun updateQueueSongs(
        queueSongs: LongArray?,
        currentSongId: Long?
    )

    fun updateQueueData(queueData: QueueEntity)
}

class RealQueueHelper(
    private val queueDao: QueueDao,
    private val songsRepository: SongsRepository
) : QueueHelper {

    override fun updateQueueSongs(queueSongs: LongArray?, currentSongId: Long?) {
        if (queueSongs == null || currentSongId == null) {
            return
        }
        val currentList = queueDao.getQueueSongsSync()
        //Log.i("Debugmsg", "updateQueueSongs: currentList, " + currentList[0].toString())
        Log.i("Debugmsg", "updateQueueSongs: queueSongs, " + queueSongs[0].toString())
        val songListToSave = queueSongs.toSongEntityList(songsRepository).sortedBy { queueSongs.indexOf(it.id) }

        val listsEqual = currentList.equalsBy(songListToSave) { left, right ->
            left.id == right.id
        }
        if (queueSongs.isNotEmpty() && !listsEqual) {
            Log.i("Debugmsg", "updateQueueSongs: Not Equal, " + songListToSave[0].toString())
            queueDao.clearQueueSongs()
            queueDao.insertAllSongs(songListToSave)
            setCurrentSongId(currentSongId)
        } else {
            setCurrentSongId(currentSongId)
        }
    }

    override fun updateQueueData(queueData: QueueEntity) = queueDao.insert(queueData)

    private fun setCurrentSongId(id: Long) = queueDao.setCurrentId(id)
}
