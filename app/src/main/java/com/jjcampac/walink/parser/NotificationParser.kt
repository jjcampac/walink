package com.jjcampac.walink.parser

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jjcampac.walink.debug.DebugSimulator
import com.jjcampac.walink.diag.LogDiag

/**
 * Resultado del parseo de una notificación de WhatsApp.
 *
 * @param remitente Nombre del contacto o del grupo (si es chat individual) o del
 * remitente concreto del mensaje (si es grupo).
 * @param texto Cuerpo del mensaje.
 * @param esGrupo true si la notificación proviene de un grupo de WhatsApp.
 * @param paqueteOrigen com.whatsapp o com.whatsapp.w4b (o el paquete propio en debug).
 * @param marcaTiempoMensaje marca de tiempo del propio mensaje según la notificación
 * (0 si no la expone). No cambia cuando WhatsApp republica la notificación del mismo
 * mensaje, así que sirve para no leerlo ni preguntarlo dos veces.
 *
 * @author jjcampac
 * @author cline
 */
data class MensajeWhatsApp(
    val remitente: String,
    val texto: String,
    val esGrupo: Boolean,
    val paqueteOrigen: String,
    val esNotaDeVoz: Boolean = false,
    val marcaTiempoMensaje: Long = 0L
)

/**
 * Parsea notificaciones de WhatsApp para extraer remitente, texto y tipo de chat.
 *
 * WhatsApp envía notificaciones con:
 *   - Individual: EXTRA_TITLE = nombre del contacto, EXTRA_TEXT = mensaje.
 *   - Grupo:      EXTRA_TITLE = nombre del grupo, EXTRA_TEXT = "Remitente: mensaje"
 *                 o el cuerpo en MessagingStyle.
 *
 * Soporte de MessagingStyle: cuando la notificación lo expone (versiones recientes
 * de WhatsApp), se extrae el remitente real y el texto del último mensaje.
 *
 * Se ignoran:
 *   - Notificaciones de resumen ("3 mensajes nuevos", "Varios contactos").
 *   - La notificación resumen del grupo de WhatsApp (FLAG_GROUP_SUMMARY), que repite
 *     el último mensaje con otro remitente.
 *   - El eco de los mensajes propios ("Tú"), que WhatsApp publica al responder.
 *   - Notificaciones de llamadas.
 *   - Notificaciones sin texto usable.
 *
 * @author jjcampac
 * @author cline
 */
object NotificationParser {

    private const val TAG = "WalinkParser"
    private const val PAQUETE_WHATSAPP = "com.whatsapp"
    private const val PAQUETE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    // Longitud máxima razonable para un nombre de usuario en un chat de grupo.
    private const val MAX_LONGITUD_NOMBRE = 25

    /**
     * Nombres con los que WhatsApp identifica al propio usuario como autor de un mensaje
     * ("Tú" en español). Aparecen cuando la notificación se actualiza tras enviar una
     * respuesta (desde Walink por RemoteInput o desde el propio móvil) y hay que
     * ignorarlos para no leer ni preguntar por nuestros propios mensajes.
     *
     * Se cubren los idiomas soportados por la app (es, en, de, fr, it, ja, pt).
     */
    private val AUTORREFERENCIAS = setOf(
        "tú", "tu", "yo", "you", "me", "du", "ich", "vous", "moi", "io",
        "あなた", "自分", "私", "você", "voce", "eu"
    )

    /**
     * Intenta extraer un [MensajeWhatsApp] de una [StatusBarNotification].
     * Devuelve null si la notificación no es de WhatsApp o debe ignorarse.
     *
     * @param aceptarPaquetePropio Si es true (solo en buildType debug), también se aceptan
     * notificaciones del propio paquete (simulador de mensajes).
     */
    fun parsear(
        notificacion: StatusBarNotification,
        contexto: Context,
        aceptarPaquetePropio: Boolean = false
    ): MensajeWhatsApp? {
        val paquete = notificacion.packageName ?: return null

        // Solo procesar WhatsApp, WhatsApp Business y (en debug) el propio paquete.
        val esWhatsApp = paquete == PAQUETE_WHATSAPP || paquete == PAQUETE_WHATSAPP_BUSINESS
        val esPropio = aceptarPaquetePropio && paquete == contexto.packageName
        if (!esWhatsApp && !esPropio) {
            return null
        }

        // IMPORTANTE: en debug la app escucha sus propias notificaciones, pero SOLO
        // deben aceptarse las del canal del simulador de mensajes. El listener también
        // recibe la notificación en primer plano del LectorAsistenteService (canal
        // "walink_asistente_voz", "Walink escuchando") y, si se tratara como un mensaje,
        // se generaría un bucle infinito: listener -> servicio -> notificación FGS ->
        // listener (cada vuelta relee y pregunta "Mensaje de Walink escuchando...").
        if (esPropio) {
            val canal = notificacion.notification?.channelId
            if (canal != DebugSimulator.CHANNEL_ID) {
                Log.d(TAG, "Notificación propia descartada (canal='$canal' != simulador)")
                return null
            }
        }

        val notification = notificacion.notification ?: return null
        val extras = notification.extras ?: return null

        // 0) Ignorar la notificación "resumen" del grupo de WhatsApp
        // (FLAG_GROUP_SUMMARY). Además del aviso de cada mensaje, WhatsApp publica una
        // notificación resumen (mismo id, tag=null, categoría "msg") que repite el
        // último mensaje con otro remitente; sin este filtro el mismo mensaje se leía
        // dos veces y con un remitente equivocado.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return descartar("es el resumen del grupo de notificaciones")
        }

