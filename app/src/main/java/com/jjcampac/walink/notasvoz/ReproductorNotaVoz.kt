package com.jjcampac.walink.notasvoz

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag
import java.io.File

/**
 * Reproduce los archivos `.opus` de las notas de voz de WhatsApp.
 *
 * Se mantiene una única instancia (igual que [com.jjcampac.walink.speech.SpeechReader])
 * para que dos sesiones de voz encadenadas no dejen reproductores huérfanos.
 *
 * Reglas de audio del proyecto:
 *  - Foco con `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` y `CONTENT_TYPE_SPEECH`.
 *  - `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (bajar volumen) o `AUDIOFOCUS_GAIN_TRANSIENT`
 *    (pausar), según el ajuste del usuario.
 *  - El foco se abandona al terminar la reproducción.
 *  - Retraso previo del ajuste de Bluetooth cuando la salida es el coche, para no
 *    perder el primer segundo del audio al abrirse el stream.
 *
 * @author jjcampac
 * @author cline
 */
class ReproductorNotaVoz private constructor(private val contexto: Context) {

    companion object {
        private const val TAG = "WalinkNotaVozRepro"

        @Volatile
        private var instancia: ReproductorNotaVoz? = null

        /** Instancia única compartida por el listener y el servicio de voz. */
        fun obtener(contexto: Context): ReproductorNotaVoz =
            instancia ?: synchronized(this) {
                instancia ?: ReproductorNotaVoz(contexto.applicationContext).also {
                    instancia = it
                }
            }
    }

    private val dataStore = WalinkDataStore.getInstance(contexto)
    private val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var reproductor: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var conAudioFocus = false
    private var alTerminarPendiente: (() -> Unit)? = null
    private var watchdog: Runnable? = null
    private var tareaInicio: Runnable? = null
    private var finalizado = true

    /** true mientras hay una nota de voz sonando. */
    val estaReproduciendo: Boolean
        get() = reproductor != null

    /**
     * Reproduce [archivo] y llama a [alTerminar] cuando acaba, falla o se detiene.
     * [alTerminar] se invoca **siempre** (también en los errores) para que el flujo
     * de voz no se quede colgado esperando.
     */
    fun reproducir(archivo: File, alTerminar: () -> Unit) {
        detener()

        if (!archivo.isFile) {
            LogDiag.e("Nota de voz: el archivo no existe (${archivo.name})")
            alTerminar()
            return
        }

        finalizado = false
        alTerminarPendiente = alTerminar

        val mp = MediaPlayer()
        reproductor = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(archivo.absolutePath)
            mp.setOnPreparedListener { jugador ->
                val retraso = if (salidaEsBluetooth()) dataStore.retrasoBluetoothMs else 0
                Log.d(TAG, "Nota de voz preparada: ${archivo.name} (retraso BT=$retraso ms)")
                if (retraso > 0) {
                    val inicio = Runnable { arrancar(jugador) }
                    tareaInicio = inicio
                    handler.postDelayed(inicio, retraso.toLong())
                } else {
                    arrancar(jugador)
                }
            }
            mp.setOnCompletionListener { terminar("fin de la nota") }
            mp.setOnErrorListener { _, que, extra ->
                terminar("error del reproductor ($que/$extra)")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            // SecurityException si falta el permiso de audio, IOException si el
            // archivo se ha borrado o no se puede abrir.
            Log.e(TAG, "No se pudo preparar '${archivo.name}'", e)
            LogDiag.e("Nota de voz: no se pudo abrir ${archivo.name} (${e.javaClass.simpleName})")
            terminar("no se pudo abrir el archivo")
        }
    }

    private fun arrancar(jugador: MediaPlayer) {
        tareaInicio = null
        if (finalizado) return
        solicitarAudioFocus()
        try {
            jugador.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "start() falló", e)
            terminar("no se pudo iniciar la reproducción")
            return
        }
        val duracion = try {
            jugador.duration
        } catch (e: IllegalStateException) {
            -1
        }
        LogDiag.d("Nota de voz: reproduciendo ${duracion / 1000} s")
        // Red de seguridad: si el reproductor no avisa del final (archivo dañado),
        // no dejamos el flujo de voz bloqueado esperando para siempre.
        if (duracion > 0) {
            val tarea = Runnable { terminar("tiempo máximo agotado") }
            watchdog = tarea
            handler.postDelayed(tarea, duracion.toLong() + 4000L)
        }
    }

    /** Detiene la reproducción en curso (si la hay) sin llamar al callback. */
    fun detener() {
        finalizado = true
        tareaInicio?.let { handler.removeCallbacks(it) }
        tareaInicio = null
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        reproductor?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Ya estaba parado o recién creado: no hay nada que detener.
            }
            it.release()
        }
        reproductor = null
        alTerminarPendiente = null
        liberarAudioFocus()
    }

    private fun terminar(motivo: String) {
        if (finalizado) return
        finalizado = true
        Log.d(TAG, "Nota de voz terminada ($motivo)")
        LogDiag.d("Nota de voz terminada ($motivo)")
        tareaInicio?.let { handler.removeCallbacks(it) }
        tareaInicio = null
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        reproductor?.release()
        reproductor = null
        liberarAudioFocus()
        val callback = alTerminarPendiente
        alTerminarPendiente = null
        callback?.invoke()
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
                when (cambio) {
                    // Si otra app se lleva el audio del todo, dejamos de reproducir:
                    // seguir sonando por encima sería molesto al conducir.
                    AudioManager.AUDIOFOCUS_LOSS -> terminar("pérdida de audio focus")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        Log.d(TAG, "Audio focus transitorio: $cambio (se sigue reproduciendo)")
                }
            }
            .build()
        audioFocusRequest = request
        val resultado = audioManager.requestAudioFocus(request)
        conAudioFocus = resultado == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Nota de voz: audio focus solicitado (resultado=$resultado)")
    }

    private fun liberarAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
        conAudioFocus = false
    }

    /** true si la salida actual es el Bluetooth del coche. */
    private fun salidaEsBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (dispositivo in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                when (dispositivo.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return true
                }
            }
        }
        return false
    }
}

