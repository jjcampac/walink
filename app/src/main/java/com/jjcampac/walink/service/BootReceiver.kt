/*
 * Copyright 2026 jjcampac
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
