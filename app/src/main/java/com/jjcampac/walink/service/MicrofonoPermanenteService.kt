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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jjcampac.walink.R
import com.jjcampac.walink.diag.LogDiag
import com.jjcampac.walink.speech.LectorAsistenteService

/**
 * Servicio en primer plano de tipo "microphone" que se mantiene en marcha mientras
 * Walink esté activado. Se arranca en cuanto hay una ventana elegible (abrir la app
 * o pulsar el tile de activación) y se detiene al desactivar Walink.
 *
 * Por qué es necesario:
 * Android 14+ (API 34) solo concede el permiso foreground-only RECORD_AUDIO
 * ("while-in-use") a un FGS cuando la app es "elegible" (pantalla encendida, app en
 * primer plano o, a lo sumo, la ventana de la notificación). Esa ventana NO existe
 * cuando un mensaje real de WhatsApp llega con la pantalla apagada: el intento de
 * conmutar el FGS de "specialUse" a "microphone" en ese momento falla con
 * SecurityException y el SpeechRecognizer se queda sin audio (picoRMS=0 y
 * ERROR_NO_MATCH), aunque la app sí lea el mensaje en voz alta.
 *
 * Manteniendo este FGS ya activo como "microphone" desde antes de que llegue el
 * mensaje, el permiso while-in-use del micrófono permanece concedido para la app
 * mientras el servicio esté en marcha (sin stopForeground), por lo que el
 * SpeechRecognizer puede captar la voz del usuario con la pantalla apagada.
 *
 * @author jjcampac
 * @author cline
 */
class MicrofonoPermanenteService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Si el sistema mata el servicio o Walink se desactiva, el permiso
        // while-in-use del micro deja de estar garantizado hasta el próximo éxito.
        estaActivo = false
        LogDiag.d("MicrofonoPermanenteService destruido; micro while-in-use ya no garantizado")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogDiag.iniciar(this)
        LogDiag.d("MicrofonoPermanenteService: onStartCommand (acción=${intent?.action})")
        Log.d(TAG, "MicrofonoPermanenteService onStartCommand (acción=${intent?.action})")

        when (intent?.action) {
            ACTION_DETENER -> {
                LogDiag.d("MicrofonoPermanenteService: detenido (Walink desactivado)")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!arrancarEnPrimerPlanoMicrofono()) {
                    // Sin elegibilidad (p. ej. Android nos recrea desde background con
                    // START_STICKY tras un kill): no podemos mantener el permiso del
                    // micro. Nos detenemos y se reintentará en la próxima ventana
                    // elegible (abrir la app o pulsar el tile).
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * Sube el servicio a primer plano como tipo "microphone". Devuelve false si
     * Android lo rechaza por no ser elegible para el permiso while-in-use.
     */
    private fun arrancarEnPrimerPlanoMicrofono(): Boolean {
        crearCanal()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIF_ID,
                    notificacionActiva(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notificacionActiva())
            }
            LogDiag.d("MicrofonoPermanenteService ACTIVO (tipo microphone); micro while-in-use disponible")
            Log.d(TAG, "MicrofonoPermanenteService en primer plano como tipo microphone")
            estaActivo = true
            true
        } catch (e: SecurityException) {
            estaActivo = false
            LogDiag.e("MicrofonoPermanenteService: FGS microphone rechazado (no elegible); se detiene", e)
            Log.w(TAG, "No se pudo arrancar el FGS de tipo microphone (no elegible)", e)
            false
        }
    }

    private fun notificacionActiva() = NotificationCompat.Builder(this, LectorAsistenteService.CANAL)
        .setSmallIcon(R.drawable.ic_tile_walink)
        .setContentTitle(getString(R.string.notif_escuchando_titulo))
        .setContentText(getString(R.string.notif_escuchando_desc))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /** Crea el canal de notificación del asistente (idempotente; mismo canal que el LectorAsistenteService). */
    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                LectorAsistenteService.CANAL,
                getString(R.string.canal_asistente_titulo),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.canal_asistente_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    companion object {
        private const val TAG = "WalinkMicPermanente"
        private const val NOTIF_ID = 41

        // True desde que el servicio consigue arrancar como FGS de tipo "microphone"
        // hasta que se destruye o falla. Lo consulta LectorAsistenteService para saber
        // si un fallo de conmutación de su propio FGS es esperado (el micro ya lo
        // cubre este servicio) o un fallo real.
        @Volatile
        var estaActivo = false
        private set

        const val ACTION_INICIAR = "com.jjcampac.walink.action.INICIAR_MICRO_PERMANENTE"
        const val ACTION_DETENER = "com.jjcampac.walink.action.DETENER_MICRO_PERMANENTE"

        /**
         * Arranca (o reafirma) el servicio de micro permanente. Debe llamarse desde
         * un contexto elegible: MainActivity en primer plano o pulsación del tile.
         */
        fun iniciar(contexto: Context) {
            ContextCompat.startForegroundService(
                contexto,
                Intent(contexto, MicrofonoPermanenteService::class.java)
                    .setAction(ACTION_INICIAR)
            )
        }

        /** Detiene el servicio de micro permanente cuando Walink se desactiva. */
        fun detener(contexto: Context) {
            contexto.stopService(Intent(contexto, MicrofonoPermanenteService::class.java))
            LogDiag.d("MicrofonoPermanenteService: solicitada su detención")
        }
    }
}
