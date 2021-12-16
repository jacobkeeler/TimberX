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
package com.naman14.timberx.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.OnHeaderDecodedListener
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.naman14.timberx.R
import com.naman14.timberx.models.MediaID
import com.naman14.timberx.models.Song
import com.naman14.timberx.repository.PlaylistRepository
import timber.log.Timber
import java.io.FileNotFoundException
import android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI as AUDIO_URI
import timber.log.Timber.d as log


// TODO get rid of this and move things to respective repositories
object MusicUtils {

    fun getSongUri(id: Long): Uri {
        return ContentUris.withAppendedId(AUDIO_URI, id)
    }

    fun getRealPathFromURI(context: Context, contentUri: Uri): String {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        log("Querying $contentUri")
        return context.contentResolver.query(contentUri, projection, null, null, null)?.use {
            val dataIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            if (it.moveToFirst()) {
                it.getString(dataIndex)
            } else {
                ""
            }
        } ?: throw IllegalStateException("Unable to query $contentUri, system returned null.")
    }

    fun getAlbumArtBitmap(context: Context, albumId: Long?): Bitmap? {
        if (albumId == null) return null
        return try {
            //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            //    val source = ImageDecoder.createSource(context.contentResolver, Utils.getAlbumArtUri(albumId))
            //    val listener = OnHeaderDecodedListener { decoder, info, source -> decoder.setTargetSize(260, 260) }
            //    ImageDecoder.decodeBitmap(source, listener)
            //} else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, Utils.getAlbumArtUri(albumId))
            //}
        } catch (e: FileNotFoundException) {
            BitmapFactory.decodeResource(context.resources, R.drawable.icon)
        }
    }

    fun shuffleWithLinks(songs: List<Song>, playlistRepository: PlaylistRepository): List<Song> {
        var shuffled = songs.shuffled()
        var playlists = playlistRepository.getPlaylists(MediaID.CALLER_SELF)
        playlists = playlists.filter { it.name.startsWith("<Link>") };
        for (playlist in playlists) {
            val linkSongs = playlistRepository.getSongsInPlaylist(playlist.id, MediaID.CALLER_SELF);
            val root = linkSongs[0];
            val rootIndex = shuffled.indexOf(
                    shuffled.find { it.id == root.id }
            );
            Timber.i("rootIndex: %s", rootIndex)
            for (i in 1 until linkSongs.size) {
                val index = shuffled.indexOf(shuffled.find { it.id == linkSongs[i].id });
                if (index != -1) {
                    val dest = rootIndex + i;
                    Timber.i("Adding song: (" + index + ") " + shuffled[index].title + " at index: " + dest)
                    val editedQueue = shuffled.toMutableList();
                    if(dest < editedQueue.size) {
                        val temp = editedQueue[index];
                        editedQueue[index] = editedQueue[dest];
                        editedQueue[dest] = temp;
                    } else {
                        editedQueue.add(shuffled[index]);
                    }

                    shuffled = editedQueue;
                }
            }
        }
        return shuffled
    }
}