        // 1) Ignorar notificaciones de llamada.
        if (esNotificacionDeLlamada(extras, notification)) {
            return descartar("parece una llamada")
        }

        // 2) Ignorar resumen de varios mensajes ("3 mensajes nuevos", etc.).
        if (esNotificacionResumen(extras)) {
            return descartar("resumen de varios mensajes")
        }

        // 3) Ignorar avisos de WhatsApp que no son mensajes (progreso, servicio,
        // copia de seguridad, sistema…). Sin este filtro se leían como si fueran
        // mensajes de un contacto: p. ej. "Eliminando chat seleccionado…".
        motivoNoEsMensaje(notification)?.let { motivo -> return descartar(motivo) }

        // 4) Intentar extraer de MessagingStyle (si WhatsApp lo expone).
        val estilo = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (estilo != null) {
            val mensaje = extraerDeMessagingStyle(estilo, notification, paquete)
            if (mensaje != null) return mensaje
        }

        // 5) Fallback: usar EXTRA_TITLE / EXTRA_TEXT.
        val titulo = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val texto = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Un mensaje real siempre trae cuerpo (texto, emoji, "📷 Foto", "🎤 mensaje de
        // voz"...). Si solo hay título (p. ej. "Eliminando chat seleccionado…") es un
        // aviso de sistema de WhatsApp, no un mensaje de un contacto.
        if (titulo.isBlank() || texto.isBlank()) return null

        // Heurística de grupo: en WhatsApp, un chat de grupo suele marcar el
        // texto como "RemitenteGrupo: mensaje" o el título coincide con un grupo.
        val infoTexto = extras.getString(Notification.EXTRA_INFO_TEXT) ?: ""
        val (remitente, textoLimpio, esGrupo) = separarRemitenteGrupo(titulo, texto, infoTexto)

        // Ignorar notificaciones cuyo remitente sea una autoreferencia del
        // usuario (p. ej. "Tú" en WhatsApp en español). Esto se produce cuando
        // WhatsApp actualiza la notificación tras un envío por RemoteInput y la
        // notificación no expone MessagingStyle (caso raro en versiones modernas).
        if (remitente.lowercase().trim() in AUTORREFERENCIAS) {
            return descartar("mensaje propio (remitente '$remitente')")
        }

        val esAudio = esMensajeDeVoz(textoLimpio.lowercase())

        return MensajeWhatsApp(
            remitente = if (remitente.isNotBlank()) remitente.trim() else "Desconocido",
            texto = if (textoLimpio.isNotBlank()) textoLimpio.trim() else "",
            esGrupo = esGrupo,
            paqueteOrigen = paquete,
            esNotaDeVoz = esAudio
        )
    }

