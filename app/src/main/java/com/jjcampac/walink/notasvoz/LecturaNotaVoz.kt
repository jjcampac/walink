package com.jjcampac.walink.notasvoz

import android.content.Context
import android.util.Log
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag
import com.jjcampac.walink.speech.SpeechReader

/**
 * Encadena la locución del mensaje con la reproducción de la nota de voz.
 *
 * Se usa en los tres puntos donde Walink "lee" un mensaje (lectura inmediata en el
 * listener, lectura tras confirmar en modo "preguntar" y lectura degradada cuando no
 * hay micrófono) para que el comportamiento sea idéntico en todos ellos:
 *
 *  1. Si el mensaje no es una nota de voz, se continúa sin más.
 *  2. Si el usuario desactivó la reproducción de notas de voz, se continúa sin más
 *     (el TTS ya ha dicho "una nota de voz").
 *  3. Si no se localiza el archivo, se avisa por voz y se continúa: el mensaje nunca
 *     se pierde y la sesión de respuesta sigue su curso.
 *  4. Si se localiza, se reproduce y al terminar se continúa.
 *
 * @author jjcampac
 * @author cline
 */
object LecturaNotaVoz {

    private const val TAG = "WalinkLecturaNotaVoz"

    /**
     * Reproduce la nota de voz del mensaje (si procede) y después invoca [alTerminar].
     *
     * @param lector lector TTS compartido, para poder avisar de un fallo por voz.
     * @param esNotaDeVoz true si el parser detectó una nota de voz.
     * @param marcaTiempoMensaje marca de tiempo del mensaje (para localizar el .opus).
     * @param alTerminar continuación del flujo de voz (se llama siempre, una vez).
     */
    fun reproducirSiProcede(
        contexto: Context,
        lector: SpeechReader,
        esNotaDeVoz: Boolean,
        marcaTiempoMensaje: Long,
        alTerminar: () -> Unit
    ) {
        if (!esNotaDeVoz) {
            alTerminar()
            return
        }

        val dataStore = WalinkDataStore.getInstance(contexto)
        if (!dataStore.reproducirNotasVoz) {
            Log.d(TAG, "Notas de voz desactivadas en los ajustes: se omite la reproducción")
            LogDiag.d("Nota de voz: reproducción desactivada en ajustes")
            alTerminar()
            return
        }

        val resultado = NotasVoz.localizar(contexto, marcaTiempoMensaje)
        val archivo = resultado.archivo
        if (archivo == null) {
            Log.w(TAG, "No se pudo localizar la nota de voz: ${resultado.motivo}")
            LogDiag.e("Nota de voz no localizada (${resultado.motivo})")
            lector.hablar(contexto.getString(R.string.nota_voz_no_reproducible)) {
                alTerminar()
            }
            return
        }

        Log.d(TAG, "Reproduciendo nota de voz: ${archivo.name}")
        ReproductorNotaVoz.obtener(contexto).reproducir(archivo) {
            Log.d(TAG, "Nota de voz terminada; continúa el flujo de voz")
            alTerminar()
        }
    }
}
