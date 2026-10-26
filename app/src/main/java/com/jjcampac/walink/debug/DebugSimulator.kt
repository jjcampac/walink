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
 * Publica una notificación que imita a WhatsApp (título = remitente, texto = mensaje,
 * categoría MESSAGE, MessagingStyle y acción "Responder" con RemoteInput).
 *
 * En release este código se compila pero nunca se ejecuta: solo se llama desde
 * MainActivity cuando BuildConfig.DEBUG es true, y el receiver [DebugReplyReceiver]
 * solo se registra en el manifest de debug.
 *
 * Al responder, [DebugReplyReceiver] captura el texto y lo muestra en Logcat
 * (en lugar de enviarlo a WhatsApp).
 */
object DebugSimulator {

    private const val TAG = "WalinkDebug"
    private const val CHANNEL_ID = "walink_debug_simulacion"
    private const val NOTIFICATION_ID = 9001

    /** Acción usada por el RemoteInput para recibir la respuesta (solo debug). */
    const val ACTION_RESPONDER = "com.jjcampac.walink.debug.ACTION_RESPONDER"

    /** Extra que transporta el nombre de la conversación (remitente). */
    const val EXTRA_CONVERSATION = "conversacion_simulada"

    /** Clave usada en el RemoteInput para recibir el texto de la respuesta. */
    const val CLAVE_REMOTE_INPUT = "respuesta_texto"

    /**
     * Publica la notificación simulada de WhatsApp.
     *
     * @param contexto Contexto de la app.
     * @param remitente Nombre del contacto/grupo que "envía" el mensaje.
     * @param texto Cuerpo del mensaje de prueba.
     */
    fun publicarMensajeSimulado(contexto: Context, remitente: String, texto: String) {
        crearCanalSiNecesario(contexto)

        // Intent que lanza la MainActivity al tocar la notificación.
        val intentAbrir = contexto.packageManager.getLaunchIntentForPackage(contexto.packageName)
        val pendingAbrir = PendingIntent.getActivity(
            contexto,
            0,
            intentAbrir,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Intent que maneja la acción "Responder" (RemoteInput).
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

        // RemoteInput para dictar/escribir la respuesta.
        val remoteInput = RemoteInput.Builder(CLAVE_REMOTE_INPUT)
            .setLabel("Responder")
            .build()

        // Acción "Responder" que se muestra en la notificación.
        val accionResponder = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Responder",
            pendingResponder
        ).addRemoteInput(remoteInput).build()

        // MessagingStyle: el constructor público con CharSequence está deprecado pero
        // es la única API accesible públicamente (el constructor con Message es
        // package-private). Usamos @Suppress para indicar que es intencional.
        @Suppress("DEPRECATION")
        val estilo = NotificationCompat.MessagingStyle(remitente)
            .setConversationTitle(remitente)
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
                NotificationManagerCompat.from(contexto).notify(NOTIFICATION_ID, builder.build())
                Log.d(TAG, "Notificación simulada publicada: '$remitente' -> '$texto'")
            } else {
                Log.w(TAG, "Permiso de notificaciones no concedido. No se pudo publicar la simulación.")
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
 * simulada y la muestra en Logcat (no se envía a WhatsApp).
 *
 * Solo se registra en buildType debug (ver src/debug/AndroidManifest.xml).
 */
class DebugReplyReceiver : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intent: Intent) {
        val remitente = intent.getStringExtra(DebugSimulator.EXTRA_CONVERSATION) ?: "desconocido"
        val textoRespuesta = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(DebugSimulator.CLAVE_REMOTE_INPUT)
            ?.toString()
            ?: "(vacío)"

        Log.d(
            "WalinkDebug",
            "RESPUESTA RECIBIDA (modo depuración): responder a '$remitente' con: '$textoRespuesta'"
        )
    }
}