    /**
     * Intenta extraer el remitente y texto a partir de NotificationCompat.MessagingStyle.
     * Devuelve null si no hay mensajes ni información útil.
     */
    private fun extraerDeMessagingStyle(
        estilo: NotificationCompat.MessagingStyle,
        notification: Notification,
        paquete: String
    ): MensajeWhatsApp? {
        val mensajes = estilo.messages
        if (mensajes.isEmpty()) return null

        // Usar el mensaje más reciente.
        val ultimoMensaje = mensajes.lastOrNull() ?: return null
        val texto = ultimoMensaje.text?.toString() ?: return null
        if (texto.isBlank()) return null

        // IMPORTANTE: si el último mensaje lo envió el propio usuario (sin autor, o con
        // el nombre con el que aparece en su WhatsApp: "Tú"), la notificación se ha
        // actualizado tras responder desde Walink (RemoteInput) o desde el móvil.
        // Se ignora para no leer ni preguntar por nuestros propios mensajes.
        @Suppress("DEPRECATION")
        val sender = ultimoMensaje.sender
        if (esAutorPropio(estilo, sender)) {
            return descartar("mensaje propio (autor '${sender ?: "sin autor"}')")
        }

        // El autor puede ser null en WhatsApp Business / algunas versiones.
        // sender está deprecado en la API de MessagingStyle pero es la única
        // forma accesible de obtener el remitente sin usar APIs internas.
        val autor = sender?.toString()?.trim() ?: ""
        val tituloConversacion = estilo.conversationTitle?.toString()?.trim()

        // MessagingStyle solo indica grupo si existe ConversationTitle.
        // (En chat individual no hay conversationTitle.)
        val esGrupo = !tituloConversacion.isNullOrBlank()

        // Remitente a leer: nombre del autor (si es grupo) o el título de la conversación.
        val remitenteLeer = when {
            esGrupo && autor.isNotBlank() -> autor
            !esGrupo -> tituloConversacion ?: autor
            else -> autor.ifBlank { tituloConversacion ?: "Desconocido" }
        }

        // Extra de llamada: algunas notificaciones de llamada usan MessagingStyle.
        // Si el texto parece de llamada, lo ignoramos aquí también por seguridad.
        val textoLower = texto.lowercase()
        if (textoLower.contains("llamada") || textoLower.contains("video call")) return null

        val esAudio = esMensajeDeVoz(textoLower, ultimoMensaje.dataMimeType)

        return MensajeWhatsApp(
            remitente = remitenteLeer.ifBlank { "Desconocido" },
            texto = texto,
            esGrupo = esGrupo,
            paqueteOrigen = paquete,
            esNotaDeVoz = esAudio,
            marcaTiempoMensaje = ultimoMensaje.timestamp
        )
    }

    /**
     * Determina si el texto o mimeType corresponde a un mensaje/nota de voz de WhatsApp.
     */
    private fun esMensajeDeVoz(textoLower: String, dataMimeType: String? = null): Boolean {
        if (dataMimeType?.startsWith("audio/") == true) return true
        return textoLower.contains("mensaje de voz") ||
               textoLower.contains("nota de voz") ||
               textoLower.contains("voice message") ||
               textoLower.contains("audio (") ||
               textoLower.startsWith("🎤") ||
               textoLower.contains("🎤 mensaje de voz")
    }

    /**
     * Separa el remitente del texto cuando la notificación parece de grupo.
     *
     * En chats de grupo WhatsApp suele enviar en EXTRA_TEXT:
     *   "Ana: ¿quedamos a las 8?"    -> remitente="Ana", texto="¿quedamos a las 8?"
     * mientras que en chat individual el texto es solo el mensaje.
     *
     * @return Triple (remitente, textoLimpio, esGrupo).
     */
    private fun separarRemitenteGrupo(
        titulo: String,
        texto: String,
        infoTexto: String
    ): Triple<String, String, Boolean> {
        // Caso 1: INFO_TEXT puede contener el remitente en chats de grupo
        // (WhatsApp a veces lo pone ahí).
        if (infoTexto.isNotBlank()) {
            // Si hay info texto Y el cuerpo empieza con "nombre: " separamos.
            val (nombre, resto) = dividirNombreYResto(texto)
            if (nombre != null && resto != null) {
                return Triple(nombre, resto, true)
            }
            // Si no se pudo separar, el remitente es el grupo (título).
            return Triple(titulo, texto, true)
        }

        // Caso 2: el texto empieza con "nombre: mensaje" (sin INFO_TEXT).
        val (nombre, resto) = dividirNombreYResto(texto)
        if (nombre != null && resto != null) {
            return Triple(nombre, resto, true)
        }

        // Caso por defecto: chat individual.
        return Triple(titulo, texto, false)
    }

    /**
     * Intenta dividir una cadena con el patrón "<nombre>: <resto>".
     * Devuelve (null, null) si no parece un nombre de remitente.
     */
    private fun dividirNombreYResto(texto: String): Pair<String?, String?> {
        if (texto.isBlank()) return null to null

        val indiceDosPuntos = texto.indexOf(':')
        if (indiceDosPuntos <= 0 || indiceDosPuntos > MAX_LONGITUD_NOMBRE) {
            return null to null
        }

        val posibleNombre = texto.substring(0, indiceDosPuntos).trim()
        val resto = texto.substring(indiceDosPuntos + 1).trim()

        // Un nombre de remitente no suele contener espacios raros ni puntuación rara:
        // evitamos romper textos legítimos como "Cita: 18:30" o URLs ("http://...").
        if (posibleNombre.isBlank() || resto.isBlank()) return null to null
        if (posibleNombre.any { it in "!?.,;@#%&*<>/\\\"'|()[]{}" }) return null to null
        if (posibleNombre.length > MAX_LONGITUD_NOMBRE) return null to null
        // Un "nombre" de una sola letra es sospechoso (p.ej. "A: ...").
        if (posibleNombre.length < 2) return null to null

        return posibleNombre to resto
    }

