package com.jjcampac.walink.speech

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jjcampac.walink.R

/**
 * Servicio en primer plano (tipo micrófono) SOLO para el modo "preguntar antes de
 * leer". Hace la pregunta en voz alta, escucha la respuesta (sí/no) y, si procede,
 * lee el mensaje.
 *
 * Necesita ejecutarse en primer plano para poder usar el micrófono mientras la
 * app está en segundo plano (p. ej. conduciendo).
 *
 * El modo "leer inmediatamente" no pasa por este servicio: lo gestiona
 * [com.jjcampac.walink.service.WhatsAppNotificationListener] leyendo directamente,
 * ya que no requiere micrófono y así evitamos servicios en primer plano innecesarios.
 */
class LectorAsistenteService : Service(), RecognitionListener {

    private lateinit var lector: SpeechReader
    private var recognizer: SpeechRecognizer? = null
    private var tiempoEscucha: Runnable? = null

    private var remitente = ""
    private var textoParaLeer = ""
    private var intentos = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lector = SpeechReader.obtener(this)
        crearCanal()
        Log.d(TAG, "LectorAsistenteService creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PREGUNTAR -> manejarPreguntar(intent)
            else -> Log.w(TAG, "Acción desconocida: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /** Modo "preguntar antes de leer": pregunta por voz y escucha sí/no. */
    private fun manejarPreguntar(intent: Intent) {
        // Si quedó un reconocedor de un ciclo anterior, lo limpiamos para empezar
        // con el estado correcto (evita que un "Simular mensaje" posterior no se oiga).
        recognizer?.destroy()
        recognizer = null

        remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
        textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
        intentos = 0
        Log.d(TAG, "ACTION_PREGUNTAR: remitente='$remitente' texto='${textoParaLeer.take(40)}'")

        // Sin el permiso de micrófono no se puede usar SpeechRecognizer; no arrancamos.
        if (!tieneMicrofono()) {
            Log.e(TAG, "RECORD_AUDIO no concedido: no se puede preguntar")
            stopSelf()
            return
        }

        entrarEnForeground()
        hacerPregunta()
    }

    private fun tieneMicrofono(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun entrarEnForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notificacionActiva())
        }
    }

    /** Reproduce la pregunta por voz y, al terminar, activa el micrófono. */
    private fun hacerPregunta() {
        val pregunta = if (intentos == 0) {
            getString(R.string.pregunta_voz, remitente)
        } else {
            getString(R.string.pregunta_voz_reintento)
        }
        Log.d(TAG, "hacerPregunta: '$pregunta' intentos=$intentos")
        lector.hablar(pregunta) {
            Log.d(TAG, "Pregunta terminada, activando micrófono")
            // Pequeña pausa para que el audio focus se libere antes de escuchar.
            lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
        }
    }

    /** Escucha la respuesta del usuario con reconocimiento de voz. */
    private fun escucharRespuesta() {
        Log.d(TAG, "escucharRespuesta: activando micrófono")
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor", e)
            finalizar()
            return
        }
        recognizer = sr.apply { setRecognitionListener(this@LectorAsistenteService) }

        val intentReconocimiento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.pregunta_voz_prompt))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra("android.speech.extra.MAX_RESULTS", 1)
        }
        recognizer?.startListening(intentReconocimiento)
        Log.d(TAG, "SpeechRecognizer escuchando...")

        // Si no se oye respuesta en un tiempo, no quedarse colgado "escuchando".
        cancelarTiempoEscucha()
        val tarea = Runnable {
            Log.w(TAG, "Tiempo de escucha agotado sin respuesta")
            reintentarONoEntendido()
        }
        tiempoEscucha = tarea
        lector.mainHandler.postDelayed(tarea, TIEMPO_ESCUCHA_MS)
    }

    // ---- Resultados del reconocimiento de voz ----

    override fun onResults(results: Bundle?) {
        // El reconocedor ya terminó: lo destruimos AHORA para que ningún callback
        // tardío (onError/onResults repetido) corte la lectura posterior del mensaje.
        recognizer?.destroy()
        recognizer = null
        cancelarTiempoEscucha()

        val frases = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.takeIf { it.isNotEmpty() }
        if (frases == null) {
            reintentarONoEntendido()
            return
        }
        val respuesta = frases.joinToString(" ").lowercase()
        Log.d(TAG, "Respuesta reconocida: \"$respuesta\"")
        when (decidir(respuesta)) {
            true -> lector.hablar(textoParaLeer) {
                Log.d(TAG, "Mensaje leído tras confirmar")
                finalizar()
            }
            false -> lector.hablar(getString(R.string.pregunta_voz_no)) {
                finalizar()
            }
            null -> reintentarONoEntendido()
        }
    }

    override fun onError(error: Int) {
        cancelarTiempoEscucha()
        Log.w(TAG, "Error de reconocimiento ($error)")
        reintentarONoEntendido()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun reintentarONoEntendido() {
        cancelarTiempoEscucha()
        recognizer?.destroy()
        recognizer = null
        if (intentos < MAX_INTENTOS) {
            intentos++
            lector.hablar(getString(R.string.pregunta_voz_no_entendido)) {
                lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
            }
        } else {
            lector.hablar(getString(R.string.aviso_no_leido)) {
                finalizar()
            }
        }
    }

    /** true = leer, false = no leer, null = no entendido. */
    private fun decidir(respuesta: String): Boolean? {
        val quiereSi = REGEX_SI.containsMatchIn(respuesta) ||
            "lee" in respuesta || "adelante" in respuesta
        val quiereNo = REGEX_NO.containsMatchIn(respuesta) || "negativo" in respuesta
        return when {
            quiereSi && !quiereNo -> true
            quiereNo && !quiereSi -> false
            else -> null
        }
    }

    private fun notificacionActiva() = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_tile_walink)
        .setContentTitle(getString(R.string.notif_escuchando_titulo))
        .setContentText(getString(R.string.notif_escuchando_desc))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL,
                getString(R.string.canal_asistente_titulo),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.canal_asistente_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun cancelarTiempoEscucha() {
        tiempoEscucha?.let { lector.mainHandler.removeCallbacks(it) }
        tiempoEscucha = null
    }

    private fun finalizar() {
        cancelarTiempoEscucha()
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelarTiempoEscucha()
        recognizer?.destroy()
        recognizer = null
        Log.d(TAG, "LectorAsistenteService destruido")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalinkLectorVoz"

        const val ACTION_PREGUNTAR = "com.jjcampac.walink.action.PREGUNTAR_LECTURA"
        const val EXTRA_REMITENTE = "remitente"
        const val EXTRA_TEXTO = "texto"

        private const val CANAL = "walink_asistente_voz"
        private const val NOTIF_ID = 40
        private const val MAX_INTENTOS = 2
        // Si en este tiempo no se oye ninguna respuesta, se trata como "no entendido".
        private const val TIEMPO_ESCUCHA_MS = 12000L

        private val REGEX_SI = Regex(
            "\\b(s[ií])\\b|\\byes\\b|\\bok\\b|\\bokay\\b",
            RegexOption.IGNORE_CASE
        )
        private val REGEX_NO = Regex(
            "\\bno\\b|\\bnegative\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
