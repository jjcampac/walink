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
import android.app.PendingIntent
import android.os.IBinder
import android.speech.RecognitionListener
import androidx.core.content.ContextCompat
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore

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
    private lateinit var dataStore: WalinkDataStore
    private var recognizer: SpeechRecognizer? = null
    private var tiempoEscucha: Runnable? = null

    private var remitente = ""
    private var textoParaLeer = ""
    private var intentos = 0
    private var ciclo = 0
    private var preguntaHablando = false
    private var tareaWatchdogPregunta: Runnable? = null

    // ---- Flujo de responder tras leer ----
    // 0 = idle, 1 = ¿leer el mensaje? (ACTION_PREGUNTAR), 2 = ¿responder? (ACTION_RESPONDER), 3 = dictado libre, 4 = ¿enviar o corregir?
    private var etapaRespuesta = 0
    private var pendingIntentResponder: PendingIntent? = null
    private var clavesRemoteInput: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lector = SpeechReader.obtener(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
        crearCanal()
        Log.d(TAG, "LectorAsistenteService creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PREGUNTAR -> manejarPreguntar(intent)
            ACTION_RESPONDER -> manejarResponder(intent)
            else -> Log.w(TAG, "Acción desconocida: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /** Modo "preguntar antes de leer": pregunta por voz y escucha sí/no. */
    private fun manejarPreguntar(intent: Intent) {
        ciclo++
        Log.d(TAG, "ACTION_PREGUNTAR (ciclo=$ciclo) recibido")

        // Si quedó pendiente algo de un ciclo anterior (locución sin terminar, un
        // onDone atrasado, un emit retrasado por Bluetooth), lo descartamos antes
        // de empezar. Sin este reset, el siguiente "Simular mensaje" podía quedarse
        // mudo o mezclar su pregunta con la lectura del mensaje anterior.
        lector.descartarLocucionPendiente()
        cancelarTiempoEscucha()
        cancelarWatchdogPregunta()
        recognizer?.destroy()
        recognizer = null

        remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
        textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
        recibirDatosRespuesta(intent)
        intentos = 0
        etapaRespuesta = ETAPA_SI_NO_LEER
        Log.d(TAG, "ACTION_PREGUNTAR (ciclo=$ciclo): remitente='$remitente' texto='${textoParaLeer.take(40)}'")

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
        // Desde Android 14 (API 34), arrancar un FGS de tipo "microphone" desde
        // segundo plano (p. ej. al llegar una notificación de WhatsApp con la app
        // cerrada) se rechaza con SecurityException porque exige que la app esté
        // en un estado elegible (primer plano). Por eso en API 34+ usamos
        // "specialUse", que no tiene esa restricción while-in-use y permite el
        // asistente de voz con micrófono aunque llegue un mensaje en segundo plano.
        // El manifest declara "microphone|specialUse" para que ambas APIs pasen
        // la validación del tipo declarado.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        Log.d(TAG, "hacerPregunta (ciclo=$ciclo): intentos=$intentos")
        preguntaHablando = true
        lector.hablar(pregunta) {
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Pregunta terminada (ciclo=$ciclo), activando micrófono")
            // Pequeña pausa para que el audio focus se libere antes de escuchar.
            lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
        }
        // Watchdog: si la pregunta no termina de hablarse (onDone perdido o motor
        // atascado), no dejamos el ciclo congelado: activamos el micrófono igualmente.
        armarWatchdogPregunta()
    }

    private fun armarWatchdogPregunta() {
        cancelarWatchdogPregunta()
        val tarea = Runnable {
            if (preguntaHablando) {
                Log.w(TAG, "La pregunta no terminó en ${WATCHDOG_PREGUNTA_MS / 1000}s (ciclo=$ciclo); activando micrófono igualmente")
                preguntaHablando = false
                escucharRespuesta()
            }
        }
        tareaWatchdogPregunta = tarea
        lector.mainHandler.postDelayed(tarea, WATCHDOG_PREGUNTA_MS)
    }

    private fun cancelarWatchdogPregunta() {
        tareaWatchdogPregunta?.let { lector.mainHandler.removeCallbacks(it) }
        tareaWatchdogPregunta = null
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
        Log.d(TAG, "Respuesta reconocida: \"$respuesta\" (etapa=$etapaRespuesta)")

        when (etapaRespuesta) {
            ETAPA_IDLE -> {
                Log.w(TAG, "onResults en etapa IDLE: se ignora")
            }
            ETAPA_SI_NO_LEER -> {
                when (decidir(respuesta)) {
                    true -> {
                        Log.d(TAG, "onResults: usuario quiere LEER")
                        if (!lector.inicializado) {
                            Log.w(TAG, "onResults: TTS sin inicializar; esperando 800 ms")
                            lector.mainHandler.postDelayed({
                                lector.hablar(textoParaLeer) {
                                    Log.d(TAG, "Mensaje leído tras confirmar; ofreciendo responder")
                                    lanzarOfrecerResponder()
                                }
                            }, 800)
                        } else if (lector.apagado) {
                            Log.e(TAG, "onResults: lector.apagado, no se puede leer")
                        } else {
                            Log.d(TAG, "onResults: pidiendo lectura del mensaje")
                            lector.hablar(textoParaLeer) {
                                Log.d(TAG, "Mensaje leído tras confirmar; ofreciendo responder")
                                lanzarOfrecerResponder()
                            }
                        }
                    }
                    false -> {
                        Log.d(TAG, "onResults: respuesta NO -> no se lee")
                        lector.hablar(getString(R.string.pregunta_voz_no)) {
                            finalizar()
                        }
                    }
                    null -> {
                        Log.d(TAG, "onResults: respuesta ambigua -> reintento")
                        reintentarONoEntendido()
                    }
                }
            }
            ETAPA_SI_NO_RESPONDER -> {
                when (decidir(respuesta)) {
                    true -> {
                        Log.d(TAG, "onResults: usuario quiere RESPONDER")
                        pedirDictado()
                    }
                    false -> {
                        Log.d(TAG, "onResults: usuario NO quiere responder")
                        lector.hablar(getString(R.string.no_responder)) {
                            finalizar()
                        }
                    }
                    null -> {
                        Log.d(TAG, "onResults: respuesta ambigua -> reintento")
                        reintentarONoEntendido()
                    }
                }
            }
            ETAPA_DICTADO -> {
                val textoDictado = frases.joinToString(" ")
                Log.d(TAG, "onResults: dictado recibido: '$textoDictado'")
                if (textoDictado.isBlank()) {
                    reintentarONoEntendido()
                    return
                }
                etapaRespuesta = ETAPA_CONFIRMAR_ENVIO
                dataStore.guardarBorradorRespuesta(remitente, textoDictado)
                lector.hablar(getString(R.string.borrador_guardado, remitente, textoDictado)) {
                    Log.d(TAG, "Borrador dictado guardado, preguntando enviar o corregir")
                    preguntarEnviarOCorregir()
                }
            }
            ETAPA_CONFIRMAR_ENVIO -> {
                val decidir = decidirEnviarOCorregir(respuesta)
                when (decidir) {
                    true -> {
                        Log.d(TAG, "onResults: usuario quiere ENVIAR")
                        enviarRespuesta()
                    }
                    false -> {
                        Log.d(TAG, "onResults: usuario quiere CORREGIR")
                        corregirRespuesta()
                    }
                    null -> {
                        Log.d(TAG, "onResults: respuesta ambigua -> reintento")
                        reintentarONoEntendidoEnviarOCorregir()
                    }
                }
            }
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
        cancelarWatchdogPregunta()
        preguntaHablando = false
        etapaRespuesta = ETAPA_IDLE
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ==== Flujo de responder tras leer ====

    /** Acción RESPONDER: pregunta si quiere responder y, si sí, escucha el dictado. */
    private fun manejarResponder(intent: Intent) {
        ciclo++
        etapaRespuesta = ETAPA_IDLE
        Log.d(TAG, "ACTION_RESPONDER (ciclo=$ciclo) recibido")

        lector.descartarLocucionPendiente()
        cancelarTiempoEscucha()
        cancelarWatchdogPregunta()
        recognizer?.destroy()
        recognizer = null

        remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
        textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
        recibirDatosRespuesta(intent)
        intentos = 0
        Log.d(TAG, "ACTION_RESPONDER (ciclo=$ciclo): remitente='$remitente' pendingIntent=${pendingIntentResponder != null}")

        if (!tieneMicrofono()) {
            Log.e(TAG, "RECORD_AUDIO no concedido: no se puede responder")
            lector.hablar(getString(R.string.microfono_no_concedido_responder)) {
                finalizar()
            }
            return
        }

        entrarEnForeground()
        preguntarSiResponder()
    }

    /** Pregunta por voz si el usuario quiere responder al remitente. */
    private fun preguntarSiResponder() {
        etapaRespuesta = ETAPA_SI_NO_RESPONDER
        preguntaHablando = true
        lector.hablar(getString(R.string.pregunta_responder, remitente)) {
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Pregunta de responder terminada, activando micrófono")
            lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
        }
        armarWatchdogPregunta()
    }

    /** Pide al usuario que dicte su respuesta por voz. */
    private fun pedirDictado() {
        etapaRespuesta = ETAPA_DICTADO
        preguntaHablando = true
        lector.hablar(getString(R.string.pedir_dictado)) {
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Prompt de dictado terminado, activando micrófono")
            lector.mainHandler.postDelayed({ escucharTextoLibre() }, 350)
        }
        armarWatchdogPregunta()
    }

    /** Escucha un dictado de texto libre (respuesta a redactar). */
    private fun escucharTextoLibre() {
        Log.d(TAG, "escucharTextoLibre: activando micrófono para dictado")
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor para dictado", e)
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
        Log.d(TAG, "SpeechRecognizer escuchando dictado...")

        cancelarTiempoEscucha()
        val tarea = Runnable {
            Log.w(TAG, "Tiempo de escucha agotado sin dictado")
            reintentarONoEntendido()
        }
        tiempoEscucha = tarea
        lector.mainHandler.postDelayed(tarea, TIEMPO_ESCUCHA_MS)
    }

    /** Lanza ACTION_RESPONDER para ofrecer responder tras leer un mensaje. */
    private fun lanzarOfrecerResponder() {
        etapaRespuesta = ETAPA_IDLE
        val intent = Intent(this, LectorAsistenteService::class.java)
            .setAction(LectorAsistenteService.ACTION_RESPONDER)
            .putExtra(LectorAsistenteService.EXTRA_REMITENTE, remitente)
            .putExtra(LectorAsistenteService.EXTRA_TEXTO, textoParaLeer)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * Copia en la instancia el PendingIntent de respuesta y las claves del RemoteInput
     * que llegan en [intent]. Solo se sobrescriben si el extra trae algún valor: cuando
     * el ACTION_RESPONDER lo lanza el propio servicio (lanzarOfrecerResponder), el intent
     * no los incluye y se conservan los de la notificación original.
     */
    private fun recibirDatosRespuesta(intent: Intent) {
        intent.getParcelableExtra<android.app.PendingIntent>(EXTRA_PENDING_INTENT_RESPONDER)
            ?.let { pendingIntentResponder = it }
        intent.getStringArrayListExtra(EXTRA_CLAVES_REMOTE_INPUT)
            ?.takeIf { it.isNotEmpty() }
            ?.let { clavesRemoteInput = it }
    }

    /** Pregunta por voz si quiere enviar la respuesta ya redactada o corregirla. */
    private fun preguntarEnviarOCorregir() {
        etapaRespuesta = ETAPA_CONFIRMAR_ENVIO
        preguntaHablando = true
        lector.hablar(getString(R.string.pregunta_enviar_o_corregir)) {
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Confirmación de enviar/corregir terminada, activando micrófono")
            lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
        }
        armarWatchdogPregunta()
    }

    /** true = enviar, false = corregir, null = no entendido. */
    private fun decidirEnviarOCorregir(respuesta: String): Boolean? {
        val quiereEnviar = REGEX_SI.containsMatchIn(respuesta) ||
            "enviar" in respuesta || "mandar" in respuesta ||
            "manda" in respuesta || "adelante" in respuesta
        val quiereCorregir = REGEX_NO.containsMatchIn(respuesta) ||
            "corregir" in respuesta || "repetir" in respuesta ||
            "cambiar" in respuesta || "otra vez" in respuesta
        return when {
            quiereEnviar && !quiereCorregir -> true
            quiereCorregir && !quiereEnviar -> false
            else -> null
        }
    }

    /** Envía la respuesta guardada mediante el RemoteInput de la notificación original. */
    private fun enviarRespuesta() {
        val texto = dataStore.borradorTexto ?: ""
        val pi = pendingIntentResponder
        if (pi == null || texto.isBlank() || clavesRemoteInput.isEmpty()) {
            Log.e(TAG, "No se puede enviar: pendingIntent=${pi != null} texto='$texto' claves=$clavesRemoteInput")
            lector.hablar(getString(R.string.pendiente_intent_no_disponible)) {
                finalizar()
            }
            return
        }
        Log.d(TAG, "Enviando respuesta a '${remitente}': '$texto'")
        lector.hablar(getString(R.string.enviando_respuesta)) {
            try {
                val intentEnvio = Intent()
                val resultados = Bundle()
                clavesRemoteInput.forEach { resultados.putCharSequence(it, texto) }
                // addResultsToIntent copia los resultados usando las claves del RemoteInput
                // original, de modo que la app receptora (WhatsApp) los interprete bien.
                val remoteInputs = clavesRemoteInput.map { input ->
                    android.app.RemoteInput.Builder(input).build()
                }.toTypedArray()
                android.app.RemoteInput.addResultsToIntent(remoteInputs, intentEnvio, resultados)
                pi.send(this, 0, intentEnvio)
                dataStore.borrarBorradorRespuesta()
                Log.d(TAG, "Respuesta enviada por RemoteInput")
                lector.hablar(getString(R.string.respuesta_enviada, remitente)) {
                    finalizar()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando respuesta", e)
                lector.hablar(getString(R.string.aviso_no_enviado)) {
                    finalizar()
                }
            }
        }
    }

    /** Vuelve a escuchar el dictado para corregir la respuesta ya redactada. */
    private fun corregirRespuesta() {
        Log.d(TAG, "onResults: usuario quiere CORREGIR; borrando borrador anterior")
        dataStore.borrarBorradorRespuesta()
        etapaRespuesta = ETAPA_DICTADO
        preguntaHablando = true
        lector.hablar(getString(R.string.corrigiendo_respuesta)) {
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Aviso de corrección terminado, activando micrófono")
            lector.mainHandler.postDelayed({ escucharTextoLibre() }, 350)
        }
        armarWatchdogPregunta()
    }

    /** Reintento cuando no se entiende "enviar" o "corregir". */
    private fun reintentarONoEntendidoEnviarOCorregir() {
        cancelarTiempoEscucha()
        recognizer?.destroy()
        recognizer = null
        if (intentos < MAX_INTENTOS) {
            intentos++
            lector.hablar(getString(R.string.pregunta_enviar_o_corregir_reintento)) {
                lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
            }
        } else {
            // Sin respuesta clara se deja el borrador guardado (no se pierde) y se finaliza.
            lector.hablar(getString(R.string.aviso_no_respondido)) {
                finalizar()
            }
        }
    }

    override fun onDestroy() {
        cancelarTiempoEscucha()
        cancelarWatchdogPregunta()
        preguntaHablando = false
        recognizer ?.destroy()
        recognizer = null
        Log.d(TAG, "LectorAsistenteService destruido")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalinkLectorVoz"

        const val ACTION_PREGUNTAR = "com.jjcampac.walink.action.PREGUNTAR_LECTURA"
        const val ACTION_RESPONDER = "com.jjcampac.walink.action.RESPONDER"
        const val EXTRA_REMITENTE = "remitente"
        const val EXTRA_TEXTO = "texto"
        const val EXTRA_PENDING_INTENT_RESPONDER = "pending_intent_responder"
        const val EXTRA_CLAVES_REMOTE_INPUT = "claves_remote_input"

        // Etapas del flujo de voz (leer / responder / dictar / enviar-o-corregir)
        private const val ETAPA_IDLE = 0
        private const val ETAPA_SI_NO_LEER = 1
        private const val ETAPA_SI_NO_RESPONDER = 2
        private const val ETAPA_DICTADO = 3
        private const val ETAPA_CONFIRMAR_ENVIO = 4

        private const val CANAL = "walink_asistente_voz"
        private const val NOTIF_ID = 40
        private const val MAX_INTENTOS = 2
        // Si en este tiempo no se oye ninguna respuesta, se trata como "no entendido".
        private const val TIEMPO_ESCUCHA_MS = 12000L
        // Tiempo máximo para que la pregunta hablada termine; si no, se activa el
        // micrófono igualmente (evita ciclos congelados por un onDone TTS perdido).
        private const val WATCHDOG_PREGUNTA_MS = 6000L

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
