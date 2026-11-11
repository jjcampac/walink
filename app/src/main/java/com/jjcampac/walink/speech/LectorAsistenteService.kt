package com.jjcampac.walink.speech

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.Service
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.app.PendingIntent
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import androidx.core.content.ContextCompat
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag
import com.jjcampac.walink.idioma.IdiomaHelper
import com.jjcampac.walink.service.MicrofonoPermanenteService
import androidx.core.app.NotificationCompat

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
 *
 * @author jjcampac
 * @author cline
 */
class LectorAsistenteService : Service(), RecognitionListener {

    private lateinit var lector: SpeechReader
    private lateinit var dataStore: WalinkDataStore
    private var recognizer: SpeechRecognizer? = null
    private var tiempoEscucha: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var remitente = ""
    private var textoParaLeer = ""
    private var intentos = 0
    private var ciclo = 0
    private var preguntaHablando = false
    private var tareaWatchdogPregunta: Runnable? = null
    // Pico de nivel de audio (onRmsChanged) del reconocedor, para diagnóstico.
    private var nivelPicoRms = 0f

    // ---- Flujo de responder tras leer ----
    // 0 = idle, 1 = ¿leer el mensaje? (ACTION_PREGUNTAR), 2 = ¿responder? (ACTION_RESPONDER), 3 = dictado libre, 4 = ¿enviar o corregir?
    private var etapaRespuesta = 0
    private var pendingIntentResponder: PendingIntent? = null
    private var clavesRemoteInput: List<String> = emptyList()
    // true cuando el FGS ya está en marcha como tipo "microphone" (con el permiso
    // while-in-use del micrófono activo). Evita volver a "specialUse" al reaccionar
    // (lo que repetiría el fallo de captar solo el primer buffer con pantalla apagada).
    private var fgMicActivo = false
    // Foco de audio retenido para mantener la música pausada durante TODA la sesión
    // de voz. Sin esto, al terminar el TTS su foco (GAIN_TRANSIENT) la música vuelve
    // justo al activar el micrófono y el micro captura ese sonido como primer buffer
    // (rompiendo el reconocimiento). Solo se usa en el modo "detener música".
    private var audioFocusRetenido: AudioFocusRequest? = null
    private val audioManagerPausa: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(IdiomaHelper.contextoLocalizado(newBase))
    }
    override fun onCreate() {
        super.onCreate()
        lector = SpeechReader.obtener(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
        crearCanal()
        LogDiag.iniciar(this)
        Log.d(TAG, "LectorAsistenteService creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        LogDiag.d("onStartCommand: acción=${intent?.action}")
        when (intent?.action) {
            ACTION_PREPARAR_VOZ -> manejarPrepararVoz()
            ACTION_PREGUNTAR -> manejarPreguntar(intent)
            ACTION_RESPONDER -> manejarResponder(intent)
            else -> Log.w(TAG, "Acción desconocida: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /**
     * Calienta el micrófono cuando llega una notificación (modo "leer inmediato"):
     * entra en primer plano y conmuta el FGS a tipo "microphone" en la ventana en que
     * la app aún es elegible para acceder al permiso foreground-only RECORD_AUDIO.
     * El servicio se mantiene en marcha (tipo micrófono) para que la escucha posterior
     * ("¿quieres responder?") reutilice este estado y no intente el arranque en frío
     * desde segundo plano, que Android rechaza con SecurityException (el micro solo
     * captaría el primer buffer con la pantalla apagada).
     */
    private fun manejarPrepararVoz() {
        Log.d(TAG, "ACTION_PREPARAR_VOZ recibido (calentando micrófono en la ventana de notificación)")
        entrarEnForeground()
        // Mantener la CPU despierta desde que llega la notificación real (pantalla
        // apagada) hasta que termine la sesión: si no, entre la lectura del mensaje
        // (hecha en el listener) y la llegada de ACTION_RESPONDER la CPU puede
        // suspenderse y el reconocedor no consigue oír con pantalla apagada.
        adquirirWakeLock()
        activarFgMicrofono()
        // Este servicio permanece en primer plano (tipo micrófono) a la espera de la
        // acción RESPONDER que llega al terminar la lectura del mensaje.
    }

    /** Modo "preguntar antes de leer": pregunta por voz y escucha sí/no. */
    private fun manejarPreguntar(intent: Intent) {
        ciclo++
        LogDiag.d("ACTION_PREGUNTAR (ciclo=$ciclo) recibido: remitente='$remitente'")
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
        adquirirWakeLock()
        // CRÍTICO: conmutar el FGS a tipo "microphone" YA aquí, en la ventana de la
        // notificación. Si esperamos a escucharRespuesta (se llama varios segundos
        // después, al terminar la pregunta hablada), esa ventana donde la app aún es
        // elegible para el permiso foreground-only ya se ha agotado con pantalla
        // apagada y startForeground(MICROPHONE) falla con SecurityException. El
        // reconocedor entonces arranca igualmente, pero sin el while-in-use del micro
        // no capta voz (picoRMS=0 y ERROR_NO_MATCH en cada intento).
        activarFgMicrofono()
        hacerPregunta()
    }

    private fun tieneMicrofono(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun entrarEnForeground() {
        // Pausar la música durante toda esta sesión de voz (solo modo "detener música"),
        // para que no vuelva a sonar justo al activar el micrófono y capte ese sonido.
        retenerPausaMusica()
        // Ya estamos en primer plano como "microphone": lo reafirmamos y NO volvemos
        // a "specialUse" (revertir rompería el permiso while-in-use del micrófono y
        // repetiría el fallo de captar solo el primer buffer con pantalla apagada).
        if (fgMicActivo) {
            activarFgMicrofono()
            return
        }
        // Desde Android 14 (API 34), arrancar un FGS de tipo "microphone" desde
        // segundo plano (p. ej. al llegar una notificación de WhatsApp con la app
        // cerrada) se rechaza con SecurityException porque exige que la app esté
        // en un estado elegible (primer plano o ventana de la notificación). Por eso
        // en API 34+ intentamos PRIMERO arrancar directamente como "microphone": si
        // llegamos aquí dentro de la ventana elegible, el while-in-use del micro
        // queda activo desde el primer momento y la escucha posterior funciona
        // aunque la pantalla esté apagada. Solo si no somos elegibles caemos a
        // "specialUse", que no tiene esa restricción while-in-use y permite ejecutar
        // el TTS; la conmutación a "microphone" se reintentará en escucharRespuesta.
        // El manifest declara "microphone|specialUse" para que ambas APIs pasen
        // la validación del tipo declarado.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                fgMicActivo = true
                LogDiag.d("FGS iniciado directamente como tipo micrófono; micro while-in-use activo")
                Log.d(TAG, "FGS iniciado como tipo micrófono")
            } catch (e: SecurityException) {
                startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                LogDiag.d("FGS arrancado como specialUse (no elegible para micrófono); se reintentará al escuchar")
                Log.d(TAG, "FGS arrancado como specialUse (no elegible para micrófono)")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            // En APIs 30-33 este es ya el tipo correcto (sin "specialUse").
            fgMicActivo = true
        } else {
            startForeground(NOTIF_ID, notificacionActiva())
        }
    }

    /** Mantiene la CPU despierta mientras la pantalla está apagada para que el
     *  reconocimiento de voz (SpeechRecognizer) pueda oír. Sin este wake lock, con
     *  la pantalla apagada el micrófono se activa (suena el tono) pero no capta audio:
     *  el servicio de reconocimiento exige que la app mantenga la CPU despierta. */
    private fun adquirirWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WalinkVoz")
        lock.setReferenceCounted(false)
        // Tiempo máximo de seguridad para no dejar la CPU activa si algo se atasca.
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
        LogDiag.d("WakeLock parcial adquirido (CPU despierta para la sesión de voz)")
        Log.d(TAG, "WakeLock parcial adquirido (reconocimiento con pantalla apagada)")
    }

    /**
     * Renueva el wake lock justo antes de cada escucha. Si la sesión lleva varios
     * segundos (lectura + preguntas), el timeout del wake lock original podría
     * expirar en mitad de la escucha y la CPU volvería a dormirse con la pantalla
     * apagada, dejando al reconocedor sin audio.
     */
    private fun refrescarWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        adquirirWakeLock()
    }

    private fun liberarWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        LogDiag.d("WakeLock liberado")
        Log.d(TAG, "WakeLock liberado")
    }

    /** Mantiene la música pausada (foco GAIN_TRANSIENT retenido) durante toda la
     *  interacción de voz. Sin este foco retenido, al terminar el TTS su foco la
     *  radio/música se reanuda justo al activar el micrófono y el micro captura ese
     *  sonido como primer buffer, rompiendo el reconocimiento de la respuesta. */
    private fun retenerPausaMusica() {
        // Solo pausar en el modo "detener música". El modo "bajar volumen" deja que la
        // música suene atenuada (el propio TTS la atenúa mientras habla).
        if (dataStore.bajarVolumenEnLugarDePausar) return
        if (audioFocusRetenido != null) return
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(atributos)
            .setOnAudioFocusChangeListener { /* no hacemos nada al perder foco */ }
            .build()
        val res = audioManagerPausa.requestAudioFocus(request)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRetenido = request
            Log.d(TAG, "Música pausada (foco retenido) durante la sesión de voz")
        } else {
            Log.d(TAG, "No se pudo retener el foco para pausar la música (res=$res)")
        }
    }

    private fun liberarPausaMusica() {
        val request = audioFocusRetenido ?: return
        audioManagerPausa.abandonAudioFocusRequest(request)
        audioFocusRetenido = null
        Log.d(TAG, "Foco liberado: la música puede reanudarse")
    }

    /**
     * Marca el FGS como tipo "microphone" mientras usamos el reconocedor. Al arrancar
     * desde segundo plano en API 34+ usamos "specialUse" (el tipo "microphone" no puede
     * iniciarse en ese caso). Pero el tipo "specialUse" no mantiene el micrófono activo
     * "while-in-use": con pantalla apagada, el SpeechRecognizer solo capta el primer
     * buffer y luego se queda sin audio (NO_MATCH). Pasando el FGS ya en marcha a tipo
     * "microphone", el sistema concede el uso continuo del micrófono.
     */
    private fun activarFgMicrofono() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                fgMicActivo = true
                LogDiag.d("FGS conmutado a tipo micrófono (API 34+); permiso while-in-use del micro activo")
                Log.d(TAG, "FGS marcado como tipo micrófono (API 34+)")
            } catch (e: Exception) {
                // Es esperado que este cambio falle con la pantalla apagada y la app en
                // segundo plano (SecurityException por no ser elegible para el permiso
                // while-in-use). Si el MicrofonoPermanenteService está activo, el micro
                // ya lo cubre ese servicio y el reconocedor oirá igualmente: se registra
                // como informativo, no como error. Solo es un fallo real cuando NO hay
                // un micro permanente garantizando el permiso.
                if (MicrofonoPermanenteService.estaActivo) {
                    LogDiag.d("FGS propio no conmuta a microphone (esperado: MicrofonoPermanenteService ya cubre el micro)")
                    Log.d(TAG, "No conmuto el FGS a micrófono; el servicio permanente ya lo cubre")
                } else {
                    LogDiag.e("Fallo al marcar el FGS como tipo micrófono", e)
                    Log.w(TAG, "No se pudo marcar el FGS como micrófono", e)
                }
            }
        }
    }

    /**
     * Fuerza el micrófono del teléfono (builtin) como dispositivo de entrada
     * preferido del reconocedor. Cuando hay auriculares Bluetooth conectados el
     * sistema puede enrutar la captura de forma intermitente a los micrófonos del
     * auricular (perfil HFP/SCO); si su micro está en espera, el SpeechRecognizer
     * casi no capta nada (ERROR_NO_MATCH/silencio) y se interpreta como "no
     * escuchado". Forzar el builtin hace el reconocimiento independiente de esa ruta.
     */
    private fun configurarMicrofonoTelefono() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            // Elegimos un dispositivo de comunicación del propio teléfono (altavoz o
            // auricular) en lugar del Bluetooth: así la captura de voz del
            // SpeechRecognizer vuelve al micrófono del teléfono y deja de depender de
            // si el sistema enrutó la entrada al micrófono de los auriculares.
            val comunicacionTelefono = audioManagerPausa
                .getAvailableCommunicationDevices()
                .firstOrNull { d ->
                    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            if (comunicacionTelefono != null) {
                val ok = audioManagerPausa.setCommunicationDevice(comunicacionTelefono)
                LogDiag.d("Mic de voz forzado al teléfono (builtin '${comunicacionTelefono.productName}' type=${comunicacionTelefono.type}): res=$ok")
                Log.d(
                    TAG,
                    "Comunicación forzada al teléfono (micro builtin, res=$ok): '${comunicacionTelefono.productName}' (type=${comunicacionTelefono.type})"
                )
                if (!ok) {
                    Log.w(TAG, "No se pudo forzar la comunicación al teléfono; se deja la ruta por defecto")
                }
            } else {
                Log.w(TAG, "No hay dispositivo de comunicación del teléfono disponible; se deja la ruta por defecto")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error forzando el micro del teléfono", e)
        }
    }

    /** Revierte la comunicación forzada en [configurarMicrofonoTelefono]. */
    private fun limpiarMicrofonoTelefono() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            audioManagerPausa.clearCommunicationDevice()
        } catch (e: Exception) {
            Log.w(TAG, "Error al limpiar el dispositivo de comunicación", e)
        }
    }

    /** Registra en log los dispositivos de entrada y salida activos (diagnóstico). */
    private fun logRutasAudio(contexto: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val entradas = audioManagerPausa
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .joinToString(" | ") { "${it.type}:${it.productName}" }
        val salidas = audioManagerPausa
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(" | ") { "${it.type}:${it.productName}" }
        LogDiag.d("[$contexto] Entradas de audio: $entradas")
        LogDiag.d("[$contexto] Salidas de audio: $salidas")
        Log.d(TAG, "[$contexto] Entradas de audio: $entradas")
        Log.d(TAG, "[$contexto] Salidas de audio: $salidas")
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
        refrescarWakeLock()
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor", e)
            finalizar()
            return
        }
        recognizer = sr.apply { setRecognitionListener(this@LectorAsistenteService) }
        configurarMicrofonoTelefono()
        logRutasAudio("escucharRespuesta")

        val intentReconocimiento = construyeIntentReconocimiento(R.string.pregunta_voz_prompt)
        activarFgMicrofono()
        recognizer?.startListening(intentReconocimiento)
        LogDiag.d("escucharRespuesta: SpeechRecognizer.startListening invocado")
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
        // El reconocedor ya terminó: restauramos la ruta de comunicación (vuelve el
        // Bluetooth) antes de que hable el siguiente TTS.
        limpiarMicrofonoTelefono()
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
        val respuesta = frases.first().lowercase()
        LogDiag.d("onResults: respuesta captada: \"$respuesta\" (etapa=$etapaRespuesta)")
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
                val textoDictado = puntuar(limpiarEco(frases.first()))
                Log.d(TAG, "onResults: dictado recibido: '${frases.first()}' -> limpio: '$textoDictado'")
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
                    DecisionConfirmEnvio.ENVIAR -> {
                        Log.d(TAG, "onResults: usuario quiere ENVIAR")
                        enviarRespuesta()
                    }
                    DecisionConfirmEnvio.CORREGIR -> {
                        Log.d(TAG, "onResults: usuario quiere CORREGIR")
                        corregirRespuesta()
                    }
                    DecisionConfirmEnvio.CANCELAR -> {
                        Log.d(TAG, "onResults: usuario quiere CANCELAR")
                        cancelarRespuesta()
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
        // Al terminar (con error) también restauramos la ruta de comunicación.
        limpiarMicrofonoTelefono()
        logRutasAudio("onError")
        LogDiag.e("Error de reconocimiento: ${nombreError(error)} ($error) picoRMS=${nivelPicoRms}")
        Log.w(TAG, "Error de reconocimiento: ${nombreError(error)} ($error), pico RMS=${"%.0f".format(nivelPicoRms)}")
        reintentarONoEntendido()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        nivelPicoRms = 0f
        logRutasAudio("onReadyForSpeech")
        LogDiag.d("onReadyForSpeech: reconocedor LISTO, escuchando...")
        Log.d(TAG, "onReadyForSpeech: reconocedor listo, escuchando...")
    }
    override fun onBeginningOfSpeech() {
        LogDiag.d("onBeginningOfSpeech: el sistema ha captado VOZ (el micro oye)")
        Log.d(TAG, "onBeginningOfSpeech: el sistema ha detectado voz")
    }
    override fun onRmsChanged(rmsdB: Float) {
        // Si el micro está captando, este valor sube y baja al hablar. Registramos
        // sobre todo los picos para diagnosticar si se oye audio con pantalla apagada.
        if (rmsdB > nivelPicoRms) {
            nivelPicoRms = rmsdB
            if (rmsdB >= 1f) {
                LogDiag.d("onRmsChanged: pico micro ${rmsdB} dB (el micro capta)")
                Log.d(TAG, "onRmsChanged: nivel micro sube a ${"%.1f".format(rmsdB)}")
            }
        }
    }
    override fun onBufferReceived(buffer: ByteArray?) { /* audio crudo, no se usa */ }
    override fun onEndOfSpeech() {
        LogDiag.d("onEndOfSpeech: fin de voz detectado")
        Log.d(TAG, "onEndOfSpeech: fin de voz detectado")
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val parcial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.d(TAG, "onPartialResults: ${parcial?.joinToString(" ")}")
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun nombreError(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        else -> "ERROR_DESCONOCIDO"
    }

    private fun reintentarONoEntendido() {
        cancelarTiempoEscucha()
        recognizer?.destroy()
        recognizer = null
        // El reintento no es igual en todas las etapas: no es lo mismo no entender una
        // respuesta "sí/no" que no captar el dictado libre de la respuesta, o la
        // confirmación de envío. Si es la confirmación de envío, delegamos en su
        // reintento específico (que conserva el borrador y avisa de forma distinta).
        if (etapaRespuesta == ETAPA_CONFIRMAR_ENVIO) {
            reintentarONoEntendidoEnviarOCorregir()
            return
        }
        if (intentos < MAX_INTENTOS) {
            intentos++
            if (etapaRespuesta == ETAPA_DICTADO) {
                // Estábamos captando la respuesta dictada: se pide repetirla.
                lector.hablar(getString(R.string.dictado_no_entendido)) {
                    lector.mainHandler.postDelayed({ escucharTextoLibre() }, 350)
                }
            } else {
                // Etapas "sí/no" (leer o responder): se pide sí o no y se vuelve a escuchar.
                lector.hablar(getString(R.string.pregunta_voz_no_entendido)) {
                    lector.mainHandler.postDelayed({ escucharRespuesta() }, 350)
                }
            }
        } else {
            lector.hablar(getString(R.string.aviso_no_leido)) {
                finalizar()
            }
        }
    }

    /**
 * Quita una "eco/doble" típica del reconocedor de voz, que a veces devuelve la
 * frase del usuario dos veces (p. ej. "bien muchas gracias bien y muchas gracias"):
 * el final vuelve a contener todos los tokens del principio. Solo actúa cuando el
 * final es claramente una repetición (contiene todos los tokens del inicio y no es
 * mucho más largo); si no está claro, se devuelve el texto sin cambios para no
 * perder dictados reales.
 */
private fun limpiarEco(texto: String): String {
    val t = texto.trim()
    val tokens = t.split(" ").filter { it.isNotBlank() }
    if (tokens.size < 3) return t
    // Normalizamos cada token (sin acentos y en minúsculas) para comparar iguales
    // palabras que difieran solo en tilde ("cómo" vs "como"). El texto devuelto
    // conserva la forma original de la primera aparición.
    val tokensNorm = tokens.map { normalizarToken(it) }
    // Buscamos la repetición en el PREFIJO más largo posible (desde el final para no
    // captar fragmentos parciales). Si la segunda parte contiene todos los tokens de la
    // primera, es un eco: nos quedamos con la primera aparición.
    for (split in (tokens.size - 1) downTo 1) {
        val primeraNorm = tokensNorm.take(split)
        val segundaNorm = tokensNorm.drop(split)
        if (segundaNorm.isEmpty()) continue
        val contieneTodo = primeraNorm.all { tok -> tok in segundaNorm }
        if (contieneTodo && segundaNorm.size <= primeraNorm.size + 2) {
            val limpio = tokens.take(split).joinToString(" ")
            Log.d(TAG, "limpiarEco: repetición detectada -> '$limpio' (original='$t')")
            return limpio
        }
    }
    return t
}

/** Elimina acentos y pasa a minúsculas para comparación de tokens tolerante a tildes. */
private fun normalizarToken(s: String): String {
    val descompuesto = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    return descompuesto.replace(Regex("\\p{M}"), "").lowercase()
}

/**
 * Puntuación heurística ligera para el dictado (sin red ni modelos pesados):
 *  - Mayúscula inicial y punto final por defecto.
 *  - Interrogación ("?") cuando la frase arranca con un interrogativo típico.
 *  - Algunas comas seguras (p. ej. ", por favor", ", la verdad") en media frase.
 * No es "puntuación por entonación" real, pero mejora la legibilidad del dictado
 * sin arriesgar el contenido.
 */
private fun puntuar(texto: String): String {
    var t = texto.trim()
    if (t.isEmpty()) return t
    // Mayúscula inicial (primera letra si es una letra).
    t = t[0].uppercaseChar().toString() + t.substring(1)
    // Quitamos signos de puntuación final que vinieran del reconocedor (evita dobles).
    t = t.trimEnd('.', '?', '!', ',', ';', ':')
    t = insertarComasSeguras(t).trim()
    val esPregunta = INTERROGATIVOS.any { t.startsWith(it, ignoreCase = true) }
    return if (esPregunta) t + "?" else t + "."
}

/** Comas solo en casos muy seguros, para no empeorar el dictado. */
private fun insertarComasSeguras(t: String): String {
    var s = t
    if (!s.contains(',')) {
        // " por favor ", " la verdad " en media frase -> ", por favor", ", la verdad".
        s = s.replace(Regex("(?i)\\bpor favor\\b"), ", por favor")
        s = s.replace(Regex("(?i)\\bla verdad\\b"), ", la verdad")
    }
    return s
}

/** true = leer, false = no leer, null = no entendido. */
    private fun decidir(respuesta: String): Boolean? {
        val texto = respuesta.lowercase()
        val quiereSi = contienePalabra(palabrasRecurso(R.array.palabras_si), texto)
        val quiereNo = contienePalabra(palabrasRecurso(R.array.palabras_no), texto)
        return when {
            quiereSi && !quiereNo -> true
            quiereNo && !quiereSi -> false
            else -> null
        }
    }

    /**
     * Palabras de voz aceptadas para un tipo de respuesta, localizadas al idioma
     * activo de la app. Los arrays se resuelven según el locale del contexto
     * localizado del servicio, igual que el TTS y el SpeechRecognizer: si la app
     * está en inglés se acepta "yes"/"no", en alemán "ja"/"nein", etc.
     */
    private fun palabrasRecurso(idRecurso: Int): List<String> =
        try {
            resources.getStringArray(idRecurso).toList()
        } catch (_: Throwable) {
            emptyList()
        }

    /** true si el texto contiene alguna palabra como término completo (o frase multi-palabra). */
    private fun contienePalabra(palabras: List<String>, texto: String): Boolean {
        val t = texto.lowercase()
        return palabras.any { palabra ->
            val patron = palabra.trim()
                .split(Regex("\\s+"))
                .joinToString("\\s+") { Regex.escape(it) }
            Regex("(^|[^\\p{L}\\p{N}])$patron($|[^\\p{L}\\p{N}])").containsMatchIn(t)
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
        liberarWakeLock()
        liberarPausaMusica()
        limpiarMicrofonoTelefono()
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
        adquirirWakeLock()
        // Mismo motivo que en manejarPreguntar: intentar la conmutación a "microphone"
        // cuanto antes (aquí aún cerca de la ventana de la notificación), en vez de
        // esperar a escucharRespuesta. Si el paso a microphone ya se logró antes
        // (manejarPrepararVoz), entrarEnForeground lo reafirma y esto es un no-op.
        activarFgMicrofono()
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
        refrescarWakeLock()
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor para dictado", e)
            finalizar()
            return
        }
        recognizer = sr.apply { setRecognitionListener(this@LectorAsistenteService) }
        configurarMicrofonoTelefono()
        logRutasAudio("escucharTextoLibre")

        val intentReconocimiento = construyeIntentReconocimiento(R.string.pregunta_voz_prompt)
        activarFgMicrofono()
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

    /**
     * Construye el intent de reconocimiento de voz. Comparte configuración entre la
     * pregunta sí/no y el dictado. Pide 3 resultados y deja un margen de silencio
     * para no cortar respuestas muy cortas ("sí"/"no") ni dar por terminado el habla
     * con el primer silencio breve.
     */
    private fun construyeIntentReconocimiento(promptRes: Int): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // El reconocimiento se hace en el idioma activo de la app (igual que el TTS):
            // así la respuesta esperada se capta en el idioma correcto ("sí"/"no" en
            // español, "yes"/"no" en inglés, "ja"/"nein" en alemán, ...).
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                IdiomaHelper.localeResuelto(this@LectorAsistenteService).toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(promptRes))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra("android.speech.extra.MAX_RESULTS", 3)
            // Margen de silencio (ms) antes de dar por terminado el habla, para que
            // no descartar respuestas cortas como "sí"/"no" con un silencio breve.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
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

    /** Decisión del usuario en la confirmación de envío de la respuesta. */
    private enum class DecisionConfirmEnvio { ENVIAR, CORREGIR, CANCELAR }

    /** Enviar, corregir o cancelar la respuesta dictada; null = no entendido. */
    private fun decidirEnviarOCorregir(respuesta: String): DecisionConfirmEnvio? {
        val texto = respuesta.lowercase()
        val quiereEnviar = contienePalabra(palabrasRecurso(R.array.palabras_enviar), texto)
        val quiereCorregir = contienePalabra(palabrasRecurso(R.array.palabras_corregir), texto)
        val quiereCancelar = contienePalabra(palabrasRecurso(R.array.palabras_cancelar), texto)
        return when {
            // La cancelación tiene prioridad: evita que un "no" de la frase se
            // interprete como corregir, y corta el flujo sin enviar nada.
            quiereCancelar -> DecisionConfirmEnvio.CANCELAR
            quiereEnviar && !quiereCorregir -> DecisionConfirmEnvio.ENVIAR
            quiereCorregir && !quiereEnviar -> DecisionConfirmEnvio.CORREGIR
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

    /** Cancela la respuesta en curso: descarta el borrador y finaliza sin enviar. */
    private fun cancelarRespuesta() {
        Log.d(TAG, "onResults: usuario quiere CANCELAR; descartando borrador")
        dataStore.borrarBorradorRespuesta()
        etapaRespuesta = ETAPA_IDLE
        lector.hablar(getString(R.string.respuesta_cancelada)) {
            finalizar()
        }
    }

    /** Reintento cuando no se entiende "enviar", "corregir" o "cancelar". */
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
        fgMicActivo = false
        liberarWakeLock()
        liberarPausaMusica()
        limpiarMicrofonoTelefono()
        Log.d(TAG, "LectorAsistenteService destruido")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalinkLectorVoz"

        const val ACTION_PREPARAR_VOZ = "com.jjcampac.walink.action.PREPARAR_VOZ"
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

        // Canal y notificación compartidos con MicrofonoPermanenteService (el canal se
        // crea idempotentemente en ambos servicios).
        const val CANAL = "walink_asistente_voz"
        const val NOTIF_ID = 40
        private const val MAX_INTENTOS = 2
        // Si en este tiempo no se oye ninguna respuesta, se trata como "no entendido".
        private const val TIEMPO_ESCUCHA_MS = 12000L
        // Máximo de retención del wake lock para no dejar la CPU activa si algo se atasca.
        private const val WAKE_LOCK_TIMEOUT_MS = 90_000L
        // Tiempo máximo para que la pregunta hablada termine; si no, se activa el
        // micrófono igualmente (evita ciclos congelados por un onDone TTS perdido).
        private const val WATCHDOG_PREGUNTA_MS = 6000L

        // Las palabras aceptadas como respuesta (sí/no, enviar/corregir/cancelar) se
        // leen de recursos localizados por idioma (arrays `palabras_*`).
        // Interrogativos con los que una frase dictada suele ser una pregunta. Se usa
        // el espacio tras el token para descartar casos como "quédate" que no preguntan.
        private val INTERROGATIVOS = listOf(
            "qué ", "quién ", "quiénes ", "cuándo ", "dónde ", "adónde ",
            "cómo ", "cuánto ", "cuánta ", "cuántos ", "cuántas ", "por qué "
        )
    }
}
