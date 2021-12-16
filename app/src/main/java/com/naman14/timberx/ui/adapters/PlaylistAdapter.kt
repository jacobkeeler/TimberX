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
package com.naman14.timberx.ui.adapters

import android.util.Log
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.naman14.timberx.R
import com.naman14.timberx.databinding.ItemPlaylistBinding
import com.naman14.timberx.models.Playlist
import com.naman14.timberx.extensions.inflateWithBinding
import com.naman14.timberx.repository.PlaylistRepository
import kotlinx.android.synthetic.main.item_playlist.view.*
import org.koin.android.ext.android.inject

class PlaylistAdapter constructor(playlistRepository: PlaylistRepository) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {
    var playlists: List<Playlist> = emptyList()
        private set
    var playlistRepository: PlaylistRepository? = playlistRepository
    fun updateData(playlists: List<Playlist>) {
        this.playlists = playlists
        notifyDataSetChanged()
    }

    fun deletePlaylist(playlist: Playlist) {
        Log.d("PlaylistFragment", "Deleting item " + this.playlists.indexOf(playlist) + ", " + playlist.name)
        var newPlaylists = this.playlists.toMutableList();
        newPlaylists.remove(playlist);
        playlistRepository?.deletePlaylist(playlist.id)
        updateData(newPlaylists);
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(parent.inflateWithBinding(R.layout.item_playlist))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(playlists[position], this)
    }

    override fun getItemCount() = playlists.size

    class ViewHolder constructor(var binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: Playlist, playlistAdapter: PlaylistAdapter) {
            binding.playlist = playlist
            binding.playlistAdapter = playlistAdapter
            binding.executePendingBindings()
        }
    }
}
