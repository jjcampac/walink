package com.jjcampac.walink.service

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jjcampac.walink.diag.LogDiag

/**
 * Actividad mínima, sin interfaz y transparente, que existe solo para crear una
 * **ventana elegible** con la que arrancar [MicrofonoPermanenteService] desde el
 * mosaico de accesos rápidos.
 *
 * Por qué es necesaria:
 * Android 14+ solo permite arrancar un FGS de tipo `microphone` si la app está en
 * un estado elegible (actividad visible, interacción con una notificación...). La
 * pulsación de un mosaico de ajustes rápidos **no** cuenta como tal, así que al
 * activar Walink desde ahí el arranque se rechazaba con `SecurityException` y el
 * micrófono quedaba inservible hasta tocar el aviso de reactivación.
 *
 * Abriendo esta actividad (visible aunque no se vea nada, por el tema translúcido)
 * el arranque sí es aceptado; en cuanto el servicio queda en primer plano se cierra
 * sola y el usuario vuelve a lo que estaba haciendo.
 *
 * @author jjcampac
 * @author cline
 */
class ArranqueRapidoActivity : Activity() {

    private val manejador = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogDiag.d("ArranqueRapidoActivity: ventana elegible abierta (mosaico)")
        Log.d(TAG, "Actividad de arranque rápido creada")
    }

    override fun onResume() {
        super.onResume()
        // Arrancamos el micro permanente ya con la actividad visible: es la ventana
        // elegible que Android exige para conceder el tipo "microphone".
        MicrofonoPermanenteService.iniciar(this)
        // El cierre se difiere unos instantes: el servicio necesita llamar a
        // startForeground mientras la app sigue siendo elegible. Cerrar la actividad
        // de inmediato podría hacer que el sistema rechazase el arranque.
        manejador.postDelayed({ finish() }, RETARDO_CIERRE_MS)
    }

    override fun onDestroy() {
        manejador.removeCallbacksAndMessages(null)
        Log.d(TAG, "Actividad de arranque rápido destruida")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalinkArranqueRapido"
        private const val RETARDO_CIERRE_MS = 700L
    }
}