package com.jjcampac.walink.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.idioma.IdiomaHelper
import java.util.Locale

/**
 * Lee texto en voz alta con el motor TextToSpeech. Es el único dueño del TTS
 * de Walink (el [LectorAsistenteService] delega en él toda la locución), lo que
 * evita que dos motores hablen a la vez.
 *
 * Responsabilidades:
 *  - Gestionar el ciclo de vida del motor TTS (sin fugas).
 *  - Gestión del audio focus según las reglas del proyecto:
 *      * AudioFocusRequest con USAGE_ASSISTANCE_NAVIGATION_GUIDANCE y
 *        CONTENT_TYPE_SPEECH.
 *      * Si el ajuste es "bajar volumen" (por defecto): se usa
 *        AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK para que el sistema atenúe a las
 *        demás apps, manteniendo nuestro TTS a su volumen normal.
 *      * Si el ajuste es "pausar": se usa AUDIOFOCUS_GAIN_TRANSIENT para que el
 *        sistema pause la reproducción de las demás apps.
 *  - Retraso inicial de Bluetooth (silencio, por defecto 800 ms) solo cuando la
 *    salida de audio es Bluetooth, para que no se corte el principio de la frase.
 *
 * El foco se solicita antes de hablar y se abandona únicamente al terminar.
 *
 * @author jjcampac
 * @author cline
 */
