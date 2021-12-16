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
package com.naman14.timberx.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.list.listItems
import com.naman14.timberx.R
import com.naman14.timberx.models.MediaID.Companion.CALLER_SELF
import com.naman14.timberx.models.Song
import com.naman14.timberx.repository.PlaylistRepository
import com.naman14.timberx.constants.Constants.SONGS
import com.naman14.timberx.extensions.toQueue
import com.naman14.timberx.extensions.toast
import com.naman14.timberx.repository.SongsRepository
import org.koin.android.ext.android.inject

class AddToLinkDialog : DialogFragment(), CreatePlaylistDialog.PlaylistCreatedCallback {

    companion object {
        private const val TAG = "AddToLinkDialog"

        fun show(activity: FragmentActivity, song: Song? = null) {
            val songs: LongArray
            if (song == null) {
                songs = LongArray(0)
            } else {
                songs = LongArray(1)
                songs[0] = song.id
            }
            show(activity, songs)
        }

        fun show(activity: FragmentActivity, songList: LongArray) {
            val dialog = AddToLinkDialog().apply {
                arguments = Bundle().apply { putLongArray(SONGS, songList) }
            }
            dialog.show(activity.supportFragmentManager, TAG)
        }
    }

    var callback: () -> Unit? = {
        null
    }
    private val playlistRepository by inject<PlaylistRepository>()
    private val songsRepository by inject<SongsRepository>()

    @NonNull
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = activity ?: throw IllegalStateException("Not attached")
        val playlists = playlistRepository.getPlaylists(CALLER_SELF)
        val itemList = mutableListOf<String>().apply {
            add(getString(R.string.create_new_link))
            addAll(playlists.filter { it.name.startsWith("<Link>") }.map { it.name.substring(5) })
        }

        return MaterialDialog(context).show {
            title(R.string.add_to_link)
            listItems(items = itemList) { _, index, _ ->
                val songs = arguments?.getLongArray(SONGS) ?: return@listItems
                var playlistId = 0L;
                if (index == 0) {
                    val root = songsRepository.getSongsForIds(songs)[0];
                    playlistId = playlistRepository.createPlaylist("<Link> " + root.title + ":" + root.artist);
                    if (playlistId == -1L) {
                        context.toast(R.string.unable_create_link)
                    } else {
                        (targetFragment as? CreatePlaylistDialog.PlaylistCreatedCallback)?.onPlaylistCreated();
                    }
                }
                else {
                    playlistId = playlists[index - 1].id;
                }

                if (songs.isNotEmpty()) {
                    val inserted = playlistRepository.addToPlaylist(playlistId, songs)
                    val message = context.resources.getQuantityString(
                            R.plurals.NNNtrackstoplaylist, inserted, inserted)
                    context.toast(message)
                } else {
                    context.toast(R.string.playlist_created)
                }
            }
            onDismiss {
                // Make sure the DialogFragment dismisses as well
                this@AddToLinkDialog.dismiss()
            }
        }
    }

    override fun onPlaylistCreated() = dismiss()
}