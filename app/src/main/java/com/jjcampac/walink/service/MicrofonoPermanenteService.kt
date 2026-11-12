package com.jjcampac.walink.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * Walink esté activado. Se arranca en cuanto hay una ventana elegible (abrir la app,
 * pulsar el tile de activación o tocar el aviso de reactivación) y se detiene al
 * desactivar Walink.
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // El usuario retira la app de recientes: NO detenemos el servicio. Este FGS es
        // justo lo que mantiene concedido el permiso while-in-use del micrófono; si se
        // parase aquí, el siguiente mensaje volvería a fallar con picoRMS=0.
        LogDiag.d("MicrofonoPermanenteService: app retirada de recientes; el servicio sigue en marcha")
        super.onTaskRemoved(rootIntent)
    }

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
                    // Sin elegibilidad (p. ej. tras actualizar la app o reiniciar el
                    // teléfono: desde Android 15 un BOOT_COMPLETED ya no puede arrancar
                    // un FGS de tipo "microphone") no podemos mantener el permiso
                    // while-in-use del micro. Antes se paraba en silencio y el fallo
                    // solo se descubría al intentar escuchar (SecurityException +
                    // picoRMS=0 + ERROR_NO_MATCH). Ahora se deja un aviso con acción:
                    // al tocar una notificación la app entra en las exenciones y el
                    // arranque de tipo "microphone" sí es aceptado.
                    avisarReactivacion(this)
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
            // Si había un aviso de "reactiva el micrófono" pendiente, ya no hace falta.
            cancelarAviso(this)
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
        .setContentTitle(getString(R.string.notif_micro_titulo))
        .setContentText(getString(R.string.notif_micro_desc))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /** Crea el canal de notificación del asistente (idempotente; mismo canal que el LectorAsistenteService). */
    private fun crearCanal() = asegurarCanal(this)

    companion object {
        private const val TAG = "WalinkMicPermanente"
        private const val NOTIF_ID = 41
        // Aviso accionable que se muestra cuando el FGS de micrófono no puede arrancar.
        private const val NOTIF_ID_AVISO = 42

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
         * un contexto elegible: MainActivity en primer plano, pulsación del tile o
         * toque sobre una notificación de la app.
         */
        fun iniciar(contexto: Context) {
            try {
                ContextCompat.startForegroundService(
                    contexto,
                    Intent(contexto, MicrofonoPermanenteService::class.java)
                        .setAction(ACTION_INICIAR)
                )
                LogDiag.d("MicrofonoPermanenteService: arranque solicitado")
            } catch (t: Throwable) {
                // Android 12+ puede rechazar el arranque en segundo plano: no es un
                // fallo fatal (se reintentará al abrir la app o tocar el aviso).
                LogDiag.e("MicrofonoPermanenteService: no se pudo solicitar el arranque", t)
            }
        }

        /** Detiene el servicio de micro permanente cuando Walink se desactiva. */
        fun detener(contexto: Context) {
            contexto.stopService(Intent(contexto, MicrofonoPermanenteService::class.java))
            cancelarAviso(contexto)
            LogDiag.d("MicrofonoPermanenteService: solicitada su detención")
        }

        /**
         * Publica un aviso con acción para reactivar el micrófono con un solo toque.
         *
         * Es la única recuperación que Android permite sin abrir la app: cuando el
         * usuario toca una notificación de la app, esta entra en las exenciones y el
         * sistema sí acepta arrancar un FGS de tipo "microphone" desde segundo plano
         * (exención "the service starts by interacting with a notification").
         *
         * Sin este aviso, la pérdida del permiso while-in-use pasaba totalmente
         * inadvertida: Walink leía el mensaje, preguntaba "¿lo leo?" y luego no oía
         * nada (picoRMS=0, ERROR_NO_MATCH) sin explicar el motivo.
         */
        fun avisarReactivacion(contexto: Context) {
            try {
                asegurarCanal(contexto)
                val pi = PendingIntent.getForegroundService(
                    contexto,
                    0,
                    Intent(contexto, MicrofonoPermanenteService::class.java)
                        .setAction(ACTION_INICIAR),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val aviso = NotificationCompat.Builder(contexto, LectorAsistenteService.CANAL)
                    .setSmallIcon(R.drawable.ic_tile_walink)
                    .setContentTitle(contexto.getString(R.string.mic_reactivar_titulo))
                    .setContentText(contexto.getString(R.string.mic_reactivar_desc))
                    .setContentIntent(pi)
                    .addAction(R.drawable.ic_tile_walink, contexto.getString(R.string.mic_reactivar_accion), pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                contexto.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID_AVISO, aviso)
                LogDiag.d("MicrofonoPermanenteService: publicado aviso para reactivar el micrófono")
            } catch (t: Throwable) {
                // Sin POST_NOTIFICATIONS (o con el canal bloqueado) no se puede avisar:
                // queda el registro de diagnóstico y la apertura de la app.
                LogDiag.e("MicrofonoPermanenteService: no se pudo publicar el aviso de reactivación", t)
            }
        }

        /** Retira el aviso de reactivación (ya no hace falta). */
        fun cancelarAviso(contexto: Context) {
            try {
                contexto.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_AVISO)
                Log.d(TAG, "Aviso de reactivación retirado")
            } catch (t: Throwable) {
                LogDiag.e("MicrofonoPermanenteService: no se pudo retirar el aviso de reactivación", t)
            }
        }

        /** Crea el canal del asistente de forma idempotente. */
        private fun asegurarCanal(contexto: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val canal = NotificationChannel(
                    LectorAsistenteService.CANAL,
                    contexto.getString(R.string.canal_asistente_titulo),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = contexto.getString(R.string.canal_asistente_desc) }
                contexto.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
            } catch (t: Throwable) {
                LogDiag.e("MicrofonoPermanenteService: no se pudo crear el canal", t)
            }
        }
    }
}
