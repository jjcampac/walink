package com.jjcampac.walink.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Utilidad de SIMULACIÓN para buildType DEBUG.
 *
 * Publica una notificación que imita a WhatsApp (título = remitente, texto =
 * mensaje, categoría MESSAGE, MessagingStyle y acción "Responder" con RemoteInput).
 * A partir de ahí, la lectura la gestiona [com.jjcampac.walink.service.WhatsAppNotificationListener]
 * igual que con un mensaje real, de modo que en debug el flujo es idéntico al de
 * producción (un único camino de lectura, sin lecturas duplicadas).
 *
 * En release este código se compila pero nunca se ejecuta: solo se llama desde
 * MainActivity cuando BuildConfig.DEBUG es true, y el receiver [DebugReplyReceiver]
 * solo se registra en el manifest de debug.
 *
 * @author jjcampac
 * @author cline
 */
object DebugSimulator {

    const val TAG = "WalinkDebug"
    const val CHANNEL_ID = "walink_debug_simulacion"
    private const val NOTIFICATION_ID_BASE = 9001

    const val ACTION_RESPONDER = "com.jjcampac.walink.debug.ACTION_RESPONDER"
    const val EXTRA_CONVERSATION = "conversacion_simulada"
    const val CLAVE_REMOTE_INPUT = "respuesta_texto"

    private var contadorNotificaciones = 0

    /**
     * Publica la notificación simulada de WhatsApp.
     *
     * El modo de lectura ya no se usa aquí: lo decide el listener leyendo la
     * configuración, igual que con WhatsApp. Se conserva el parámetro por
     * compatibilidad con el llamador (MainActivity).
     */
    @Suppress("UNUSED_PARAMETER")
    fun publicarMensajeSimulado(contexto: Context, remitente: String, texto: String, modoLectura: Boolean) {
        crearCanalSiNecesario(contexto)
        // Cada pulsación publica una notificación con ID propio: reutilizar el mismo
        // ID hacía que la segunda simulación se tratara como una actualización de la
        // anterior y, en algunos dispositivos, no volviera a disparar onNotificationPosted
        // (la segunda simulación se quedaba muda).
        val idNotificacion = NOTIFICATION_ID_BASE + (contadorNotificaciones++ % 1000)
        Log.d(TAG, "Simulando mensaje de '$remitente': '$texto' (id=$idNotificacion, modo solicitado en UI: $modoLectura)")

        val intentAbrir = contexto.packageManager.getLaunchIntentForPackage(contexto.packageName)
        val pendingAbrir = PendingIntent.getActivity(
            contexto,
            0,
            intentAbrir,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intentResponder = Intent(contexto, DebugReplyReceiver::class.java).apply {
            action = ACTION_RESPONDER
            putExtra(EXTRA_CONVERSATION, remitente)
        }
        val pendingResponder = PendingIntent.getBroadcast(
            contexto,
            1,
            intentResponder,
            PendingIntent.FLAG_MUTABLE
        )

        val remoteInput = RemoteInput.Builder(CLAVE_REMOTE_INPUT)
            .setLabel("Responder")
            .build()

        val accionResponder = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Responder",
            pendingResponder
        )
            .addRemoteInput(remoteInput)
            .build()

        val estilo = NotificationCompat.MessagingStyle(remitente)
            .addMessage(texto, System.currentTimeMillis(), remitente)

        val builder = NotificationCompat.Builder(contexto, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(remitente)
            .setContentText(texto)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingAbrir)
            .setAutoCancel(true)
            .addAction(accionResponder)
            .setStyle(estilo)

        try {
            if (NotificationManagerCompat.from(contexto).areNotificationsEnabled()) {
                NotificationManagerCompat.from(contexto).notify(idNotificacion, builder.build())
                Log.d(TAG, "Notificación simulada publicada (id=$idNotificacion). La lectura la gestiona el listener.")
            } else {
                Log.w(TAG, "Permiso de notificaciones no concedido. areNotificationsEnabled=false")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val concedido = contexto.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    Log.w(TAG, "POST_NOTIFICATIONS concedido: $concedido")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error publicando notificación simulada", e)
        }
    }

    private fun crearCanalSiNecesario(contexto: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Simulación de mensajes (debug)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de prueba para depurar Walink"
            }
            contexto.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }
}

/**
 * Recibe la respuesta escrita/dictada desde el RemoteInput de la notificación
 * simulada y la muestra en Logcat (no se envía a WhatsApp). Solo se registra en
 * buildType debug (ver src/debug/AndroidManifest.xml).
 *
 * @author jjcampac
 * @author cline
 */
class DebugReplyReceiver : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intent: Intent) {
        val remitente = intent.getStringExtra(DebugSimulator.EXTRA_CONVERSATION) ?: "desconocido"
        val textoRespuesta = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(DebugSimulator.CLAVE_REMOTE_INPUT)
            ?.toString()
            ?: "(vacío)"
        Log.d(TAG, "RESPUESTA RECIBIDA (modo depuración): responder a '$remitente' con: '$textoRespuesta'")
    }

    private companion object {
        const val TAG = "WalinkDebug"
    }
}
