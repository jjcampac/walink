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
import com.jjcampac.walink.speech.SpeechReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Servicio que escucha las notificaciones de WhatsApp (y del simulador en debug),
 * las parsea y las lee en voz alta mediante [SpeechReader].
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var speechReader: SpeechReader
    private lateinit var dataStore: WalinkDataStore

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate() {
        super.onCreate()
        speechReader = SpeechReader(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
        Log.d(TAG, "Servicio de notificaciones creado")
    }

    override fun onDestroy() {
        job.cancel()
        speechReader.apagar()
        Log.d(TAG, "Servicio de notificaciones destruido")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        scope.launch {
            try {
                if (!dataStore.estaActivado) return@launch

                val mensaje =
                    NotificationParser.parsear(
                        sbn,
                        this@WhatsAppNotificationListener,
                        aceptarPaquetePropio = BuildConfig.DEBUG
                    ) ?: return@launch

                val textoParaLeer = if (dataStore.leerTextoCompleto) {
                    this@WhatsAppNotificationListener.getString(
                        R.string.tts_formato,
                        mensaje.remitente,
                        mensaje.texto
                    )
                } else {
                    this@WhatsAppNotificationListener.getString(
                        R.string.tts_formato,
                        mensaje.remitente,
                        "[texto oculto]"
                    )
                }

                Log.d(TAG, "Mensaje de '${mensaje.remitente}' (grupo=${mensaje.esGrupo}): ${mensaje.texto}")

                if (dataStore.leerTrasPreguntar) {
                    // Modo "preguntar": el asistente de voz hace la pregunta por TTS
                    // y escucha la respuesta (sí/no) antes de leer, pensado para
                    // conducir sin tocar el teléfono.
                    val intentAsistente = Intent(
                        this@WhatsAppNotificationListener,
                        com.jjcampac.walink.speech.LectorAsistenteService::class.java
                    )
                    intentAsistente.action =
                        com.jjcampac.walink.speech.LectorAsistenteService.ACTION_PREGUNTAR
                    intentAsistente.putExtra(
                        com.jjcampac.walink.speech.LectorAsistenteService.EXTRA_REMITENTE,
                        mensaje.remitente
                    )
                    intentAsistente.putExtra(
                        com.jjcampac.walink.speech.LectorAsistenteService.EXTRA_TEXTO,
                        textoParaLeer
                    )
                    ContextCompat.startForegroundService(
                        this@WhatsAppNotificationListener,
                        intentAsistente
                    )
                } else {
                    // Modo por defecto: leer nada más recibirlo.
                    val hablado = speechReader.hablar(textoParaLeer)
                    if (!hablado) {
                        Log.w(TAG, "No se pudo leer: TTS no inicializado")
                    }
                }
            } catch (e: Exception) {
                // Nunca debe romper el listener; se registra para depurar.
                Log.e(TAG, "Error procesando notificación", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        private const val TAG = "WalinkListener"
    }
}
