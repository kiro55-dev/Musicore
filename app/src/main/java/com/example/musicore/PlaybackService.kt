package com.example.musicore

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Mantiene la reproducción en segundo plano y muestra la notificación con controles. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        val s = MediaSession.Builder(this, Engine.player).build()
        session = s
        // Sin esto la notificación solo aparece cuando se conecta un controlador externo.
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // Si está sonando, la app sigue viva (foreground + notificación) aunque cierres la ventana.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.savePosition()
        if (!Engine.player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }
}
