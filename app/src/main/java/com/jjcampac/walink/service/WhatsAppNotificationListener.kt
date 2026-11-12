package com.jjcampac.walink.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.jjcampac.walink.BuildConfig
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag
import com.jjcampac.walink.idioma.IdiomaHelper
import com.jjcampac.walink.parser.NotificationParser
import com.jjcampac.walink.speech.LectorAsistenteService
import com.jjcampac.walink.speech.SpeechReader

/**
 * Servicio que escucha las notificaciones de WhatsApp (y del simulador en debug).
 *
 * Es el punto de entrada de la lectura de mensajes:
 *  - Modo "leer inmediatamente": lee el mensaje aquí mismo con su [SpeechReader],
 *    sin servicio en primer plano (no requiere micrófono ni recursos extra).
 *  - Modo "preguntar antes de leer": delega en [LectorAsistenteService], un
 *    servicio en primer plano (tipo micrófono) que pregunta, escucha y luego lee.
 *
 * Esta separación evita cuelgues por servicios en primer plano innecesarios y
 * garantiza que nunca haya dos lecturas a la vez.
 *
 * @author jjcampac
 * @author cline
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var speechReader: SpeechReader
    private lateinit var dataStore: WalinkDataStore

    companion object {
        private const val TAG = "WalinkListener"
        // Ventana de seguridad: ignoramos re-posts de la misma clave que ocurren
        // en menos de este tiempo (evita ráfagas de reenvíos).
        private const val DEDUP_WINDOW_MS = 30_000L
        // Tamaño máximo del caché de claves gestionadas (evita crecimiento infinito).
        private const val DEDUP_MAX_SIZE = 64
        // Ventana para descartar el mismo mensaje publicado dos veces con avisos
        // distintos (clave/tag diferente). Los duplicados observados llegan en 40-70 ms,
        // así que una ventana corta basta y no oculta mensajes repetidos legítimos.
        private const val DEDUP_HUELLA_MS = 2_500L
    }

    /** Caché de claves de notificación procesadas recientemente (timestamp de llegada). */
    private val notificacionesRecientes = LinkedHashMap<String, Long>(
        /* initialCapacity */ 16,
        /* loadFactor */ 0.75f,
        /* accessOrder */ false
    )

    /** Clave de notificación -> postTime de última notificación que gestionamos
     *  (leímos o preguntamos). Si WhatsApp vuelve a publicar el mismo aviso con un
     *  postTime igual o anterior, no es un mensaje nuevo: se ignora. Esto rompe el
     *  bucle de "¿lo leo?" cuando una notificación activa se reenvía varias veces. */
    private val notificacionesGestionadas = LinkedHashMap<String, Long>(
        /* initialCapacity */ 16,
        /* loadFactor */ 0.75f,
        /* accessOrder */ false
    )

    private fun yaGestionada(clave: String, postTime: Long): Boolean {
        val previo = notificacionesGestionadas[clave]
        return previo != null && postTime <= previo
    }

    /** Huella de contenido (paquete + remitente + texto) -> instante en que se gestionó.
     *
     *  Cubre el caso en que WhatsApp publica **dos avisos distintos** (con `key`/`tag`
     *  diferentes, por lo que los filtros por clave no los ven) del mismo mensaje: sin
     *  esto se preguntaba "¿lo leo?" dos veces o se leía el mensaje dos veces. */
    private val huellasRecientes = LinkedHashMap<String, Long>(
        /* initialCapacity */ 16,
        /* loadFactor */ 0.75f,
        /* accessOrder */ false
    )

    private fun marcarGestionada(clave: String, postTime: Long) {
        if (notificacionesGestionadas.size >= DEDUP_MAX_SIZE) {
            notificacionesGestionadas.remove(notificacionesGestionadas.keys.first())
        }
        notificacionesGestionadas[clave] = postTime
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(IdiomaHelper.contextoLocalizado(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        speechReader = SpeechReader.obtener(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
        LogDiag.iniciar(this)
        Log.d(TAG, "Servicio de notificaciones creado")
    }

    override fun onDestroy() {
        Log.d(TAG, "Servicio de notificaciones destruido")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            if (!dataStore.estaActivado) return

            // ---- Deduplicación y protección contra bucles ----
            val ahora = System.currentTimeMillis()
            val clave = sbn.key
            val postTime = sbn.postTime

            // 1. Re-post demasiado rápido de la misma clave (ventana de seguridad).
            val ultimaVez = notificacionesRecientes[clave]
            if (ultimaVez != null && ahora - ultimaVez < DEDUP_WINDOW_MS) {
                Log.d(TAG, "Notificación duplicada (ventana) ignorada (clave=$clave, hace ${ahora - ultimaVez} ms)")
                return
            }

            // 2. Ya gestionamos este aviso con un postTime igual o posterior ->
            //    no es un mensaje nuevo, es un re-post del mismo aviso.
            if (yaGestionada(clave, postTime)) {
                Log.d(TAG, "Notificación ya gestionada, ignorada (clave=$clave, postTime=$postTime)")
                return
            }

            notificacionesRecientes[clave] = ahora
            marcarGestionada(clave, postTime)

            val mensaje = NotificationParser.parsear(
                sbn,
                this,
                // En debug se acepta siempre el paquete propio (simulador); en
                // release solo si la puerta oculta de depuración está activada
                // (7 pulsaciones sobre la versión en la cabecera).
                aceptarPaquetePropio = BuildConfig.DEBUG || dataStore.modoDepuracion
            ) ?: return

            // Metadatos del aviso en el diagnóstico: imprescindibles para entender los
            // duplicados (dos avisos con clave/tag distinta para el mismo mensaje).
            val notif = sbn.notification
            LogDiag.d(
                "Notif: pkg=${sbn.packageName} id=${sbn.id} tag=${sbn.tag} postTime=$postTime " +
                    "grupo=${sbn.isGroup()} groupKey=${sbn.groupKey} " +
                    "flags=0x${Integer.toHexString(notif?.flags ?: 0)} " +
                    "cat=${notif?.category} canal=${notif?.channelId} " +
                    "longitudTexto=${mensaje.texto.length}"
            )

            // Foto del estado del teléfono en el momento exacto de recibir un mensaje
            // real (pantalla, no-molestar, batería y Bluetooth). Se guarda en logcat y
            // en filesDir/walink_diag.log para poder diagnosticar un fallo aunque
            // ocurra lejos del ordenador.
            // Se incluye el estado del FGS permanente de micrófono: si no está activo,
            // la app no tiene el permiso while-in-use de RECORD_AUDIO y el reconocedor
            // no captará voz (picoRMS=0) con la pantalla apagada y la app en segundo plano.
            val estadoMicro = if (MicrofonoPermanenteService.estaActivo) {
                "micro-permanente=ACTIVO"
            } else {
                "micro-permanente=NO ACTIVO (sin while-in-use: la escucha fallará)"
            }
            LogDiag.registrarEstado(
                this,
                "mensaje recibido de '${mensaje.remitente}' (grupo=${mensaje.esGrupo})",
                estadoMicro
            )

            // 3. Mismo contenido en una ventana muy corta: WhatsApp a veces publica dos
            //    avisos distintos (clave/tag/id diferentes) para el mismo mensaje, por lo
            //    que los filtros 1 y 2 no los detectan y se preguntaba o leía dos veces.
            val huella = "${mensaje.paqueteOrigen}|${mensaje.remitente.trim().lowercase()}|" +
                mensaje.texto.trim()
            val huellaPrevia = huellasRecientes[huella]
            if (huellaPrevia != null && ahora - huellaPrevia < DEDUP_HUELLA_MS) {
                LogDiag.d("Mensaje repetido ignorado (misma huella hace ${ahora - huellaPrevia} ms)")
                Log.d(TAG, "Mensaje repetido ignorado (misma huella, hace ${ahora - huellaPrevia} ms)")
                return
            }
            if (huellasRecientes.size >= DEDUP_MAX_SIZE) {
                huellasRecientes.remove(huellasRecientes.keys.first())
            }
            huellasRecientes[huella] = ahora

            // ---- Regla del dispositivo Bluetooth del coche (uno o varios) ----
            // Si el usuario ha elegido uno o más dispositivos en los ajustes, Walink
            // solo actúa cuando ALGUNO de ellos está conectado. Salvo que esté
            // activo el ajuste "leerSinCoche" (modo pruebas), o que no haya
            // ninguno seleccionado (conjunto vacío), en cuyo caso se lee por el
            // altavoz del teléfono.
            // El ajuste "leerSinCoche" solo tiene sentido en modo depuración (es una
            // ayuda de pruebas). Si el modo depuración no está activo, se ignora
            // aunque el valor persistido siga a true (por ejemplo, porque la tarjeta
            // de depuración se ocultó tras haberlo activado).
            val modoDebugActivo = BuildConfig.DEBUG || dataStore.modoDepuracion
            val leerSinCocheEfectivo = modoDebugActivo && dataStore.leerSinCoche
            val direccionesCoche = dataStore.dispositivosBluetooth
            if (direccionesCoche.isNotEmpty() && !leerSinCocheEfectivo) {
                if (!BluetoothCoche.estaConectadoCualquiera(applicationContext, direccionesCoche)) {
                    val nombres = direccionesCoche
                        .map { BluetoothCoche.nombreDe(applicationContext, it) ?: it }
                        .joinToString(", ")
                    Log.d(
                        TAG,
                        "Ninguno de los coches ($nombres) conectado -> mensaje de " +
                            "'${mensaje.remitente}' ignorado"
                    )
                    return
                }
                Log.d(TAG, "Alguno de los coches seleccionados conectado -> procesando mensaje")
            }

            // ---- Filtro de grupos (Fase 4) ----
            if (mensaje.esGrupo && !dataStore.leerGrupos) {
                Log.d(TAG, "Mensaje de grupo '${mensaje.remitente}' ignorado por filtro de grupos")
                return
            }

            // ---- Filtro de contactos: permitidos / bloqueados (Fase 4) ----
            val remitenteNormalizado = mensaje.remitente.trim()
            val bloqueados = dataStore.contactosBloqueados
            if (bloqueados.isNotEmpty()) {
                val estaBloqueado = bloqueados.any { contacto ->
                    remitenteNormalizado.equals(contacto, ignoreCase = true) ||
                    remitenteNormalizado.contains(contacto, ignoreCase = true)
                }
                if (estaBloqueado) {
                    Log.d(TAG, "Mensaje de '$remitenteNormalizado' ignorado (en lista de bloqueados)")
                    return
                }
            }

            val permitidos = dataStore.contactosPermitidos
            if (permitidos.isNotEmpty()) {
                val estaPermitido = permitidos.any { contacto ->
                    remitenteNormalizado.equals(contacto, ignoreCase = true) ||
                    remitenteNormalizado.contains(contacto, ignoreCase = true)
                }
                if (!estaPermitido) {
                    Log.d(TAG, "Mensaje de '$remitenteNormalizado' ignorado (no está en lista de permitidos)")
                    return
                }
            }

            val cuerpoMensaje = when {
                mensaje.esNotaDeVoz -> getString(R.string.nota_de_voz_detectada, mensaje.texto)
                !dataStore.leerTextoCompleto -> "[texto oculto]"
                else -> mensaje.texto
            }
            val textoParaLeer = getString(R.string.tts_formato, mensaje.remitente, cuerpoMensaje)

            // El modo se decide únicamente por la preferencia persistente
            // leerTrasPreguntar. No depende del parámetro recibido por el
            // simulador de debug: este solo publica una notificación idéntica
            // a la de WhatsApp, y el listener aplica la regla de negocio.
            val esInmediato = !dataStore.leerTrasPreguntar
            val modoTexto = if (esInmediato) "leer inmediato" else "preguntar"
            Log.d(TAG, "Modo lectura: leerTrasPreguntar=${dataStore.leerTrasPreguntar} -> $modoTexto")
            // Los datos de respuesta (PendingIntent + claves del RemoteInput) se extraen
            // de la notificación original y se pasan al servicio para poder enviar la
            // respuesta dictada por la acción de respuesta de la propia notificación.
            val (pendingResponder, clavesRemoteInput) = extraerDatosRespuesta(sbn)
            Log.d(TAG, "Datos de respuesta: pendingIntent=${pendingResponder != null}, claves=$clavesRemoteInput")

            if (esInmediato) {
                // Modo "leer inmediatamente": leemos el mensaje aquí (en el listener),
                // pero CALENTAMOS ya el LectorAsistenteService conmutándolo a tipo
                // "microphone" en esta ventana de notificación, que es donde la app aún
                // es elegible para acceder al permiso foreground-only RECORD_AUDIO.
                // Sin este calentamiento, al terminar la lectura el arranque en frío
                // desde segundo plano no podía conmutar el FGS a "microphone"
                // (SecurityException) y la escucha de "¿quieres responder?" se quedaba
                // sin audio con la pantalla apagada (solo el primer buffer).
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, LectorAsistenteService::class.java)
                        .setAction(LectorAsistenteService.ACTION_PREPARAR_VOZ)
                )
                Log.d(TAG, "Mensaje de '${mensaje.remitente}' (grupo=${mensaje.esGrupo}) -> leyendo ahora (mic precalentado)")
                speechReader.hablar(textoParaLeer) {
                    // Tras terminar la lectura, ofrecer responder por voz.
                    // El servicio ya verificará el permiso de micrófono internamente.
                    val intent = Intent(this, LectorAsistenteService::class.java)
                        .setAction(LectorAsistenteService.ACTION_RESPONDER)
                        .putExtra(LectorAsistenteService.EXTRA_REMITENTE, mensaje.remitente)
                        .putExtra(LectorAsistenteService.EXTRA_TEXTO, textoParaLeer)
                        .putExtra(
                            LectorAsistenteService.EXTRA_PENDING_INTENT_RESPONDER,
                            pendingResponder as? android.os.Parcelable
                        )
                        .putStringArrayListExtra(
                            LectorAsistenteService.EXTRA_CLAVES_REMOTE_INPUT,
                            ArrayList(clavesRemoteInput)
                        )
                    ContextCompat.startForegroundService(this, intent)
                }
            } else {
                // Modo "preguntar": delega en el servicio en primer plano.
                Log.d(TAG, "Mensaje de '${mensaje.remitente}' -> modo preguntar")
                val intent = Intent(this, LectorAsistenteService::class.java)
                    .setAction(LectorAsistenteService.ACTION_PREGUNTAR)
                    .putExtra(LectorAsistenteService.EXTRA_REMITENTE, mensaje.remitente)
                    .putExtra(LectorAsistenteService.EXTRA_TEXTO, textoParaLeer)
                    .putExtra(
                        LectorAsistenteService.EXTRA_PENDING_INTENT_RESPONDER,
                        pendingResponder as? android.os.Parcelable
                    )
                    .putStringArrayListExtra(
                        LectorAsistenteService.EXTRA_CLAVES_REMOTE_INPUT,
                        ArrayList(clavesRemoteInput)
                    )
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (e: Exception) {
            // Nunca debe romper el listener.
            Log.e(TAG, "Error procesando notificación", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
    }

    /**
     * Extrae de la notificación la acción de respuesta con RemoteInput:
     * el PendingIntent que hay que lanzar para responder y las claves de los
     * RemoteInput (necesarias para rellenar el resultado con el texto dictado).
     *
     * Devuelve null cuando la notificación no trae acción de respuesta (p. ej.
     * un aviso sin la acción "Responder"), en cuyo caso no se podrá enviar.
     */
    private fun extraerDatosRespuesta(sbn: StatusBarNotification): Pair<android.os.Parcelable?, List<String>> {
        val accionRespuesta: Notification.Action? = sbn.notification.actions
            ?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val pendingIntent = accionRespuesta?.actionIntent
        val claves = accionRespuesta?.remoteInputs
            ?.map { it.resultKey }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return pendingIntent to claves
    }

    }
