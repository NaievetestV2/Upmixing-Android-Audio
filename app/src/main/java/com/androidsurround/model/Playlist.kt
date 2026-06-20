package com.androidsurround.model

import android.net.Uri
import java.util.UUID

data class PlaylistItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val title: String = "",
    val durationMs: Long = 0,
    val isVideo: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val items: List<PlaylistItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class RepeatMode { NONE, ONE, ALL }
