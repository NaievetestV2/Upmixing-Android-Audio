package com.androidsurround.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.androidsurround.model.Playlist
import com.androidsurround.model.PlaylistItem
import com.androidsurround.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistManager(private val context: Context) {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _queueItems = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val queueItems: StateFlow<List<PlaylistItem>> = _queueItems.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var _shuffled = false
    val isShuffled: Boolean get() = _shuffled

    private var _repeatMode = RepeatMode.NONE
    val repeatMode: RepeatMode get() = _repeatMode

    val currentItem: PlaylistItem?
        get() = if (_currentIndex.value in _queueItems.value.indices) _queueItems.value[_currentIndex.value] else null

    private val jsonFile = File(context.filesDir, "playlists.json")
    private val prefs = context.getSharedPreferences("playlist_prefs", Context.MODE_PRIVATE)

    init {
        loadPlaylists()
        restoreQueue()
    }

    // --- Named playlist CRUD ---

    fun createPlaylist(name: String) {
        val pl = Playlist(name = name)
        _playlists.value = _playlists.value + pl
        savePlaylists()
    }

    fun renamePlaylist(id: String, newName: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(name = newName, updatedAt = System.currentTimeMillis()) else it
        }
        savePlaylists()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        savePlaylists()
    }

    fun addToPlaylist(playlistId: String, item: PlaylistItem) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(
                items = it.items + item,
                updatedAt = System.currentTimeMillis(),
            ) else it
        }
        savePlaylists()
    }

    fun addCurrentToPlaylist(playlistId: String) {
        val item = currentItem ?: return
        addToPlaylist(playlistId, item)
    }

    fun removeFromPlaylist(playlistId: String, itemId: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(
                items = it.items.filter { i -> i.id != itemId },
                updatedAt = System.currentTimeMillis(),
            ) else it
        }
        savePlaylists()
    }

    // --- Queue management ---

    fun loadIntoQueue(items: List<PlaylistItem>, startIndex: Int = 0) {
        _queueItems.value = items.toList()
        _currentIndex.value = if (items.isNotEmpty()) startIndex.coerceIn(0, items.size - 1) else -1
        saveQueue()
    }

    fun appendToQueue(items: List<PlaylistItem>) {
        val current = _queueItems.value
        _queueItems.value = current + items
        if (_currentIndex.value < 0 && items.isNotEmpty()) _currentIndex.value = current.size
        saveQueue()
    }

    fun saveQueueAsPlaylist(name: String) {
        val items = _queueItems.value
        if (items.isEmpty()) return
        val pl = Playlist(
            name = name,
            items = items,
        )
        _playlists.value = _playlists.value + pl
        savePlaylists()
    }

    fun loadPlaylistIntoQueue(playlistId: String) {
        val pl = _playlists.value.find { it.id == playlistId } ?: return
        loadIntoQueue(pl.items)
    }

    private fun saveQueue() {
        prefs.edit().putString("queue_json", encodeItems(_queueItems.value).toString()).apply()
        prefs.edit().putInt("queue_index", _currentIndex.value).apply()
    }

    private fun restoreQueue() {
        val json = prefs.getString("queue_json", null) ?: return
        val idx = prefs.getInt("queue_index", -1)
        val items = decodeItems(json)
        if (items.isNotEmpty()) {
            _queueItems.value = items
            _currentIndex.value = idx.coerceIn(0, items.size - 1)
        }
    }

    // --- Navigation ---

    fun next(): PlaylistItem? {
        val items = _queueItems.value
        if (items.isEmpty()) return null
        val idx = _currentIndex.value
        return when {
            _shuffled -> {
                val next = (idx + 1).coerceAtMost(items.size - 1)
                if (next >= items.size) {
                    when (_repeatMode) {
                        RepeatMode.ALL -> { _currentIndex.value = 0; saveQueue(); items[0] }
                        RepeatMode.ONE -> { items[idx] }
                        RepeatMode.NONE -> { _currentIndex.value = -1; saveQueue(); null }
                    }
                } else {
                    _currentIndex.value = next; saveQueue(); items[next]
                }
            }
            else -> {
                val next = idx + 1
                if (next >= items.size) {
                    when (_repeatMode) {
                        RepeatMode.ALL -> { _currentIndex.value = 0; saveQueue(); items[0] }
                        RepeatMode.ONE -> { items[idx] }
                        RepeatMode.NONE -> { _currentIndex.value = -1; saveQueue(); null }
                    }
                } else {
                    _currentIndex.value = next; saveQueue(); items[next]
                }
            }
        }
    }

    fun previous(): PlaylistItem? {
        val items = _queueItems.value
        if (items.isEmpty()) return null
        val idx = _currentIndex.value
        val prev = if (_shuffled) {
            (idx - 1).coerceAtLeast(0)
        } else {
            idx - 1
        }
        if (prev < 0) {
            return when (_repeatMode) {
                RepeatMode.ALL -> { _currentIndex.value = items.size - 1; saveQueue(); items.last() }
                RepeatMode.ONE -> { items[idx] }
                RepeatMode.NONE -> null
            }
        }
        _currentIndex.value = prev; saveQueue(); return items[prev]
    }

    fun playItem(index: Int) {
        if (index in _queueItems.value.indices) {
            _currentIndex.value = index; saveQueue()
        }
    }

    // --- Shuffle & Repeat ---

    fun toggleShuffle() {
        _shuffled = !_shuffled
        if (_shuffled) {
            val items = _queueItems.value.toMutableList()
            val current = _currentIndex.value
            if (current in items.indices) {
                val curItem = items.removeAt(current)
                items.shuffle()
                items.add(0, curItem)
                _currentIndex.value = 0
            } else {
                items.shuffle()
                _currentIndex.value = -1
            }
            _queueItems.value = items
        } else {
            restoreQueue()
        }
        saveQueue()
    }

    fun cycleRepeatMode() {
        _repeatMode = when (_repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
    }

    // --- Persistence ---

    private fun loadPlaylists() {
        try {
            if (!jsonFile.exists()) return
            val text = jsonFile.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                list.add(parsePlaylist(arr.getJSONObject(i)))
            }
            _playlists.value = list
        } catch (e: Exception) {
            Log.e("PlaylistManager", "Failed to load playlists", e)
        }
    }

    private fun savePlaylists() {
        try {
            val arr = JSONArray()
            _playlists.value.forEach { arr.put(encodePlaylist(it)) }
            jsonFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("PlaylistManager", "Failed to save playlists", e)
        }
    }

    private fun encodePlaylist(pl: Playlist): JSONObject = JSONObject().apply {
        put("id", pl.id)
        put("name", pl.name)
        put("items", encodeItems(pl.items))
        put("createdAt", pl.createdAt)
        put("updatedAt", pl.updatedAt)
    }

    private fun parsePlaylist(obj: JSONObject): Playlist = Playlist(
        id = obj.getString("id"),
        name = obj.getString("name"),
        items = decodeItems(obj.getJSONArray("items")),
        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
    )

    private fun encodeItems(items: List<PlaylistItem>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri)
                put("title", item.title)
                put("durationMs", item.durationMs)
                put("isVideo", item.isVideo)
                put("addedAt", item.addedAt)
            })
        }
    }

    private fun decodeItems(arr: JSONArray): List<PlaylistItem> {
        val list = mutableListOf<PlaylistItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(PlaylistItem(
                id = obj.getString("id"),
                uri = obj.getString("uri"),
                title = obj.optString("title", ""),
                durationMs = obj.optLong("durationMs", 0),
                isVideo = obj.optBoolean("isVideo", false),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
            ))
        }
        return list
    }

    private fun decodeItems(json: String): List<PlaylistItem> {
        try {
            if (json.isBlank()) return emptyList()
            return decodeItems(JSONArray(json))
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun clearQueue() {
        _queueItems.value = emptyList()
        _currentIndex.value = -1
        saveQueue()
    }

    fun removeFromQueue(index: Int) {
        val items = _queueItems.value.toMutableList()
        if (index !in items.indices) return
        items.removeAt(index)
        _queueItems.value = items
        val ci = _currentIndex.value
        _currentIndex.value = when {
            items.isEmpty() -> -1
            ci >= items.size -> items.size - 1
            ci == index -> if (ci >= items.size) -1 else ci
            ci > index -> ci - 1
            else -> ci
        }
        saveQueue()
    }
}
