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
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.app.PendingIntent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag
import com.jjcampac.walink.idioma.IdiomaHelper
import com.jjcampac.walink.notasvoz.LecturaNotaVoz
import com.jjcampac.walink.notasvoz.ReproductorNotaVoz
import com.jjcampac.walink.service.DatosRespuestaHelper
import com.jjcampac.walink.service.MicrofonoPermanenteService
import com.jjcampac.walink.service.WhatsAppNotificationListener
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
    // Nota de voz: el mensaje no tiene texto útil, hay que reproducir el .opus.
    private var esNotaDeVoz = false
    private var marcaMensaje = 0L

    // true si el mensaje viene del simulador (modo de pruebas): la nota de voz se busca
    // sin ventana de tiempo, porque no hay un audio recién descargado.
    private var mensajeSimulado = false
    private var tareaWatchdogPregunta: Runnable? = null
    // Pico de nivel de audio (onRmsChanged) del reconocedor, para diagnóstico.
    private var nivelPicoRms = 0f
    // Tarea que espera a que el SCO esté realmente enrutado antes de escuchar.
    private var tareaEsperaRuta: Runnable? = null
    // Preparación de ruta en curso: dispositivo objetivo, instante de inicio y
    // callbacks que esperan. Compartir la espera evita reiniciar el reloj del SCO
    // si dos escuchas seguidas piden la ruta antes de que se complete la primera.
    private var objetivoRuta: AudioDeviceInfo? = null
    private var inicioRuta = 0L
    private val callbacksRuta = mutableListOf<(RutaMicro) -> Unit>()
    // true mientras hay una escucha en curso: descarta callbacks tardíos
    // (onResults/onError repetidos) que cortarían el turno siguiente.
    private var escuchaActiva = false
    // Tono breve que avisa de que el micrófono ya está abierto.
    private var tonoEscucha: ToneGenerator? = null
    // Identificador de la escucha en curso: invalida preparaciones de ruta que lleguen
    // tarde (p. ej. si la sesión se finaliza o se encola otro mensaje mientras se
    // esperaba al SCO).
    private var generacionEscucha = 0
    // Tono de aviso de que se puede hablar. En Bluetooth se retrasa hasta que el SCO
    // entrega audio de verdad: medido con los Pixel Buds en el Nothing Phone (3a), el
    // primer buffer audible llega ~1,3 s después de onReadyForSpeech. Si el tono suena
    // antes, el usuario habla mientras el micro aún no capta y se pierde el principio
    // de la frase (dictados cortados: "vale, quedo a las cinco" -> "a las cinco").
    private var tareaTono: Runnable? = null
    // true mientras el AudioManager está en MODE_IN_COMMUNICATION para el SCO. Sin ese
    // modo el framework aplica el dispositivo de comunicación pero el SCO tarda en
    // entregar audio (los logs mostraban modo=0 durante toda la escucha).
    private var modoComunicacionActivo = false
    // Último resultado parcial del reconocedor. El motor a veces detecta voz y devuelve
    // ERROR_NO_MATCH aunque el parcial ya contenía la palabra ("cancelar", "enviar"…).
    // Se usa como red de seguridad en las etapas de sí/no y de confirmación, validando
    // siempre el texto contra las listas de palabras antes de actuar.
    private var ultimoResultadoParcial: String? = null

    // ---- Flujo de responder tras leer ----
    // 0 = idle, 1 = ¿leer el mensaje? (ACTION_PREGUNTAR), 2 = ¿responder? (ACTION_RESPONDER), 3 = dictado libre, 4 = ¿enviar o corregir?
    private var etapaRespuesta = 0
    private var pendingIntentResponder: PendingIntent? = null
    private var clavesRemoteInput: List<String> = emptyList()
    // Clave de la notificación de la que se extrajeron los datos de respuesta: permite
    // volver a leerlos justo antes de enviar (WhatsApp reemplaza la notificación del
    // chat con cada mensaje y el PendingIntent anterior deja de entregar el t

    // ---- Cola de mensajes pendientes ----
    // Cuando llega un nuevo mensaje mientras el servicio está ocupado (preguntando
    // o leyendo otro), en lugar de sobrescribir las variables del ciclo actual se
    // encola aquí. Al terminar cada ciclo se saca el siguiente y se procesa, así
    // ningún mensaje se pierde por llegar "demasiado pronto".
    private data class MensajePendiente(
        val remitente: String,
        val texto: String,
        val esNotaDeVoz: Boolean,
        val marcaMensaje: Long,
        val claveNotificacion: String?,
        val pendingIntent: PendingIntent?,
        val clavesRemoteInput: List<String>,
        val simulado: Boolean,
        val recibidaEn: Long = System.currentTimeMillis()
    )
    private val colaPendientes = ArrayDeque<MensajePendiente>()
    // Número de mensajes de esta sesión que se han leído, ignorado o quedan pendientes.
    // Se registra en el diagnóstico para saber qué se ha leído y qué no.
    private var mensajesLeidos = 0
    private var mensajesIgnorados = 0
    private var claveNotificacion: String? = null
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
        recognizer?.cancel()
        escuchaActiva = false
        cancelarEsperaRuta()
        generacionEscucha++

        // Extraer los datos del intent en variables locales. Si el servicio ya está
        // ocupado (etapa != IDLE), encolamos en lugar de sobrescribir las variables
        // del ciclo actual: así ningún mensaje se pierde por llegar demasiado pronto.
        val remitenteNuevo = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
        val textoNuevo = intent.getStringExtra(EXTRA_TEXTO) ?: ""
        val esNotaVoz = intent.getBooleanExtra(EXTRA_ES_NOTA_VOZ, false)
        val marcaNueva = intent.getLongExtra(EXTRA_MARCA_MENSAJE, 0L)
        val simulado = intent.getBooleanExtra(EXTRA_SIMULADO, false)

        if (etapaRespuesta != ETAPA_IDLE) {
            colaPendientes.add(MensajePendiente(
                remitente = remitenteNuevo,
                texto = textoNuevo,
                esNotaDeVoz = esNotaVoz,
                marcaMensaje = marcaNueva,
                claveNotificacion = intent.getStringExtra(EXTRA_CLAVE_NOTIFICACION),
                pendingIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_PENDING_INTENT_RESPONDER),
                clavesRemoteInput = intent.getStringArrayListExtra(EXTRA_CLAVES_REMOTE_INPUT)?.toList() ?: emptyList(),
                simulado = simulado
            ))
            Log.d(TAG, "Servicio ocupado (etapa=$etapaRespuesta): mensaje de '$remitenteNuevo' encolado (${colaPendientes.size} pendientes)")
            LogDiag.d("Mensaje encolado: '$remitenteNuevo' (pendientes=${colaPendientes.size})")
            // Avisar por voz brevemente y terminar este ciclo para no bloquear el siguiente.
            lector.hablar(getString(R.string.mensaje_en_cola, remitenteNuevo)) {
                finalizar()
            }
            return
        }

        remitente = remitenteNuevo
        textoParaLeer = textoNuevo
        esNotaDeVoz = esNotaVoz
        marcaMensaje = marcaNueva
        mensajeSimulado = simulado
        if (esNotaDeVoz) {
            Log.d(TAG, "El mensaje es una nota de voz (marca=$marcaMensaje, simulado=$mensajeSimulado)")
            LogDiag.d(
                "ACTION_PREGUNTAR: nota de voz (marca=$marcaMensaje, simulado=$mensajeSimulado)"
            )
        }
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
        if (!activarFgMicrofono()) {
            // Sin permiso while-in-use del micrófono no tiene sentido preguntar "¿lo leo?":
            // el usuario respondería y no se oiría nada. manejarMicroNoDisponible degrada
            // a lectura directa (etapa "¿lo leo?"), dejando el aviso de reactivación.
            LogDiag.e("ACTION_PREGUNTAR sin micrófono utilizable (sin while-in-use)")
            manejarMicroNoDisponible()
            return
        }
        hacerPregunta()
    }

    /**
     * Reintenta arrancar el micro permanente y deja el aviso accionable para que el
     * usuario pueda recuperar el permiso while-in-use con un solo toque (al tocar una
     * notificación, Android sí permite arrancar un FGS de tipo "microphone").
     */
    private fun intentarReactivarMicrofono() {
        MicrofonoPermanenteService.iniciar(this)
        MicrofonoPermanenteService.avisarReactivacion(this)
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
     *
     * @return true si el micrófono tiene acceso while-in-use (bien por este FGS, bien
     *         porque lo cubre [MicrofonoPermanenteService]); false si no hay ninguna de
     *         las dos cosas y, por tanto, el reconocedor no captaría audio.
     */
    private fun activarFgMicrofono(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // En APIs 30-33 el tipo ya se fijó al entrar en primer plano (o no aplica):
            // el micro está disponible para el reconocedor.
            return true
        }
        if (fgMicActivo) {
            MicrofonoPermanenteService.cancelarAviso(this)
            return true
        }
        return try {
            startForeground(NOTIF_ID, notificacionActiva(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            fgMicActivo = true
            MicrofonoPermanenteService.cancelarAviso(this)
            LogDiag.d("FGS conmutado a tipo micrófono (API 34+); permiso while-in-use del micro activo")
            Log.d(TAG, "FGS marcado como tipo micrófono (API 34+)")
            true
        } catch (e: Exception) {
            // Este cambio falla con SecurityException cuando la app no está en un estado
            // elegible para el permiso foreground-only RECORD_AUDIO (pantalla apagada y
            // app en segundo plano). Si MicrofonoPermanenteService está activo, el micro
            // ya lo cubre ese servicio y el reconocedor oirá igualmente: es esperado.
            if (MicrofonoPermanenteService.estaActivo) {
                LogDiag.d("FGS propio no conmuta a microphone (esperado: MicrofonoPermanenteService ya cubre el micro)")
                Log.d(TAG, "No conmuto el FGS a micrófono; el servicio permanente ya lo cubre")
                true
            } else {
                LogDiag.e(
                    "SIN permiso while-in-use del micrófono (el FGS permanente no está activo): " +
                        "el reconocedor no captaría audio",
                    e
                )
                Log.w(TAG, "No se pudo marcar el FGS como micrófono y no hay micro permanente", e)
                false
            }
        }
    }

    /**
     * Ruta de entrada elegida para el reconocimiento de voz.
     *
     * @param etiqueta texto descriptivo para el diagnóstico.
     * @param bluetooth true si se capta por el micrófono de un dispositivo Bluetooth.
     */
    private data class RutaMicro(val etiqueta: String, val bluetooth: Boolean)

    /** true si el dispositivo es un micrófono Bluetooth utilizable como entrada de voz. */
    private fun esEntradaBluetooth(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /**
     * Prepara la entrada de audio para escuchar y avisa cuando LA RUTA YA ESTÁ
     * APLICADA de verdad (no solo solicitada).
     *
     * Prioridad:
     *  1. Micrófono Bluetooth (auriculares, manos libres del coche): es el único
     *     utilizable cuando el teléfono va en el bolsillo.
     *  2. Micrófono builtin del teléfono, como respaldo.
     *
     * Por qué no basta con `setCommunicationDevice` + `startListening` seguidos:
     * comprobado en el Nothing Phone (3a) con los Pixel Buds (SCO/HFP), el framework
     * tarda ~1,6 s en aplicar la ruta (en `dumpsys audio`, `onCommunicationDeviceChanged`
     * llega 1,6 s después de `setCommunicationDevice`). Si el reconocedor arranca antes,
     * su AudioRecord se crea con la ruta anterior y, cuando el SCO conmuta a mitad de la
     * captura, el motor recibe audio ininteligible: `onBeginningOfSpeech` seguido de
     * `ERROR_NO_MATCH` con picoRMS alto (justo el fallo de las 12:46). Por eso aquí se
     * espera a que `getCommunicationDevice()` devuelva el dispositivo elegido y solo
     * entonces se escucha. Si el SCO no llega en [ESPERA_RUTA_BT_MS], se usa el
     * micrófono del teléfono para no dejar el turno sin oír nada.
     */
    private fun prepararMicrofonoVoz(alListo: (RutaMicro) -> Unit) {
        // Si ya hay una preparación en marcha (dos escuchas seguidas), nos
        // enganchamos a ella en lugar de reiniciar la espera del SCO: reiniciarla
        // retrasaría el momento de abrir el micrófono.
        if (objetivoRuta != null) {
            callbacksRuta.add(alListo)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Antes de Android 12 no existe setCommunicationDevice: ruta del sistema.
            alListo(RutaMicro("ruta del sistema", false))
            return
        }
        val dispositivos = try {
            audioManagerPausa.availableCommunicationDevices
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron consultar los dispositivos de comunicación", e)
            alListo(RutaMicro("ruta del sistema", false))
            return
        }
        val bluetooth = dispositivos.firstOrNull { esEntradaBluetooth(it) }
        val builtin = dispositivos.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        if (bluetooth == null) {
            // Sin Bluetooth no se toca la ruta: la del sistema ya es el micro del
            // teléfono y así no se altera el enrutado del TTS ni de la música.
            LogDiag.d("Ruta de voz: sin Bluetooth; se usa la ruta por defecto del sistema")
            alListo(RutaMicro("ruta del sistema", false))
            return
        }
        val elegido = bluetooth

        // El modo de comunicación es lo que hace que el framework trate el SCO como una
        // llamada. Sin él (los logs mostraban modo=0) el dispositivo se aplica, pero el
        // audio del auricular tarda ~1,3 s en llegar al reconocedor y se pierde el
        // principio de la frase.
        activarModoComunicacion()

        val yaAplicado = audioManagerPausa.communicationDevice?.id == elegido.id
        if (yaAplicado) {
            LogDiag.d("Ruta de voz: '${elegido.productName}' ya estaba aplicada (type=${elegido.type})")
            alListo(RutaMicro("Bluetooth '${elegido.productName}'", true))
            return
        }

        val ok = try {
            audioManagerPausa.setCommunicationDevice(elegido)
        } catch (e: Exception) {
            Log.w(TAG, "Error seleccionando el dispositivo de entrada", e)
            false
        }
        LogDiag.d("Ruta de voz: solicitado Bluetooth '${elegido.productName}' (type=${elegido.type}): res=$ok")
        Log.d(TAG, "Ruta de voz solicitada: Bluetooth '${elegido.productName}' (type=${elegido.type}, res=$ok)")
        if (!ok) {
            // Sin SCO no hay nada que esperar: se escucha por el teléfono.
            alListo(usarMicroTelefono(builtin, "no se pudo solicitar el SCO"))
            return
        }

        // SCO solicitado: no escuchar hasta que el framework lo haya aplicado.
        objetivoRuta = elegido
        inicioRuta = SystemClock.elapsedRealtime()
        callbacksRuta.clear()
        callbacksRuta.add(alListo)
        esperarRutaBluetooth(elegido, builtin, inicioRuta)
    }

    /**
     * Comprueba periódicamente (sin bloquear el hilo principal) si el SCO ya está
     * enrutado. En cuanto `getCommunicationDevice()` devuelve el dispositivo pedido,
     * se deja un margen corto y se empieza a escuchar.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun esperarRutaBluetooth(
        objetivo: AudioDeviceInfo,
        builtin: AudioDeviceInfo?,
        inicio: Long
    ) {
        val aplicado = audioManagerPausa.communicationDevice
        if (aplicado != null && aplicado.id == objetivo.id) {
            val transcurrido = SystemClock.elapsedRealtime() - inicio
            // Margen extra: el auricular abre su micrófono unos milisegundos después
            // de conmutar la ruta; empezar justo en el cambio pierde las primeras sílabas.
            val tarea = Runnable {
                LogDiag.d(
                    "Ruta de voz: Bluetooth '${objetivo.productName}' aplicado en $transcurrido ms; " +
                        "margen de ${MARGEN_POST_RUTA_BT_MS} ms y a escuchar"
                )
                Log.d(TAG, "Ruta Bluetooth lista tras $transcurrido ms; escuchando")
                completarRuta(RutaMicro("Bluetooth '${objetivo.productName}'", true))
            }
            tareaEsperaRuta = tarea
            lector.mainHandler.postDelayed(tarea, MARGEN_POST_RUTA_BT_MS)
            return
        }
        if (SystemClock.elapsedRealtime() - inicio >= ESPERA_RUTA_BT_MS) {
            completarRuta(usarMicroTelefono(builtin, "el SCO no se aplicó en $ESPERA_RUTA_BT_MS ms"))
            return
        }
        val tarea = Runnable { esperarRutaBluetooth(objetivo, builtin, inicio) }
        tareaEsperaRuta = tarea
        lector.mainHandler.postDelayed(tarea, INTERVALO_RUTA_BT_MS)
    }

    /** Cambia al micrófono del teléfono cuando la ruta Bluetooth no está disponible. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun usarMicroTelefono(builtin: AudioDeviceInfo?, motivo: String): RutaMicro {
        // También en modo comunicación: así el micro del teléfono se comporta como en una
        // llamada y el reconocedor recibe audio estable.
        activarModoComunicacion()
        val aplicado = builtin?.let {
            try {
                audioManagerPausa.setCommunicationDevice(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error seleccionando el micrófono del teléfono", e)
                false
            }
        } ?: false
        LogDiag.d("Ruta de voz: $motivo; se usa el micrófono del teléfono (res=$aplicado)")
        Log.w(TAG, "Ruta de voz: $motivo; se usa el micrófono del teléfono (res=$aplicado)")
        return RutaMicro(if (aplicado) "teléfono" else "ruta del sistema", false)
    }

    /** Entrega la ruta ya preparada a todos los que la esperaban. */
    private fun completarRuta(ruta: RutaMicro) {
        val pendientes = callbacksRuta.toList()
        callbacksRuta.clear()
        objetivoRuta = null
        inicioRuta = 0L
        tareaEsperaRuta = null
        if (pendientes.isEmpty()) {
            Log.d(TAG, "Ruta de voz preparada sin nadie esperando: ${ruta.etiqueta}")
        }
        pendientes.forEach { it(ruta) }
    }

    /** Cancela una espera de ruta pendiente (fin de sesión o nueva escucha). */
    private fun cancelarEsperaRuta() {
        tareaEsperaRuta?.let { lector.mainHandler.removeCallbacks(it) }
        tareaEsperaRuta = null
        objetivoRuta = null
        inicioRuta = 0L
        callbacksRuta.clear()
    }

    /**
     * Emite un tono breve justo antes de abrir el micrófono. Con Bluetooth hay que
     * esperar ~1,5 s a que el SCO esté aplicado; sin esta señal el usuario no sabe
     * cuándo puede hablar y su respuesta se pierde por decirla demasiado pronto.
     */
    private fun avisarEscuchando() {
        try {
            val generador = tonoEscucha
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tonoEscucha = it }
            generador.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo emitir el tono de escucha", e)
        }
    }

    /**
     * Programa el tono de "puedes hablar".
     *
     * Sin Bluetooth suena de inmediato: la ruta del teléfono ya entrega audio. Con
     * Bluetooth se retrasa [RETRASO_TONO_BT_MS] porque el SCO tarda ~1,3 s en entregar
     * el primer buffer audible (comprobado con los Pixel Buds). Así, cuando el usuario
     * oye el tono y empieza a hablar, el reconocedor ya está captando de verdad y no se
     * pierde el principio de la frase.
     */
    private fun programarTonoEscucha(esBluetooth: Boolean) {
        cancelarTonoEscucha()
        if (!esBluetooth) {
            avisarEscuchando()
            return
        }
        val tarea = Runnable {
            tareaTono = null
            if (escuchaActiva) {
                LogDiag.d("Tono de escucha emitido (Bluetooth, ${RETRASO_TONO_BT_MS} ms tras abrir el micro)")
                avisarEscuchando()
            }
        }
        tareaTono = tarea
        lector.mainHandler.postDelayed(tarea, RETRASO_TONO_BT_MS)
    }

    private fun cancelarTonoEscucha() {
        tareaTono?.let { lector.mainHandler.removeCallbacks(it) }
        tareaTono = null
    }

    /** Revierte la comunicación forzada durante la escucha. */
    private fun limpiarMicrofonoTelefono() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            audioManagerPausa.clearCommunicationDevice()
        } catch (e: Exception) {
            Log.w(TAG, "Error al limpiar el dispositivo de comunicación", e)
        }
        desactivarModoComunicacion()
    }

    /** Pone el audio en modo comunicación (como una llamada) para que el SCO sea estable. */
    private fun activarModoComunicacion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (modoComunicacionActivo) return
        try {
            audioManagerPausa.mode = AudioManager.MODE_IN_COMMUNICATION
            modoComunicacionActivo = true
            LogDiag.d("Modo de audio: MODE_IN_COMMUNICATION (SCO estable)")
            Log.d(TAG, "Modo de audio de comunicación activado para el SCO")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo activar el modo de comunicación", e)
        }
    }

    /** Devuelve el audio al modo normal (música y TTS por A2DP). */
    private fun desactivarModoComunicacion() {
        if (!modoComunicacionActivo) return
        try {
            audioManagerPausa.mode = AudioManager.MODE_NORMAL
            Log.d(TAG, "Modo de audio restaurado a normal")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar el modo de audio normal", e)
        }
        modoComunicacionActivo = false
    }

    /** Registra en log las rutas de audio y la ruta de entrada realmente aplicada. */
    private fun logRutasAudio(contexto: String, ruta: RutaMicro? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val entradas = audioManagerPausa
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .joinToString(" | ") { "${it.type}:${it.productName}" }
        val salidas = audioManagerPausa
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(" | ") { "${it.type}:${it.productName}" }
        val comunicacion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManagerPausa.communicationDevice?.let { "${it.type}:${it.productName}" } ?: "ninguno"
        } else {
            "no aplica (API < 31)"
        }
        val lineaRuta = ruta?.let { " -> entrada=${it.etiqueta}" } ?: ""
        LogDiag.d("[$contexto] Ruta de voz: comunicación=$comunicacion, modo=${audioManagerPausa.mode}$lineaRuta")
        LogDiag.d("[$contexto] Entradas de audio: $entradas")
        LogDiag.d("[$contexto] Salidas de audio: $salidas")
        Log.d(TAG, "[$contexto] Ruta de voz: comunicación=$comunicacion, modo=${audioManagerPausa.mode}$lineaRuta")
        Log.d(TAG, "[$contexto] Entradas de audio: $entradas")
        Log.d(TAG, "[$contexto] Salidas de audio: $salidas")
    }

    /**
     * Registra qué grabaciones hay activas y por qué dispositivo va cada una.
     *
     * `onRmsChanged` solo dice que llega audio, no de dónde. Este dato es el que
     * permite saber si el SpeechRecognizer está captando de verdad por el SCO o si,
     * a pesar de tener el SCO seleccionado, sigue usando el micrófono del teléfono.
     */
    private fun logGrabacionesActivas(contexto: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val activas = audioManagerPausa.activeRecordingConfigurations
            if (activas.isEmpty()) {
                LogDiag.d("[$contexto] Grabaciones activas: ninguna")
                return
            }
            val detalle = activas.joinToString(" | ") { c ->
                val dispositivo = c.audioDevice
                    ?.let { "${it.type}:${it.productName}" } ?: "desconocido"
                "source=${c.clientAudioSource}, dispositivo=$dispositivo"
            }
            LogDiag.d("[$contexto] Grabaciones activas: $detalle")
            Log.d(TAG, "[$contexto] Grabaciones activas: $detalle")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron consultar las grabaciones activas", e)
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
            if (!preguntaHablando) {
                // El watchdog ya abrió el micro: este onDone atrasado volvería a iniciar
                // otra escucha encima de la que está en curso (y se captaría el eco).
                Log.d(TAG, "onDone atrasado de la pregunta (watchdog ya actuó); se ignora")
                return@hablar
            }
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
    private fun escucharRespuesta() = escucharVoz("escucharRespuesta")

    /**
     * Punto único de escucha: comprueba el permiso while-in-use, prepara la ruta del
     * micrófono y solo arranca el reconocedor cuando la ruta está aplicada de verdad
     * (ver [prepararMicrofonoVoz]).
     */
    private fun escucharVoz(etiqueta: String) {
        Log.d(TAG, "$etiqueta: activando micrófono")
        refrescarWakeLock()
        // Sin permiso while-in-use el reconocedor arranca pero no capta audio: se
        // comprueba ANTES de crearlo para no encadenar reintentos mudos.
        if (!activarFgMicrofono()) {
            manejarMicroNoDisponible()
            return
        }
        val generacion = ++generacionEscucha
        prepararMicrofonoVoz { ruta ->
            if (generacion != generacionEscucha) {
                Log.d(TAG, "$etiqueta: la ruta se preparó tarde; escucha ya cancelada")
                return@prepararMicrofonoVoz
            }
            iniciarEscucha(etiqueta, ruta)
        }
    }

    /**
     * Devuelve la instancia única del reconocedor para toda la sesión de voz.
     *
     * Se reutiliza entre turnos a propósito: crear y destruir un SpeechRecognizer
     * dentro de los callbacks (onResults/onError) deja al servicio de reconocimiento
     * con la sesión anterior a medio cerrar y el `startListening` del turno
     * siguiente no llega a arrancar (síntoma: la primera pregunta se oye y la de
     * "¿quieres responder?" ya no). Solo se destruye al terminar el servicio.
     */
    private fun obtenerRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return try {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
                recognizer = it
                Log.d(TAG, "SpeechRecognizer creado (se reutilizará durante la sesión)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear el reconocedor", e)
            null
        }
    }

    /** Crea el reconocedor y empieza a escuchar con la ruta de entrada ya aplicada. */
    private fun iniciarEscucha(etiqueta: String, ruta: RutaMicro) {
        val sr = obtenerRecognizer()
        if (sr == null) {
            finalizar()
            return
        }
        // Por si quedó una escucha anterior abierta: se cancela sin destruir. Si no
        // hay ninguna activa no se llama a cancel(), para no encadenar
        // cancel()+startListening() sobre el mismo reconocedor.
        if (escuchaActiva) {
            Log.d(TAG, "$etiqueta: se cancela la escucha anterior antes de reutilizar el reconocedor")
            sr.cancel()
            escuchaActiva = false
        }
        // Deja limpio cualquier temporizador o tono de una escucha anterior antes de
        // programar los de este turno (cancelarTiempoEscucha también cancela el tono).
        cancelarTiempoEscucha()
        logRutasAudio(etiqueta, ruta)
        // Aviso acústico de que ya puede hablar. En Bluetooth no suena aquí sino cuando
        // el SCO ya entrega audio (ver [programarTonoEscucha]); si sonara ahora, el
        // usuario hablaría ~1,3 s antes de que el micro capture y se perdería el
        // principio de la frase.
        programarTonoEscucha(ruta.bluetooth)
        escuchaActiva = true
        ultimoResultadoParcial = null

        sr.startListening(construyeIntentReconocimiento(R.string.pregunta_voz_prompt))
        LogDiag.d("$etiqueta: SpeechRecognizer.startListening invocado (entrada=${ruta.etiqueta})")
        Log.d(TAG, "SpeechRecognizer escuchando ($etiqueta, entrada=${ruta.etiqueta})...")

        // Si no se oye respuesta en un tiempo, no quedarse colgado esperando. El dictado
        // libre tiene más margen porque una frase larga supera los 12 s.
        val duracion = if (etapaRespuesta == ETAPA_DICTADO) {
            TIEMPO_ESCUCHA_DICTADO_MS
        } else {
            TIEMPO_ESCUCHA_MS
        }
        val tarea = Runnable {
            Log.w(TAG, "$etiqueta: tiempo de escucha agotado sin respuesta")
            reintentarONoEntendido()
        }
        tiempoEscucha = tarea
        lector.mainHandler.postDelayed(tarea, duracion)
    }

    // ---- Resultados del reconocimiento de voz ----

    override fun onResults(results: Bundle?) {
        if (!escuchaActiva) {
            Log.d(TAG, "onResults tardío ignorado (no hay escucha activa)")
            return
        }
        escuchaActiva = false
        // El reconocedor ya terminó: restauramos la ruta de comunicación (vuelve el
        // Bluetooth) antes de que hable el siguiente TTS. La instancia NO se destruye:
        // se reutiliza en el siguiente turno (ver [obtenerRecognizer]).
        limpiarMicrofonoTelefono()
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
                        mensajesLeidos++
                        Log.d(TAG, "onResults: usuario quiere LEER")
                        LogDiag.d("Mensaje leído: '$remitente' (leidos=$mensajesLeidos, ignorados=$mensajesIgnorados, pendientes=${colaPendientes.size})")
                        if (!lector.inicializado) {
                            Log.w(TAG, "onResults: TTS sin inicializar; esperando 800 ms")
                            lector.mainHandler.postDelayed({
                                leerMensajeYOfrecerResponder()
                            }, 800)
                        } else if (lector.apagado) {
                            Log.e(TAG, "onResults: lector.apagado, no se puede leer")
                        } else {
                            Log.d(TAG, "onResults: pidiendo lectura del mensaje")
                            leerMensajeYOfrecerResponder()
                        }
                    }
                    false -> {
                        mensajesIgnorados++
                        Log.d(TAG, "onResults: respuesta NO -> no se lee")
                        LogDiag.d("Mensaje ignorado: '$remitente' (leidos=$mensajesLeidos, ignorados=$mensajesIgnorados, pendientes=${colaPendientes.size})")
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

                // Envío rápido: si es una confirmación inequívoca ("vale", "de acuerdo"…)
                // y el usuario lo tiene activado, se envía sin repetirla ni preguntar.
                // Sigue siendo una lista blanca: nada que no esté reconocido se envía solo.
                if (dataStore.envioRapidoRespuestasCortas && RespuestaCorta.esCorta(textoDictado)) {
                    Log.d(TAG, "Dictado corto inequívoco ('$textoDictado'): envío directo")
                    LogDiag.d("Respuesta corta '$textoDictado': envío directo sin confirmar")
                    lector.hablar(getString(R.string.respuesta_corta_enviando, textoDictado)) {
                        enviarRespuesta(decirEnviando = false)
                    }
                    return
                }

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
        if (!escuchaActiva) {
            Log.d(TAG, "onError tardío ignorado: ${nombreError(error)} ($error)")
            return
        }
        // Red de seguridad: el motor a veces capta voz (onBeginningOfSpeech, pico alto)
        // y aun así devuelve ERROR_NO_MATCH. Si el parcial ya contenía una palabra
        // reconocible para la etapa, se procesa como si hubiera sido un resultado final.
        if (error == SpeechRecognizer.ERROR_NO_MATCH) {
            val parcial = ultimoResultadoParcial
            if (!parcial.isNullOrBlank() && parcialValido(parcial)) {
                Log.d(TAG, "NO_MATCH recuperado con el parcial: '$parcial' (etapa=$etapaRespuesta)")
                LogDiag.d("onError NO_MATCH recuperado con parcial: '$parcial' (etapa=$etapaRespuesta)")
                val sintetico = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(parcial)
                    )
                }
                onResults(sintetico)
                return
            }
        }
        escuchaActiva = false
        cancelarTiempoEscucha()
        // Al terminar (con error) también restauramos la ruta de comunicación.
        limpiarMicrofonoTelefono()
        logRutasAudio("onError")
        LogDiag.e("Error de reconocimiento: ${nombreError(error)} ($error) picoRMS=${nivelPicoRms}")
        Log.w(TAG, "Error de reconocimiento: ${nombreError(error)} ($error), pico RMS=${"%.0f".format(nivelPicoRms)}")
        reintentarONoEntendido()
    }

    /**
     * Indica si un resultado parcial contiene una respuesta válida para la etapa actual.
     * Solo se acepta en las etapas de palabras clave (sí/no y confirmación); el dictado
     * libre nunca se cierra con un parcial, que podría estar a medias.
     */
    private fun parcialValido(parcial: String): Boolean = when (etapaRespuesta) {
        ETAPA_SI_NO_LEER, ETAPA_SI_NO_RESPONDER -> decidir(parcial) != null
        ETAPA_CONFIRMAR_ENVIO -> decidirEnviarOCorregir(parcial) != null
        else -> false
    }

    override fun onReadyForSpeech(params: Bundle?) {
        nivelPicoRms = 0f
        logRutasAudio("onReadyForSpeech")
        logGrabacionesActivas("onReadyForSpeech")
        LogDiag.d("onReadyForSpeech: reconocedor LISTO, escuchando...")
        Log.d(TAG, "onReadyForSpeech: reconocedor listo, escuchando...")
    }
    override fun onBeginningOfSpeech() {
        logGrabacionesActivas("onBeginningOfSpeech")
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
        val parciales = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.d(TAG, "onPartialResults: ${parciales?.joinToString(" ")}")
        // Guardamos el último parcial no vacío: si el resultado final se pierde con
        // ERROR_NO_MATCH, permite recuperar palabras clave ("cancelar", "enviar"…).
        parciales?.firstOrNull { it.isNotBlank() }?.let { ultimoResultadoParcial = it }
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
        recognizer?.cancel()
        escuchaActiva = false
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
        // El tono de "puedes hablar" solo tiene sentido mientras la escucha sigue viva:
        // si ya llegó el resultado o se canceló, no debe sonar a destiempo.
        cancelarTonoEscucha()
    }

    private fun finalizar() {
        cancelarTiempoEscucha()
        cancelarWatchdogPregunta()
        preguntaHablando = false
        etapaRespuesta = ETAPA_IDLE
        recognizer?.cancel()
        escuchaActiva = false
        cancelarEsperaRuta()
        generacionEscucha++
        liberarWakeLock()
        liberarPausaMusica()
        limpiarMicrofonoTelefono()
        stopForeground(STOP_FOREGROUND_REMOVE)
        fgMicActivo = false

        // Si hay mensajes pendientes en cola (llegaron mientras estábamos
        // ocupados preguntando o leyendo otro), procesar el siguiente en
        // lugar de detener el servicio. Así ningún mensaje se pierde.
        if (colaPendientes.isNotEmpty()) {
            Log.d(TAG, "Finalizando ciclo: hay ${colaPendientes.size} mensaje(s) pendiente(s) en cola")
            LogDiag.d("Pendientes en cola: ${colaPendientes.size}; procesando el siguiente")
            procesarSiguientePendiente()
        } else {
            LogDiag.d("Sesión de voz terminada: leidos=$mensajesLeidos, ignorados=$mensajesIgnorados, pendientes=${colaPendientes.size}")
            stopSelf()
        }
    }

    /** Saca el siguiente mensaje de la cola de pendientes y lo procesa como si acabara de llegar. */
    private fun procesarSiguientePendiente() {
        val pendiente = colaPendientes.removeFirst()
        Log.d(TAG, "Procesando pendiente: '${pendiente.remitente}' (quedan ${colaPendientes.size})")
        LogDiag.d("Procesando pendiente: '${pendiente.remitente}' (quedan ${colaPendientes.size})")

        ciclo++
        etapaRespuesta = ETAPA_SI_NO_LEER
        intentos = 0

        remitente = pendiente.remitente
        textoParaLeer = pendiente.texto
        esNotaDeVoz = pendiente.esNotaDeVoz
        marcaMensaje = pendiente.marcaMensaje
        mensajeSimulado = pendiente.simulado
        pendingIntentResponder = pendiente.pendingIntent
        clavesRemoteInput = pendiente.clavesRemoteInput
        claveNotificacion = pendiente.claveNotificacion

        Log.d(TAG, "Pendiente (ciclo=$ciclo): remitente='$remitente' texto='${textoParaLeer.take(40)}'")

        entrarEnForeground()
        adquirirWakeLock()
        if (!activarFgMicrofono()) {
            manejarMicroNoDisponible()
            return
        }
        hacerPregunta()
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
        recognizer?.cancel()
        escuchaActiva = false
        cancelarEsperaRuta()
        generacionEscucha++

        remitente = intent.getStringExtra(EXTRA_REMITENTE) ?: ""
        textoParaLeer = intent.getStringExtra(EXTRA_TEXTO) ?: ""
        mensajeSimulado = intent.getBooleanExtra(EXTRA_SIMULADO, false)
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
        if (!activarFgMicrofono()) {
            // Sin micrófono no se puede oír la respuesta: se avisa y se termina la
            // sesión de voz (el mensaje ya se ha leído; se puede responder desde WhatsApp).
            manejarMicroNoDisponible()
            return
        }
        preguntarSiResponder()
    }

    /**
     * Gestiona la sesión cuando no hay permiso while-in-use del micrófono (ni este FGS
     * ni el micro permanente lo tienen). En lugar de arrancar un reconocedor que no
     * captaría nada (picoRMS=0 y ERROR_NO_MATCH en cadena), se avisa por voz, se
     * reintenta el micro permanente y se deja el aviso a un toque. Si estábamos en la
     * etapa "¿lo leo?" se lee el mensaje de todos modos, para no perderlo.
     */
    private fun manejarMicroNoDisponible() {
        cancelarTiempoEscucha()
        cancelarWatchdogPregunta()
        preguntaHablando = false
        recognizer?.cancel()
        escuchaActiva = false
        cancelarEsperaRuta()
        generacionEscucha++
        limpiarMicrofonoTelefono()
        val degradarALeer = etapaRespuesta == ETAPA_SI_NO_LEER && textoParaLeer.isNotBlank()
        etapaRespuesta = ETAPA_IDLE
        LogDiag.e("Sesión de voz degradada por falta de micrófono (se lee el mensaje=$degradarALeer)")
        intentarReactivarMicrofono()
        lector.hablar(getString(R.string.micro_no_disponible)) {
            if (degradarALeer) {
                lector.hablar(textoParaLeer) {
                    // Aunque no haya micrófono, una nota de voz debe sonar: es el
                    // mensaje completo y el usuario no puede leerlo de otra forma.
                    LecturaNotaVoz.reproducirSiProcede(
                        contexto = this,
                        lector = lector,
                        esNotaDeVoz = esNotaDeVoz,
                        marcaTiempoMensaje = marcaMensaje,
                        sinVentana = mensajeSimulado
                    ) {
                        finalizar()
                    }
                }
            } else {
                finalizar()
            }
        }
    }

    /** Pregunta por voz si el usuario quiere responder al remitente. */
    private fun preguntarSiResponder() {
        etapaRespuesta = ETAPA_SI_NO_RESPONDER
        // Cada etapa tiene su propio margen de reintentos: no debe heredar los que se
        // hayan gastado en "¿lo leo?".
        intentos = 0
        preguntaHablando = true
        lector.hablar(getString(R.string.pregunta_responder, remitente)) {
            if (!preguntaHablando) {
                Log.d(TAG, "onDone atrasado de la pregunta de responder; se ignora")
                return@hablar
            }
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
        // El dictado empieza con su propio margen: los reintentos de la pregunta "sí/no"
        // no deben dejar sin oportunidades al dictado.
        intentos = 0
        preguntaHablando = true
        lector.hablar(getString(R.string.pedir_dictado)) {
            if (!preguntaHablando) {
                Log.d(TAG, "onDone atrasado del prompt de dictado; se ignora")
                return@hablar
            }
            preguntaHablando = false
            cancelarWatchdogPregunta()
            Log.d(TAG, "Prompt de dictado terminado, activando micrófono")
            lector.mainHandler.postDelayed({ escucharTextoLibre() }, 350)
        }
        armarWatchdogPregunta()
    }

    /** Escucha un dictado de texto libre (respuesta a redactar). */
    private fun escucharTextoLibre() = escucharVoz("escucharTextoLibre")

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
            // Parciales activados: si el resultado final se pierde (ERROR_NO_MATCH) pero
            // el parcial ya contenía la palabra clave, se recupera en onError.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.MAX_RESULTS", 3)
            // Margen de silencio (ms) antes de dar por terminado el habla, para que
            // no descartar respuestas cortas como "sí"/"no" con un silencio breve.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    /**
     * Lee el mensaje y, después, ofrece responder.
     *
     * Si el mensaje es una nota de voz, entre la locución y la oferta de respuesta se
     * reproduce el audio de WhatsApp ([LecturaNotaVoz]); el flujo continúa igual si la
     * nota no se encuentra o la reproducción de notas está desactivada.
     */
    private fun leerMensajeYOfrecerResponder() {
        lector.hablar(textoParaLeer) {
            Log.d(TAG, "Mensaje leído tras confirmar; comprobando nota de voz")
            LecturaNotaVoz.reproducirSiProcede(
                contexto = this,
                lector = lector,
                esNotaDeVoz = esNotaDeVoz,
                marcaTiempoMensaje = marcaMensaje,
                sinVentana = mensajeSimulado
            ) {
                Log.d(TAG, "Lectura completa; ofreciendo responder")
                lanzarOfrecerResponder()
            }
        }
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
        intent.getStringExtra(EXTRA_CLAVE_NOTIFICACION)
            ?.takeIf { it.isNotBlank() }
            ?.let { claveNotificacion = it }
    }

    /**
     * Vuelve a leer el PendingIntent y las claves del RemoteInput de la notificación
     * vigente del chat.
     *
     * WhatsApp reemplaza la notificación con cada mensaje nuevo y el PendingIntent de la
     * versión anterior deja de entregar el texto **sin devolver error**: el envío parece
     * correcto pero WhatsApp lo descarta (síntoma: "lo ha informado como enviado pero no
     * se ha enviado"). Si no se puede refrescar, se conservan los datos originales.
     */
    private fun refrescarDatosRespuesta() {
        val datos = DatosRespuestaHelper.extraerVigente(this, claveNotificacion)
        if (datos == null) {
            Log.d(TAG, "Se conservan los datos de respuesta de la notificación original")
            return
        }
        pendingIntentResponder = datos.pendingIntent
        clavesRemoteInput = datos.claves
    }

    /** Pregunta por voz si quiere enviar la respuesta ya redactada o corregirla. */
    private fun preguntarEnviarOCorregir() {
        etapaRespuesta = ETAPA_CONFIRMAR_ENVIO
        // Margen propio para la confirmación: si el dictado necesitó repetirse, esos
        // reintentos no deben hacer que "enviar/corregir/cancelar" se rinda a la primera.
        intentos = 0
        preguntaHablando = true
        lector.hablar(getString(R.string.pregunta_enviar_o_corregir)) {
            if (!preguntaHablando) {
                Log.d(TAG, "onDone atrasado de la confirmación de envío; se ignora")
                return@hablar
            }
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

    /**
     * Envía la respuesta guardada mediante el RemoteInput de la notificación original.
     *
     * @param decirEnviando false cuando ya se ha avisado por voz justo antes (envío
     * rápido de respuestas cortas), para no repetir "Enviando respuesta".
     */
    private fun enviarRespuesta(decirEnviando: Boolean = true) {
        val texto = dataStore.borradorTexto ?: ""
        // Antes de enviar, usar los datos de respuesta de la notificación vigente del
        // chat: si ha llegado otro mensaje mientras hablábamos, WhatsApp ha reemplazado
        // la notificación y el PendingIntent antiguo ya no entrega el texto.
        refrescarDatosRespuesta()
        val pi = pendingIntentResponder
        if (pi == null || texto.isBlank() || clavesRemoteInput.isEmpty()) {
            Log.e(TAG, "No se puede enviar: pendingIntent=${pi != null} texto='$texto' claves=$clavesRemoteInput")
            lector.hablar(getString(R.string.pendiente_intent_no_disponible)) {
                finalizar()
            }
            return
        }
        Log.d(TAG, "Enviando respuesta a '${remitente}': '$texto'")
        val enviar = {
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
                Log.d(TAG, "Respuesta entregada a WhatsApp; verificando que se ha insertado")
                verificarEnvio(texto) { confirmado ->
                    val aviso = if (confirmado) {
                        getString(R.string.respuesta_enviada, remitente)
                    } else {
                        Log.e(TAG, "El envío no se ha podido confirmar: WhatsApp no muestra el mensaje")
                        getString(R.string.aviso_no_enviado)
                    }
                    lector.hablar(aviso) { finalizar() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando respuesta", e)
                lector.hablar(getString(R.string.aviso_no_enviado)) {
                    finalizar()
                }
            }
        }
        // En el envío rápido ya se ha dicho el texto justo antes ("Vale. Enviando."),
        // así que no se repite el aviso genérico de envío.
        if (decirEnviando) {
            lector.hablar(getString(R.string.enviando_respuesta)) { enviar() }
        } else {
            enviar()
        }
    }

    /**
     * Comprueba, poco después de enviar, que WhatsApp ha insertado el texto en la
     * conversación: la notificación vigente del chat debe tenerlo como último mensaje.
     *
     * `PendingIntent.send()` no devuelve error aunque WhatsApp descarte el resultado, así
     * que esta comprobación evita anunciar un envío que no se ha producido.
     */
    private fun verificarEnvio(texto: String, alTerminar: (Boolean) -> Unit) {
        lector.mainHandler.postDelayed({
            val vigente = WhatsAppNotificationListener.notificacionActiva(claveNotificacion)
            val confirmado = vigente != null && DatosRespuestaHelper.contieneMensajePropio(vigente, texto)
            Log.d(TAG, "Verificación del envío: confirmado=$confirmado (notificación=${vigente != null})")
            alTerminar(confirmado)
        }, ESPERA_VERIFICACION_ENVIO_MS)
    }

    /** Vuelve a escuchar el dictado para corregir la respuesta ya redactada. */
    private fun corregirRespuesta() {
        Log.d(TAG, "onResults: usuario quiere CORREGIR; borrando borrador anterior")
        dataStore.borrarBorradorRespuesta()
        etapaRespuesta = ETAPA_DICTADO
        // El nuevo dictado vuelve a tener su margen completo de reintentos.
        intentos = 0
        preguntaHablando = true
        lector.hablar(getString(R.string.corrigiendo_respuesta)) {
            if (!preguntaHablando) {
                Log.d(TAG, "onDone atrasado del aviso de corrección; se ignora")
                return@hablar
            }
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
        recognizer?.cancel()
        escuchaActiva = false
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
        escuchaActiva = false
        cancelarEsperaRuta()
        generacionEscucha++
        fgMicActivo = false
        liberarWakeLock()
        liberarPausaMusica()
        limpiarMicrofonoTelefono()
        tonoEscucha?.release()
        tonoEscucha = null
        // Si el servicio muere mientras suena una nota de voz, se libera el
        // reproductor (sin fugas de MediaPlayer ni del audio focus).
        ReproductorNotaVoz.obtener(this).detener()
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
        const val EXTRA_CLAVE_NOTIFICACION = "clave_notificacion"
        const val EXTRA_ES_NOTA_VOZ = "es_nota_de_voz"
        const val EXTRA_MARCA_MENSAJE = "marca_tiempo_mensaje"
        const val EXTRA_SIMULADO = "mensaje_simulado_para_pruebas"

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
        private const val MAX_INTENTOS = 3
        // Si en este tiempo no se oye ninguna respuesta, se trata como "no entendido".
        private const val TIEMPO_ESCUCHA_MS = 12000L
        // El dictado libre necesita más margen: una frase larga puede superar los 12 s.
        private const val TIEMPO_ESCUCHA_DICTADO_MS = 20000L
        // Tiempo máximo de espera a que el SCO quede realmente enrutado antes de
        // escuchar (medido ~1,6 s con los Pixel Buds en el Nothing Phone 3a). Si no
        // llega, se usa el micrófono del teléfono en lugar de arrancar el reconocedor
        // con la ruta a medio cambiar (que provoca ERROR_NO_MATCH).
        private const val ESPERA_RUTA_BT_MS = 3500L
        private const val INTERVALO_RUTA_BT_MS = 100L
        // Margen tras aplicar la ruta Bluetooth: el auricular abre su micrófono unos
        // milisegundos después de conmutar y se perderían las primeras sílabas. No hace
        // falta más porque el tono ya espera a que el SCO entregue voz inteligible.
        private const val MARGEN_POST_RUTA_BT_MS = 250L
        // Retraso del tono de "puedes hablar" cuando se escucha por Bluetooth. Medido con
        // los Pixel Buds: el SCO entrega voz inteligible ~2,8 s después de
        // onReadyForSpeech (antes hay ~1 s de ruido de AGC que el motor no transcribe).
        // El tono se emite al final de esa espera: quien habla al oírlo entra completo.
        private const val RETRASO_TONO_BT_MS = 3000L
        // Máximo de retención del wake lock para no dejar la CPU activa si algo se atasca.
        private const val WAKE_LOCK_TIMEOUT_MS = 90_000L
        // Tiempo máximo para que la pregunta hablada termine; si no, se activa el
        // micrófono igualmente (evita ciclos congelados por un onDone TTS perdido).
        // 12 s: una voz de red con retraso Bluetooth tarda ~6 s en frases normales; con
        // 6 s el watchdog abría el micro mientras el TTS seguía hablando y se captaba el eco.
        private const val WATCHDOG_PREGUNTA_MS = 12000L
        // Espera antes de comprobar que WhatsApp ha insertado la respuesta enviada
        // (el eco de la notificación llega en ~100 ms; se deja margen por si hay retardo).
        private const val ESPERA_VERIFICACION_ENVIO_MS = 2_500L
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
