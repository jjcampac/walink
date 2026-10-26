package com.jjcampac.walink.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.jjcampac.walink.data.WalinkDataStore
import android.os.Looper
import java.util.Locale

class SpeechReader(private val contexto: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "WalinkTTS"
        private const val USO_NAVEGACION = 14
    }

    private val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val dataStore = WalinkDataStore.getInstance(contexto.applicationContext)

    private var tts: TextToSpeech? = null
    var inicializado = false

    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private val USE_AUDIO_FOCUS_REQUEST: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var liberandoFocus = false

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(contexto, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            aplicarVozGuardada()
            tts?.setSpeechRate(1.0f)
            inicializado = true
            Log.d(TAG, "TextToSpeech inicializado")
        } else {
            Log.e(TAG, "Error inicializando TextToSpeech: status=$status")
        }
    }

    fun aplicarVozGuardada() {
        val tts = tts ?: return
        val nombreVoz = dataStore.vozTts

        if (!nombreVoz.isNullOrBlank()) {
            val voz = tts.voices?.firstOrNull { it.name == nombreVoz }
            if (voz != null && tts.setVoice(voz) == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Voz aplicada: ${voz.name} (${voz.locale})")
                return
            }
            Log.w(TAG, "Voz guardada no disponible ($nombreVoz), usando español por defecto")
        }

        val resultado = tts.setLanguage(Locale("es", "ES"))
        if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Idioma español no soportado, usando inglés por defecto")
            tts.setLanguage(Locale.ENGLISH)
        }
    }

    fun aplicarVozGuardadaYInicializar() {
        aplicarVozGuardada()
        inicializado = true
        Log.d(TAG, "Voz aplicada y TTS marcado como inicializado")
    }

    private fun liberarAudioFocus() {
        val listener = audioFocusListener ?: return
        audioFocusListener = null
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(listener)
        Log.d(TAG, "Audio focus liberado")
    }

    private fun solicitarAudioFocus() {
        if (audioFocusListener != null) {
            return
        }

        val listener = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(change: Int) {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        mainHandler.post {
                            tts?.stop()
                        }
                    }
                }
            }
        }

        val resultado = audioManager.requestAudioFocus(
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )

        if (resultado == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusListener = listener
            Log.d(TAG, "Audio focus concedido (ducking)")
        } else {
            Log.w(TAG, "Audio focus DENEGADO (resultado=$resultado)")
        }
    }

    fun apagar() {
        liberarAudioFocus()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        inicializado = false
    }

    fun hablar(texto: String, onFinished: (() -> Unit)? = null): Boolean {
        if (!inicializado) {
            Log.w(TAG, "TTS no inicializado; intentando aplicar voz")
            aplicarVozGuardadaYInicializar()
        }

        if (tts == null) {
            Log.e(TAG, "TTS es null, no se puede hablar")
            return false
        }

        tts?.stop()
        registrarCallbackFinDeVoz(onFinished)
        solicitarAudioFocus()

        @Suppress("DEPRECATION")
        val ok = tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "") == TextToSpeech.SUCCESS

        Log.d(TAG, "Intentando hablar: '${texto.take(20)}', ok=$ok")
        return ok
    }

    private fun registrarCallbackFinDeVoz(onFinished: (() -> Unit)? = null) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    liberarAudioFocus()
                    onFinished?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    liberarAudioFocus()
                    onFinished?.invoke()
                }
            }
        })
    }
}