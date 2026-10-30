package com.example.musicore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Song(val id: String, val uri: String, val title: String)
data class PlaylistInfo(val id: Long, val name: String, val folders: List<String>, val count: Int)

/**
 * Lo mínimo para pintar la pantalla de una lista: cuántas filas hay, por cuál va y una revisión
 * que solo cambia cuando cambia el contenido. Las canciones se piden fila a fila (solo las visibles).
 */
data class QueueInfo(val active: Boolean, val len: Int, val pos: Int, val rev: Long, val error: String? = null)

/**
 * Envuelve ExoPlayer para que "siguiente/anterior" (auriculares, notificación,
 * pantalla de bloqueo) pasen por la cola que gestiona Rust.
 */
class QueuePlayer(p: Player) : ForwardingPlayer(p) {
    private val extra = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )
    override fun getAvailableCommands(): Player.Commands {
        val b = super.getAvailableCommands().buildUpon()
        extra.forEach { b.add(it) }
        // El orden y las repeticiones los gestiona Rust: se rechazan las órdenes externas
        // de "repetir"/"aleatorio" (coche, auriculares…), que hacían repetir la misma canción.
        b.remove(Player.COMMAND_SET_REPEAT_MODE)
        b.remove(Player.COMMAND_SET_SHUFFLE_MODE)
        return b.build()
    }
    override fun setRepeatMode(repeatMode: Int) {}
    override fun getRepeatMode() = Player.REPEAT_MODE_OFF
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {}
    override fun getShuffleModeEnabled() = false
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun play() {
        when (playbackState) {
            Player.STATE_ENDED -> Engine.next()          // terminada: un "play" externo avanza, no repite
            Player.STATE_IDLE -> Engine.reloadCurrent()  // parado tras un fallo: vuelve a cargar la canción
            else -> super.play()
        }
    }
    override fun hasNextMediaItem() = true
    override fun hasPreviousMediaItem() = true
    override fun seekToNext() = Engine.next()
    override fun seekToNextMediaItem() = Engine.next()
    override fun seekToPrevious() = Engine.prev()
    override fun seekToPreviousMediaItem() = Engine.prev()
}

object Engine {
    private lateinit var app: Context
    private lateinit var exo: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var player: Player; private set
    private var ready = false
    private var errors = 0   // fallos de reproducción seguidos

    val current = MutableStateFlow<Song?>(null)
    val playing = MutableStateFlow(false)
    val volume = MutableStateFlow(1f)
    val position = MutableStateFlow(0L)   // ms
    val duration = MutableStateFlow(0L)   // ms
    val version = MutableStateFlow(0)     // sube con cada cambio de canción/cola
    val playlists = MutableStateFlow<List<PlaylistInfo>>(emptyList())
    val activePlaylist = MutableStateFlow<Long?>(null)
    /** Último fallo de reproducción (se muestra en el reproductor para poder diagnosticarlo). */
    val playbackError = MutableStateFlow<String?>(null)

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        app = ctx.applicationContext
        RustCore.init(app.filesDir.absolutePath)

