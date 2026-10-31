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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(IdiomaHelper.contextoLocalizado(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        speechReader = SpeechReader.obtener(this)
        dataStore = WalinkDataStore.getInstance(applicationContext)
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

            val mensaje = NotificationParser.parsear(
                sbn,
                this,
                aceptarPaquetePropio = BuildConfig.DEBUG
            ) ?: return

            // ---- Regla del dispositivo Bluetooth del coche ----
            // Si el usuario ha elegido un dispositivo en los ajustes, Walink solo
            // actúa cuando ese dispositivo está conectado. Salvo que esté activo el
            // ajuste "leerSinCoche" (modo pruebas), o que no haya ninguno seleccionado
            // (null/vacío), en cuyo caso se lee por el altavoz del teléfono.
            val direccionCoche = dataStore.dispositivoBluetooth
            if (!direccionCoche.isNullOrBlank() && !dataStore.leerSinCoche) {
                if (!BluetoothCoche.estaConectado(applicationContext, direccionCoche)) {
                    val nombreCoche = BluetoothCoche.nombreDe(applicationContext, direccionCoche)
                    Log.d(
                        TAG,
                        "Coche '$nombreCoche' ($direccionCoche) NO conectado -> mensaje de " +
                            "'${mensaje.remitente}' ignorado"
                    )
                    return
                }
                Log.d(TAG, "Coche seleccionado conectado -> procesando mensaje")
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
                // Modo "leer inmediatamente": leemos sin servicio en primer plano.
                // Si quedara un LectorAsistenteService de un ciclo "preguntar" anterior
                // (por un onDone TTS perdido), lo detenemos para que no contamine esta
                // lectura con una pregunta fantasma.
                stopService(Intent(this, LectorAsistenteService::class.java))
                Log.d(TAG, "Mensaje de '${mensaje.remitente}' (grupo=${mensaje.esGrupo}) -> leyendo ahora")
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

    companion object {
        private const val TAG = "WalinkListener"
    }
}
