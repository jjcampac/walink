package com.jjcampac.walink.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Datos necesarios para responder a un mensaje con la acción de respuesta
 * (RemoteInput) de la propia notificación: el PendingIntent que hay que lanzar y las
 * claves de los RemoteInput, con las que se rellena el resultado con el texto dictado.
 *
 * @author jjcampac
 * @author cline
 */
data class DatosRespuesta(
    val pendingIntent: PendingIntent?,
    val claves: List<String>
) {
    /** true si hay datos suficientes para intentar el envío. */
    val valido: Boolean get() = pendingIntent != null && claves.isNotEmpty()
}

/**
 * Extrae y refresca los [DatosRespuesta] de las notificaciones de WhatsApp.
 *
 * WhatsApp reemplaza la notificación del chat cada vez que llega un mensaje nuevo. El
 * PendingIntent de la versión anterior deja de entregar el texto a WhatsApp **sin
 * devolver ningún error** (el envío parece correcto pero no se envía nada), así que
 * justo antes de enviar se vuelven a leer los datos de la notificación vigente.
 *
 * @author jjcampac
 * @author cline
 */
object DatosRespuestaHelper {

    private const val TAG = "WalinkDatosRespuesta"

    /** Lee los datos de respuesta de una notificación concreta (la primera acción de respuesta). */
    fun extraer(sbn: StatusBarNotification): DatosRespuesta {
        val accionRespuesta = sbn.notification?.actions
            ?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val pendingIntent = accionRespuesta?.actionIntent
        val claves = accionRespuesta?.remoteInputs
            ?.map { it.resultKey }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return DatosRespuesta(pendingIntent, claves)
    }

    /**
     * Vuelve a leer los datos de respuesta de la notificación activa con [clave].
     *
     * Se consulta primero la instancia conectada del
     * [WhatsAppNotificationListener] (única vía fiable para ver notificaciones de otros
     * paquetes) y, si no está disponible, `NotificationManager` (solo devuelve las de
     * la propia app, pero no molesta como respaldo).
     *
     * Devuelve null si no se encuentra (notificación ya retirada, listener desconectado
     * o sin acción de respuesta), en cuyo caso hay que conservar los datos guardados al
     * leer el mensaje.
     */
    fun extraerVigente(contexto: Context, clave: String?): DatosRespuesta? {
        if (clave.isNullOrBlank()) return null

        val vigente = WhatsAppNotificationListener.notificacionActiva(clave) ?: buscarEnGestor(contexto, clave)
        if (vigente == null) {
            Log.d(TAG, "No hay notificación vigente con esa clave")
            return null
        }

        val datos = extraer(vigente)
        if (!datos.valido) {
            Log.d(TAG, "La notificación vigente no trae acción de respuesta")
            return null
        }
        Log.d(TAG, "Datos de respuesta refrescados (postTime=${vigente.postTime})")
        return datos
    }

    /** Respaldo: consulta las notificaciones activas a través de NotificationManager. */
    private fun buscarEnGestor(contexto: Context, clave: String): StatusBarNotification? {
        return try {
            val gestor = contexto.getSystemService(NotificationManager::class.java) ?: return null
            gestor.activeNotifications?.firstOrNull { it.key == clave }
        } catch (t: Throwable) {
            // Sin permiso de acceso a notificaciones o servicio caído: no es crítico.
            Log.d(TAG, "No se pudo consultar NotificationManager: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Indica si la notificación ya contiene [texto] como **último** mensaje de la
     * conversación, es decir, si WhatsApp ha insertado la respuesta enviada.
     *
     * `PendingIntent.send()` no devuelve ningún error aunque WhatsApp descarte el texto,
     * así que esta comprobación es la única forma de no anunciar un envío que en
     * realidad no se ha producido.
     */
    fun contieneMensajePropio(sbn: StatusBarNotification, texto: String): Boolean {
        val notification = sbn.notification ?: return false
        val estilo = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification) ?: return false
        val ultimo = estilo.messages.lastOrNull() ?: return false
        val textoUltimo = ultimo.text?.toString()?.trim().orEmpty()
        return textoUltimo.isNotEmpty() && textoUltimo.equals(texto.trim(), ignoreCase = true)
    }
}
