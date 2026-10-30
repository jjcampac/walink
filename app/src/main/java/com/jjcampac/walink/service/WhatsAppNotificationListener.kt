package com.jjcampac.walink.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.jjcampac.walink.BuildConfig
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.parser.NotificationParser
import com.jjcampac.walink.speech.LectorAsistenteService
import com.jjcampac.walink.speech.SpeechReader

/**
 * Servicio que escucha las notificaciones de WhatsApp (y del simulador en debug).
 *
 * Es el punto de entrada de la lectura de mensajes:
 *  - Modo "leer inmediatamente": lee el mensaje aquí mismo con su [SpeechReader],
 *    sin servicio en primer plano (no requiere micrófono ni recursos extra).
 *  - Modo "preguntar antes de leer": delega en [LectorAsistenteService], un
 *    servicio en primer plano (tipo micrófono) que pregunta, escucha y luego lee.
 *
 * Esta separación evita cuelgues por servicios en primer plano innecesarios y
 * garantiza que nunca haya dos lecturas a la vez.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var speechReader: SpeechReader
    private lateinit var dataStore: WalinkDataStore

    override fun onCreate() {
        super.onCreate()
        speechReader = SpeechReader.obtener(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
        Log.d(TAG, "Servicio de notificaciones creado")
    }

    override fun onDestroy() {
        Log.d(TAG, "Servicio de notificaciones destruido")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            if (!dataStore.estaActivado) return

            val mensaje = NotificationParser.parsear(
                sbn,
                this,
                aceptarPaquetePropio = BuildConfig.DEBUG
            ) ?: return

            val visibilidad = if (dataStore.leerTextoCompleto) mensaje.texto else "[texto oculto]"
            val textoParaLeer = getString(R.string.tts_formato, mensaje.remitente, visibilidad)

            // El modo se decide únicamente por la preferencia persistente
            // leerTrasPreguntar. No depende del parámetro recibido por el
            // simulador de debug: este solo publica una notificación idéntica
            // a la de WhatsApp, y el listener aplica la regla de negocio.
            val esInmediato = !dataStore.leerTrasPreguntar
            val modoTexto = if (esInmediato) "leer inmediato" else "preguntar"
            Log.d(TAG, "Modo lectura: leerTrasPreguntar=${dataStore.leerTrasPreguntar} -> $modoTexto")
            if (esInmediato) {
                // Modo "leer inmediatamente": leemos sin servicio en primer plano.
                // Si quedara un LectorAsistenteService de un ciclo "preguntar" anterior
                // (por un onDone TTS perdido), lo detenemos para que no contamine esta
                // lectura con una pregunta fantasma.
                stopService(Intent(this, LectorAsistenteService::class.java))
                Log.d(TAG, "Mensaje de '${mensaje.remitente}' (grupo=${mensaje.esGrupo}) -> leyendo ahora")
                speechReader.hablar(textoParaLeer)
            } else {
                // Modo "preguntar": delega en el servicio en primer plano.
                Log.d(TAG, "Mensaje de '${mensaje.remitente}' -> modo preguntar")
                val intent = Intent(this, LectorAsistenteService::class.java)
                    .setAction(LectorAsistenteService.ACTION_PREGUNTAR)
                    .putExtra(LectorAsistenteService.EXTRA_REMITENTE, mensaje.remitente)
                    .putExtra(LectorAsistenteService.EXTRA_TEXTO, textoParaLeer)
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (e: Exception) {
            // Nunca debe romper el listener.
            Log.e(TAG, "Error procesando notificación", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        private const val TAG = "WalinkListener"
    }
}
