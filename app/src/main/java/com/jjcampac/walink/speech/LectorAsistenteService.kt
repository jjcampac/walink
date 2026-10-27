package com.jjcampac.walink.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jjcampac.walink.R

/**
 * Servicio en primer plano que, cuando Walink está configurado para "preguntar
 * antes de leer", hace la pregunta de forma oral (TTS) y escucha la respuesta del
 * usuario con reconocimiento de voz (SpeechRecognizer).
 *
 * Necesita ejecutarse en primer plano para poder usar el micrófono mientras la
 * app está en segundo plano (p. ej. conduciendo).
 *
 * Respuestas típicas reconocidas: "sí / si / lee / adelante / okay" (leer) y
 * "no / negativo" (ignorar). Si no se entiende, repite la pregunta hasta un
 * máximo de intentos y luego se detiene sin leer.
 */
class LectorAsistenteService : Service(), RecognitionListener {

    private lateinit var lector: SpeechReader
    private var recognizer: SpeechRecognizer? = null

    private var remitente = ""
    private var textoParaLeer = ""
    private var intentos = 0

    private var audioFocusConcedido = false
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lector = SpeechReader(this)
        crearCanal()
        Log.d(TAG, "LectorAsistenteService creado")
    }

    /** Solicita audio focus para el servicio (navegación/guía). */
    private fun solicitarAudioFocusParaServicio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusListener = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(change: Int) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus ganado en servicio")
                        audioFocusConcedido = true
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.w(TAG, "Pérdida permanente de audio focus en servicio")
                        audioFocusConcedido = false
                        finalizar()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Focus temporal perdido en servicio")
                        audioFocusConcedido = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Focus atenuado en servicio")
                        audioFocusConcedido = false
                    }
                }
            }
        }
        val resultado = audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        Log.d(TAG, "Solicitud audio focus resultado: $resultado, concedido: ${if (resultado == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "SÍ" else "NO"}")
        if (resultado == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusConcedido = true
        }
    }

    /** Libera el audio focus cuando el servicio termina. */
    private fun liberarAudioFocusServicio() {
        audioFocusListener?.also { listener ->
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(listener)
            Log.d(TAG, "Audio focus liberado en servicio")
        }
        audioFocusConcedido = false
        audioFocusListener = null
    }

    private var estaEjecutando = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (estaEjecutando) {
            Log.w(TAG, "Servicio ya en ejecución, deteniendo instancia anterior")
            stopSelf()
            return START_NOT_STICKY
        }
        estaEjecutando = true
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand recibida acción=${intent?.action} flags=$flags startId=$startId")
        val modo = intent?.getStringExtra(EXTRA_MODO_LECTURA) ?: "preguntar"

        if (intent?.action == ACTION_PREGUNTAR) {
            remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
            textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
            Log.d(TAG, "ACTION_PREGUNTAR: remitente='$remitente', texto='$textoParaLeer'")
            intentos = 0
            startForeground(NOTIF_ID, notificacionActiva())
            Log.d(TAG, "Servicio en foreground, llamando a hacerPregunta")
            hacerPregunta()
        } else if (intent?.action == ACTION_LEER_SIMULADO) {
            remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
            textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
            Log.d(TAG, "ACTION_LEER_SIMULADO: remitente='$remitente', texto='$textoParaLeer', modo='$modo'")
            intentos = 0
            startForeground(NOTIF_ID, notificacionActiva())
            if (modo == "inmediatamente") {
                Log.d(TAG, "Modo: leer inmediatamente, esperando TTS listo")
                esperarInicializado(10000) { listo ->
                    if (listo) {
                        Log.d(TAG, "TTS listo, leyendo mensaje")
                        lector.hablar(textoParaLeer) {
                            finalizar()
                        }
                    } else {
                        Log.e(TAG, "TTS no inicializó tras 10s, abortando")
                        finalizar()
                    }
                }
            } else {
                Log.d(TAG, "Modo: preguntar, llamando a hacerPregunta")
                hacerPregunta()
            }
        } else {
            Log.w(TAG, "Acción desconocida: ${intent?.action}, servicio detenido")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Reproduce la pregunta por voz y, al terminar, activa el micrófono. */
    private fun hacerPregunta() {
        val pregunta = if (intentos == 0) {
            getString(R.string.pregunta_voz, remitente)
        } else {
            getString(R.string.pregunta_voz_reintento)
        }

        Log.d(TAG, "hacerPregunta: '$pregunta' para remitente='$remitente', intentos=$intentos")

        // La única diferencia: esperar a que TTS esté listo (TTS engine no está
        // completamente listo al instante tras onCreate), con timeout de 10s.
        esperarInicializado(10000) { listo ->
            if (listo) {
                solicitarAudioFocusParaServicio()
                val ok = lector.hablar(pregunta) {
                    Log.d(TAG, "TTS terminó de hablar, ahora escuchando respuesta")
                    // Pequeño retraso para que el audio focus se libere completamente
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({ escucharRespuesta() }, 300)
                }
                if (!ok) {
                    Log.w(TAG, "No se pudo hacer la pregunta (TTS no listo); se detiene")
                    finalizar()
                }
            } else {
                Log.e(TAG, "TTS no inicializó tras 10s; abortando")
                finalizar()
            }
        }
    }

    private fun esperarInicializado(timeoutMs: Long, callback: (Boolean) -> Unit) {
        if (lector.inicializado) {
            callback(true)
            return
        }

        var intentos = 0
        val maxIntentos = (timeoutMs / 200).coerceAtLeast(1)

        val check = object : Runnable {
            override fun run() {
                if (lector.inicializado) {
                    callback(true)
                } else if (intentos < maxIntentos) {
                    intentos++
                    lector.mainHandler.postDelayed(this, 200)
                } else {
                    callback(false)
                }
            }
        }

        lector.mainHandler.post(check)
    }

    /** Escucha la respuesta del usuario con reconocimiento de voz. */
    private fun escucharRespuesta() {
        Log.d(TAG, "escucharRespuesta llamado, audioFocusConcedido=$audioFocusConcedido")
        if (!audioFocusConcedido) {
            Log.w(TAG, "No se tiene audio focus, intentando recuperarlo")
            solicitarAudioFocusParaServicio()
        }
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor", e)
            finalizar()
            return
        }
        recognizer = sr.apply {
            setRecognitionListener(this@LectorAsistenteService)
            Log.d(TAG, "SpeechRecognizer configurado con listener")
        }

        val intentReconocimiento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.pregunta_voz_prompt))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra("android.speech.extra.MAX_RESULTS", 1)
        }

        Log.d(TAG, "Intent de reconocimiento creado, arrancando reconocedor")
        // El reconocedor debe arrancar en el hilo principal
        recognizer!!.startListening(intentReconocimiento)
        Log.d(TAG, "SpeechRecognizer arrancando...")
    }

    // ---- Decorrido del reconocimiento de voz ----

    override fun onResults(results: Bundle?) {
        val frases = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.takeIf { it.isNotEmpty() }
        if (frases == null) {
            reintentarONoEntendido()
            return
        }
        val respuesta = frases.joinToString(" ").lowercase()
        Log.d(TAG, "Respuesta reconocida: \"$respuesta\"")
        Log.d(TAG, "REGEX_SI.containsMatchIn(\"$respuesta\") = ${REGEX_SI.containsMatchIn(respuesta)}")
        Log.d(TAG, "ln1 si check: ${"lee" in respuesta}, ln2 si check: ${"adelante" in respuesta}")
        Log.d(TAG, "REGEX_NO.containsMatchIn(\"$respuesta\") = ${REGEX_NO.containsMatchIn(respuesta)}")
        Log.d(TAG, "no check: ${"negativo" in respuesta}")

        when (decidir(respuesta)) {
            true -> {
                // Sí: se lee el mensaje.
                lector.hablar(textoParaLeer) {
                    finalizar()
                }
            }
            false -> {
                // No: no se lee.
                lector.hablar(getString(R.string.pregunta_voz_no)) {
                    finalizar()
                }
            }
            null -> reintentarONoEntendido()
        }
    }

    override fun onError(error: Int) {
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
        recognizer?.destroy()
        recognizer = null

        if (intentos < MAX_INTENTOS) {
            intentos++
            lector.hablar(getString(R.string.pregunta_voz_no_entendido)) {
                escucharRespuesta()
            }
        } else {
            lector.hablar(getString(R.string.aviso_no_leido))
            finalizar()
        }
    }

    /**
     * Interpreta la respuesta: true = leer, false = no leer, null = no entendido.
     */
    private fun decidir(respuesta: String): Boolean? {
        val si = when {
            REGEX_SI.containsMatchIn(respuesta) -> true
            "lee" in respuesta -> true
            "adelante" in respuesta -> true
            else -> false
        }
        val no = when {
            REGEX_NO.containsMatchIn(respuesta) -> true
            "negativo" in respuesta -> true
            else -> false
        }
        return when {
            si && !no -> true
            no && !si -> false
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL,
                getString(R.string.canal_asistente_titulo),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.canal_asistente_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun finalizar() {
        estaEjecutando = false
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        lector.apagar()
        Log.d(TAG, "LectorAsistenteService destruido")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalinkLectorVoz"

        const val ACTION_PREGUNTAR = "com.jjcampac.walink.action.PREGUNTAR_LECTURA"
        const val ACTION_LEER_SIMULADO = "com.jjcampac.walink.action.LEER_MENSAJE_SIMULADO"
        const val EXTRA_MODO_LECTURA = "modo_lectura"
        const val EXTRA_REMITENTE = "remitente"
        const val EXTRA_TEXTO = "texto"

        private const val CANAL = "walink_asistente_voz"
        private const val NOTIF_ID = 40
        private const val MAX_INTENTOS = 2

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
