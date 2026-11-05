package com.jjcampac.walink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag

/**
 * Reintenta arrancar el [MicrofonoPermanenteService] cuando el teléfono se enciende
 * (y, de paso, cuando la app se reinstala/actualiza), si Walink estaba activado.
 *
 * El arranque real solo tendrá éxito si en ese momento hay una ventana elegible
 * (pantalla encendida, lo habitual justo tras el encendido del móvil). Si Android lo
 * rechaza por estar la app en segundo plano con pantalla apagada, el servicio se
 * detiene solo y el micro quedará cubierto la primera vez que se abra la app o se
 * toque el tile. Es por tanto un refuerzo, no una garantía, para que el permiso
 * while-in-use del micrófono se recupere automáticamente tras un reinicio.
 *
 * @author jjcampac
 * @author cline
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accion = intent.action
        LogDiag.iniciar(context)
        if (accion != Intent.ACTION_BOOT_COMPLETED && accion != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val dataStore = WalinkDataStore.getInstance(context.applicationContext)
        if (!dataStore.estaActivado) {
            LogDiag.d("BootReceiver: $accion recibido pero Walink no está activado; no se arranca el micro permanente")
            Log.d(TAG, "$accion recibido, Walink desactivado: no se arranca el micro permanente")
            return
        }

        LogDiag.d("BootReceiver: $accion recibido con Walink activado; arrancando micro permanente")
        Log.d(TAG, "$accion recibido: arrancando micro permanente")
        MicrofonoPermanenteService.iniciar(context)
    }

    companion object {
        private const val TAG = "WalinkBootReceiver"
    }
}