        exo = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)   // pausa al desconectar auriculares
            .setWakeMode(C.WAKE_MODE_LOCAL)      // sigue sonando con la pantalla apagada
            .build()
        volume.value = app.getSharedPreferences("prefs", 0).getFloat("vol", 1f)
        exo.volume = volume.value                // volumen propio, independiente del sistema
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.shuffleModeEnabled = false

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next() else tick()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                if (isPlaying) { errors = 0; playbackError.value = null } else savePosition()
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError.value = "${error.errorCodeName}: ${error.cause?.message ?: error.message ?: ""}".trim()
                errors++
                when {
                    errors == 1 -> reloadCurrent()  // primer fallo: reintenta la misma canción donde iba
                    errors <= 3 -> next()           // sigue fallando (archivo ilegible): salta a la siguiente
                    else -> exo.stop()              // demasiados seguidos: se para en vez de encadenar saltos
                }
            }
        })
        player = QueuePlayer(exo)

        // Actualiza la barra de progreso mientras suena
        scope.launch {
            var n = 0
            while (true) {
                if (exo.isPlaying) { tick(); if (++n % 6 == 0) savePosition() } // guarda cada ~3 s
                delay(500)
            }
        }

        refreshPlaylists()
        activePlaylist.value = (RustCore.call("active") as? Number)?.toLong()
        // Recupera la canción y el punto exacto por donde iba (en pausa)
        song(RustCore.call("current"))?.let { s ->
            val p = app.getSharedPreferences("prefs", 0)
            val ms = if (p.getString("pos_song", null) == s.id) p.getLong("pos_ms", 0L) else 0L
            play(s, autoplay = false, startMs = ms)
        }
    }

    private fun tick() {
        position.value = exo.currentPosition.coerceAtLeast(0)
        duration.value = exo.duration.let { if (it == C.TIME_UNSET) 0L else it }
    }

    // ---------- listas
    fun refreshPlaylists() {
        val arr = RustCore.call("playlists") as JSONArray
        playlists.value = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val f = o.getJSONArray("folders")
            PlaylistInfo(o.getLong("id"), o.getString("name"), (0 until f.length()).map(f::getString), o.getInt("count"))
        }
    }

    fun savePlaylist(id: Long?, name: String, folders: List<String>, songs: List<Song>) {
        val args = JSONObject()
            .put("id", id ?: JSONObject.NULL)
            .put("name", name)
            .put("folders", JSONArray(folders))
            .put("songs", JSONArray(songs.map {
                JSONObject().put("id", it.id).put("uri", it.uri).put("title", it.title)
            }))
        RustCore.call("save_playlist", args)
        refreshPlaylists()
        if (id != null && id == activePlaylist.value) {
            val c = song(RustCore.call("current"))
            if (c?.id != current.value?.id) play(c)   // la que sonaba ya no está en la lista
        }
        version.value++
    }

    fun deletePlaylist(id: Long) {
        RustCore.call("delete_playlist", JSONObject().put("id", id))
        if (activePlaylist.value == id) { activePlaylist.value = null; play(null) }
        refreshPlaylists()
    }

    /** Crea una cola aleatoria nueva con toda la lista y empieza por la primera. */
    fun startPlaylist(id: Long) {
        ensureService()
        activePlaylist.value = id
        play(song(RustCore.call("start", JSONObject().put("id", id))))
    }

    /** Reproduce una canción concreta de la lista y continúa la cola desde ahí. */
    fun playSong(playlistId: Long, songId: String) {
        ensureService()
        activePlaylist.value = playlistId
        play(song(RustCore.call("play_song", JSONObject().put("id", playlistId).put("song_id", songId))))
    }

    fun queueInfo(id: Long): QueueInfo = runCatching {
        val o = RustCore.call("queue_info", JSONObject().put("id", id)) as JSONObject
        QueueInfo(o.getBoolean("active"), o.getInt("len"), o.getInt("pos"), o.getLong("rev"))
    }.getOrElse { QueueInfo(false, 0, 0, 0, it.message ?: it.toString()) }

    /** La canción de la fila `index` (orden de cola si la lista está activa, alfabético si no). */
    fun itemAt(id: Long, index: Int): Song? =
        runCatching { song(RustCore.call("item_at", JSONObject().put("id", id).put("index", index))) }.getOrNull()

    /** Solo los números de fila que coinciden con la búsqueda (la búsqueda la hace Rust). */
    fun search(id: Long, q: String): List<Int> = runCatching {
        val a = RustCore.call("search", JSONObject().put("id", id).put("q", q)) as JSONArray
        List(a.length()) { a.getInt(it) }
    }.getOrDefault(emptyList())

    // ---------- control
    fun next() { runCatching { play(song(RustCore.call("next"))) } }
    fun prev() { runCatching { play(song(RustCore.call("prev"))) } }
    fun toggle() {
        ensureService()
        when {
            exo.isPlaying -> exo.pause()
            exo.playbackState == Player.STATE_ENDED -> next()
            exo.playbackState == Player.STATE_IDLE -> { errors = 0; reloadCurrent() } // tras un fallo: recarga la canción
            else -> exo.play()
        }
    }

    /** Vuelve a cargar la canción actual por donde iba (el reproductor se quedó parado tras un fallo). */
    fun reloadCurrent() {
        current.value?.let { play(it, autoplay = true, startMs = position.value) }
    }

    /** Si Android paró el servicio (notificación y controles externos), lo vuelve a arrancar. */
    private fun ensureService() {
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }
    fun seekTo(ms: Long) { exo.seekTo(ms); tick(); current.value?.let { persist(it.id, ms) } }

    fun setVolume(v: Float) {
        volume.value = v
        exo.volume = v
        app.getSharedPreferences("prefs", 0).edit().putFloat("vol", v).apply()
    }

    /** Borra el archivo del disco de forma permanente y pasa a la siguiente. */
    fun deleteCurrent(): Boolean {
        val s = current.value ?: return false
        exo.stop(); exo.clearMediaItems() // suelta el archivo
        val ok = runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(s.uri)) }
            .getOrDefault(false)
        if (!ok) { play(s, autoplay = false); return false }
        play(song(RustCore.call("remove_current")))
        refreshPlaylists()
        return true
    }

    /** Borra del disco cualquier canción (no solo la que suena) y la quita de las listas. */
    fun deleteSong(s: Song): Boolean {
        if (s.id == current.value?.id) return deleteCurrent()
        val ok = runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(s.uri)) }
            .getOrDefault(false)
        if (!ok) return false
        RustCore.call("remove_song", JSONObject().put("song_id", s.id))
        refreshPlaylists()
        version.value++
        return true
    }

    // ---------- posición guardada
    private fun persist(songId: String, ms: Long) {
        app.getSharedPreferences("prefs", 0).edit().putString("pos_song", songId).putLong("pos_ms", ms).apply()
    }

    /** Guarda por dónde va la canción actual (se recupera al reabrir, aunque Android mate la app). */
    fun savePosition() {
        val s = current.value ?: return
        if (exo.playbackState == Player.STATE_READY) persist(s.id, exo.currentPosition.coerceAtLeast(0))
    }

    private fun play(s: Song?, autoplay: Boolean = true, startMs: Long = 0L) {
        current.value = s
        position.value = startMs; duration.value = 0
        version.value++
        if (s == null) { exo.stop(); exo.clearMediaItems(); return }
        persist(s.id, startMs)
        exo.setMediaItem(
            MediaItem.Builder().setUri(s.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).build()).build(),
            startMs,
        )
        exo.prepare()
        exo.playWhenReady = autoplay
    }

    private fun song(o: Any?): Song? =
        (o as? JSONObject)?.let { Song(it.getString("id"), it.getString("uri"), it.getString("title")) }

}