    /**
     * Devuelve el motivo por el que una notificación de WhatsApp **no es un mensaje**
     * (avisos de progreso como "Eliminando chat seleccionado…", copias de seguridad,
     * "WhatsApp Web conectado", avisos de servicio…), o null si puede serlo.
     *
     * WhatsApp publica estos avisos en su mismo paquete y, sin este filtro, se
     * trataban como si fueran mensajes de un contacto: se leía en voz alta un
     * remitente inexistente y se ofrecía responder a algo que no era un chat.
     *
     * Solo se descartan los casos claramente identificables (categoría de sistema,
     * notificación "en curso" o no descartable sin MessagingStyle); un mensaje real
     * de WhatsApp es descartable (FLAG_AUTO_CANCEL) y trae cuerpo de texto.
     */
    private fun motivoNoEsMensaje(notification: Notification): String? {
        val categoriasNoMensaje = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_ERROR,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_EVENT,
            Notification.CATEGORY_PROMO,
            Notification.CATEGORY_RECOMMENDATION,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_ALARM
        )
        val categoria = notification.category ?: ""
        if (categoria in categoriasNoMensaje) {
            return "categoría de sistema '$categoria'"
        }

        // Un mensaje nunca es una notificación "en curso" (progreso que no termina).
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return "evento en curso (FLAG_ONGOING_EVENT)"
        }

        // Avisos que no se pueden descartar y que no traen conversación: son de sistema.
        val tieneEstilo =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) != null
        if (!tieneEstilo && (notification.flags and Notification.FLAG_NO_CLEAR) != 0) {
            return "no descartable y sin MessagingStyle"
        }

        return null
    }

    /**
     * Indica si el autor del último mensaje es el propio usuario.
     *
     * WhatsApp actualiza la notificación al enviar un mensaje (desde Walink por
     * RemoteInput o desde el propio móvil) y lo firma con el nombre con el que el
     * usuario aparece en su WhatsApp ("Tú" en español, "You" en inglés…). Leerlo daría
     * lugar a "Mensaje de Tú. ¿Lo leo?" justo después de responder.
     *
     * @param autor nombre del autor del último mensaje (o null si no lo expone).
     */
    private fun esAutorPropio(estilo: NotificationCompat.MessagingStyle, autor: CharSequence?): Boolean {
        // Sin autor no hay forma de saberlo: se mantiene el comportamiento anterior
        // (se ignora, porque WhatsApp lo omite en el eco de los mensajes propios).
        val nombre = autor?.toString()?.trim() ?: return true
        if (nombre.isEmpty()) return true

        // Si WhatsApp expone el perfil del usuario, comparamos con su nombre.
        @Suppress("DEPRECATION")
        val nombreUsuario = estilo.user?.name?.toString()?.trim()
        if (!nombreUsuario.isNullOrBlank() && nombreUsuario.equals(nombre, ignoreCase = true)) {
            return true
        }

        return nombre.lowercase() in AUTORREFERENCIAS
    }

    /**
     * Registra en el diagnóstico el motivo por el que se descarta una notificación y
     * devuelve null, para poder usarlo como `return descartar("…")`.
     */
    private fun descartar(motivo: String): MensajeWhatsApp? {
        Log.d(TAG, "Notificación descartada: $motivo")
        LogDiag.d("Notificación descartada ($motivo)")
        return null
    }

    private fun esNotificacionDeLlamada(extras: Bundle, notification: Notification): Boolean {
        val categoria = notification.category
        if (categoria == Notification.CATEGORY_CALL) return true

        val titulo = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val texto = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val textoCombinado = (titulo + " " + texto).lowercase()

        return textoCombinado.contains("llamada") ||
               textoCombinado.contains("call") ||
               textoCombinado.contains("videollamada") ||
               textoCombinado.contains("video call")
    }

    private fun esNotificacionResumen(extras: Bundle): Boolean {
        val texto = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val titulo = extras.getString(Notification.EXTRA_TITLE) ?: ""

        val textoLower = texto.lowercase()
        val tituloLower = titulo.lowercase()

        return textoLower.contains("mensajes nuevos") ||
               textoLower.contains("new messages") ||
               tituloLower.contains("varios contactos") ||
               (tituloLower.contains("grupo") && texto.isBlank()) ||
               (extras.containsKey("android.subText") && texto.isBlank() && titulo.isBlank())
    }
}