class SpeechReader private constructor(private val contexto: Context) : TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        private var instancia: SpeechReader? = null

        /**
         * Devuelve la instancia ÚNICA compartida del lector de voz. Se comparte entre
         * el listener (leer inmediatamente) y el servicio (preguntar antes) para no
         * crear/destruir el motor TTS en cada ciclo, lo que provocaba que tras la
         * primera lectura los siguientes intentos se quedaran mudos.
         */
        fun obtener(contexto: Context): SpeechReader {
            return instancia ?: synchronized(this) {
                instancia ?: SpeechReader(contexto.applicationContext).also { instancia = it }
            }
        }

        private const val TAG = "WalinkTTS"
        private const val TIEMPO_ESPERA_INIT = 10000L
    }

    private val dataStore = WalinkDataStore.getInstance(contexto.applicationContext)
    private val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var inicializado = false
        private set

    @Volatile
    var apagado = false
        private set

    private var tts: TextToSpeech? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var conAudioFocus = false
    private var alTerminarPendiente: (() -> Unit)? = null
    private var tareaEmitir: Runnable? = null
    private var contadorUtterance = 0L

    val mainHandler: Handler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(contexto, this)
    }

    override fun onInit(status: Int) {
        if (apagado) return
        if (status == TextToSpeech.SUCCESS) {
            // Avisamos al motor de que estas emisiones son guía de navegación/habla,
            // así las prioriza (no las trata como música de fondo "duckeable").
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            aplicarVozGuardada()
            tts?.setSpeechRate(dataStore.velocidadVoz)
            inicializado = true
            Log.d(TAG, "TextToSpeech inicializado")
        } else {
            Log.e(TAG, "Error inicializando TextToSpeech: status=$status")
        }
    }

    /** Actualiza la velocidad de voz aplicada al motor TTS. */
    fun actualizarVelocidad(velocidad: Float) {
        tts?.setSpeechRate(velocidad)
    }

    /** Aplica la voz: respeta la guardada si es local; si es de red, prefiere una local. */
    private fun aplicarVozGuardada() {
        val tts = tts ?: return
        val voces = tts.voices ?: emptyList()
        val localeApp = IdiomaHelper.localeResuelto(contexto)
        val vozLocal = preferirVozLocal(tts, localeApp)

        val nombreVoz = dataStore.vozTts
        if (!nombreVoz.isNullOrBlank()) {
            val vozGuardada = voces.firstOrNull { it.name == nombreVoz }
            if (vozGuardada != null && !vozGuardada.isNetworkConnectionRequired) {
                // Voz guardada LOCAL: la respetamos tal cual.
                if (tts.setVoice(vozGuardada) == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Voz aplicada (guardada local): ${vozGuardada.name}")
                    return
                }
            } else if (vozGuardada != null && vozGuardada.isNetworkConnectionRequired) {
                // Voz guardada de RED: puede cortarse al sintetizar. Preferimos una local.
                Log.w(TAG, "La voz guardada '${vozGuardada.name}' es de red (puede cortarse)")
                if (vozLocal != null && tts.setVoice(vozLocal) == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Usando voz local en su lugar: ${vozLocal.name}")
                    return
                }
                if (tts.setVoice(vozGuardada) == TextToSpeech.SUCCESS) {
                    Log.d(TAG, "Voz de red aplicada (no hay local): ${vozGuardada.name}")
                    return
                }
            } else {
                Log.w(TAG, "Voz guardada no disponible, usando voz local del idioma elegido")
            }
        }

        // Sin voz guardada usable: preferir una voz LOCAL del idioma de la app.
        // Las voces locales suenan normalmente más fuertes, claras y sin cortes que las de red.
        if (vozLocal != null) {
            if (tts.setVoice(vozLocal) == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Voz local seleccionada: ${vozLocal.name}")
                return
            }
        }
        val resultado = tts.setLanguage(localeApp)
        if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Idioma de la app no soportado por el TTS, usando inglés")
            tts.setLanguage(Locale.ENGLISH)
        }
    }

    /**
     * Devuelve la mejor voz del idioma de la app disponible en el motor, prefiriendo
     * las locales (offline) sobre las de red. null si no hay ninguna usable.
     */
    private fun preferirVozLocal(tts: TextToSpeech, localeApp: Locale): Voice? {
        val voces = tts.voices ?: return null
        val delIdioma = voces.filter { it.locale?.language.equals(localeApp.language, ignoreCase = true) }
        if (delIdioma.isEmpty()) return null
        return delIdioma.firstOrNull { !it.isNetworkConnectionRequired } ?: delIdioma.firstOrNull()
    }

    /**
     * Lee [texto] en voz alta. Al terminar o fallar se invoca [alTerminar].
     * Solo se habla de uno en uno; una nueva llamada corta la lectura anterior.
     */
    fun hablar(texto: String, alTerminar: (() -> Unit)? = null) {
        if (tts == null || apagado) {
            Log.w(TAG, "TTS no disponible, no se puede hablar")
            alTerminar?.invoke()
            return
        }
        // Se espera a que el motor esté totalmente inicializado y verificado
        // antes de intentar hablar. Esto evita que, tras una lectura previa, el
        // siguiente mensaje se quede en silencio por un motor o estado no listo.
        // La puerta no depende de conAudioFocus: la petición de foco se hace
        // justo antes del speak en pronunciar(), evitando que un state anterior
        // bloquee vueltas sucesivas.
        esperarInicializado { listo ->
            if (listo) {
                pronunciar(texto, alTerminar)
            } else {
                Log.e(TAG, "TTS no se inicializó en $TIEMPO_ESPERA_INIT ms; no se puede leer")
                Log.w(TAG, "Bloqueo: tts=${tts != null}, apagado=$apagado, inicializado=$inicializado")
                alTerminar?.invoke()
            }
        }
    }

    private fun pronunciar(texto: String, alTerminar: (() -> Unit)? = null) {
        if (tts == null || apagado) {
            Log.w(TAG, "TTS no disponible en pronunciar, no se puede leer")
            alTerminar?.invoke()
            return
        }
        // Descartamos cualquier locución pendiente anterior (un onDone atrasado,
        // un emit retrasado por Bluetooth o un callback sin consumir) para que un
        // ciclo previo no se mezcle con la lectura actual: era la causa de que la
        // segunda simulación se quedara muda o aparecieran preguntas fantasma.
        descartarLocucionPendiente()
        alTerminarPendiente = alTerminar

        val retrasoBL = if (salidaEsBluetooth()) dataStore.retrasoBluetoothMs else 0

        // El foco se solicita justo antes del speak, no al entrar en la llamada.
        // Si en una lectura posterior el foco no se concedió, lo volvemos a pedir
        // antes de emitir, y no dejamos que un estado anterior en silencio.
        solicitarAudioFocus()

        // ID único por locución: el motor TTS encadena por utteranceId, y reutilizar
        // el mismo ID entre ciclos hacía que onDone de una lectura llegara al listener
        // de otra (callbacks fantasma que detenían la lectura del siguiente mensaje).
        val idUtterance = "walink_${++contadorUtterance}"
        registrarProgreso(idUtterance)

        val motor = tts ?: run {
            Log.w(TAG, "TTS no disponible en pronunciar, no se puede leer")
            alTerminar?.invoke()
            return
        }

        val emitir = Runnable {
            val ok = motor.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle(), idUtterance)
            Log.d(TAG, "hablar(\"${texto.take(40)}\") ok=$ok retrasoBL=$retrasoBL id=$idUtterance")
            tareaEmitir = null
        }
        tareaEmitir = emitir

        // Retraso previo de Bluetooth mediante silencio (solo cuando la salida es BT),
        // para que no se corte el inicio de la frase al arrancar el stream del coche.
        if (retrasoBL > 0) {
            mainHandler.postDelayed(emitir, retrasoBL.toLong())
        } else {
            emitir.run()
        }
    }

    /**
     * Descarta una locución que todavía no ha emitido o cuyo callback de fin no se
     * ha consumido. Evita que un ciclo anterior contamine al siguiente (silencios y
     * preguntas fantasma al encadenar notificaciones en modo "preguntar antes").
     */
    fun descartarLocucionPendiente() {
        tareaEmitir?.let { mainHandler.removeCallbacks(it) }
        tareaEmitir = null
        alTerminarPendiente = null
    }

    private fun registrarProgreso(utteranceIdActual: String) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Solo finalizamos cuando termina la locución REAL, no el silencio.
                if (utteranceId == utteranceIdActual) {
                    mainHandler.post { terminarLocucion() }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == utteranceIdActual) {
                    mainHandler.post { terminarLocucion() }
                }
            }

            @Deprecated("Deprecado en API 21")
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == utteranceIdActual) {
                    mainHandler.post { terminarLocucion() }
                }
            }
        })
    }

    private fun terminarLocucion() {
        liberarAudioFocus()
        val cb = alTerminarPendiente
        alTerminarPendiente = null
        cb?.invoke()
    }

    private fun solicitarAudioFocus() {
        if (conAudioFocus) return
        val atenuar = dataStore.bajarVolumenEnLugarDePausar
        val tipoFocus = if (atenuar) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(tipoFocus)
            .setAudioAttributes(atributos)
            .setOnAudioFocusChangeListener { cambio ->
                // IMPORTANTE: no detenemos el TTS al perder foco. El motor TTS gestiona
                // su propio audio focus, así que al empezar a hablar nos puede llegar
                // LOSS/LOSS_TRANSIENT; si paráramos aquí, se cortaría la frase y se
                // generaría un vaivén de paradas/reanudaciones (ruidos de golpes).
                when (cambio) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        Log.d(TAG, "Audio focus cambiado: $cambio (no detenemos el TTS)")
                }
            }
            .build()
        audioFocusRequest = request
        val res = audioManager.requestAudioFocus(request)
        conAudioFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus solicitado (resultado=$res, modo=${if (atenuar) "atenuar" else "pausar"})")
    }

    private fun liberarAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
        conAudioFocus = false
        Log.d(TAG, "Audio focus liberado")
    }

    private fun salidaEsBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (d in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return true
                }
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
        return false
    }

    private fun esperarInicializado(alListo: (Boolean) -> Unit) {
        if (inicializado) {
            alListo(true)
            return
        }
        var intentos = 0
        val maxIntentos = (TIEMPO_ESPERA_INIT / 150).coerceAtLeast(1)
        val check = object : Runnable {
            override fun run() {
                if (inicializado) {
                    alListo(true)
                } else if (intentos < maxIntentos) {
                    intentos++
                    mainHandler.postDelayed(this, 150)
                } else {
                    alListo(false)
                }
            }
        }
        mainHandler.post(check)
    }

    /** Libera todos los recursos. Debe llamarse en onDestroy de su dueño. */
    fun apagar() {
        apagado = true
        descartarLocucionPendiente()
        liberarAudioFocus()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        inicializado = false
        // El handler de hilo principal se conserva: no se destruye, por lo que
        // las labores pendientes (retrasos de BT, finalización de locución)
        // no se ejecutan sobre un objeto muerto.
        Log.d(TAG, "SpeechReader apagado")
    }
}
