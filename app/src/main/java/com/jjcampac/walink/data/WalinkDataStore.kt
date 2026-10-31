package com.jjcampac.walink.data

import android.content.Context
import android.content.SharedPreferences

// Claves de preferencia guardadas en SharedPreferences (todo local, sin red)
private object WalinkKeys {
    const val ACTIVADO = "activado"
    const val DISPOSITIVO_BLUETOOTH = "dispositivo_bluetooth"
    const val CONTACTOS_PERMITIDOS = "contactos_permitidos"
    const val CONTACTOS_BLOQUEADOS = "contactos_bloqueados"
    const val LEER_GRUPOS = "leer_grupos"
    const val LEER_TEXTO_COMPLETO = "leer_texto_completo"
    const val VELOCIDAD_VOZ = "velocidad_voz"
    const val VOZ_TTS = "voz_tts"
    const val ONBOARDING_COMPLETADO = "onboarding_completado"
    const val LEER_TRAS_PREGUNTAR = "leer_tras_preguntar"
    const val VOLUMEN_ATENUAR = "volumen_atenuar"
    const val RETRASO_BLUETOOTH = "retraso_bluetooth"
    const val LEER_SIN_COCHE = "leer_sin_coche"
    const val BORRADOR_REMITENTE = "borrador_remitente"
    const val BORRADOR_TEXTO = "borrador_texto"
    const val BORRADOR_TIMESTAMP = "borrador_timestamp"
}

/**
 * Acceso a preferencias persistentes con SharedPreferences.
 * Toda la información se guarda localmente en el dispositivo.
 */
class WalinkDataStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("walink_prefs", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var INSTANCE: WalinkDataStore? = null
        fun getInstance(context: Context): WalinkDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalinkDataStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ---- ACTIVADO (interruptor principal) ----
    val estaActivado: Boolean
        get() = prefs.getBoolean(WalinkKeys.ACTIVADO, false)

    fun setActivado(activado: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.ACTIVADO, activado).apply()
    }

    // ---- DISPOSITIVO BLUETOOTH ----
    val dispositivoBluetooth: String?
        get() = prefs.getString(WalinkKeys.DISPOSITIVO_BLUETOOTH, null)

    suspend fun setDispositivoBluetooth(direccionMac: String?) {
        prefs.edit().putString(WalinkKeys.DISPOSITIVO_BLUETOOTH, direccionMac).apply()
    }

    // ---- CONTACTOS PERMITIDOS ----
    val contactosPermitidos: Set<String>
        get() {
            val valor = prefs.getString(WalinkKeys.CONTACTOS_PERMITIDOS, "") ?: ""
            return if (valor.isBlank()) emptySet()
            else valor.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

    suspend fun setContactosPermitidos(contactos: Set<String>) {
        prefs.edit().putString(WalinkKeys.CONTACTOS_PERMITIDOS, contactos.joinToString(",")).apply()
    }

    // ---- CONTACTOS BLOQUEADOS ----
    val contactosBloqueados: Set<String>
        get() {
            val valor = prefs.getString(WalinkKeys.CONTACTOS_BLOQUEADOS, "") ?: ""
            return if (valor.isBlank()) emptySet()
            else valor.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

    suspend fun setContactosBloqueados(contactos: Set<String>) {
        prefs.edit().putString(WalinkKeys.CONTACTOS_BLOQUEADOS, contactos.joinToString(",")).apply()
    }

    // ---- LEER GRUPOS ----
    val leerGrupos: Boolean
        get() = prefs.getBoolean(WalinkKeys.LEER_GRUPOS, true)

    suspend fun setLeerGrupos(leer: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.LEER_GRUPOS, leer).apply()
    }

    // ---- LEER TEXTO COMPLETO ----
    val leerTextoCompleto: Boolean
        get() = prefs.getBoolean(WalinkKeys.LEER_TEXTO_COMPLETO, true)

    suspend fun setLeerTextoCompleto(leer: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.LEER_TEXTO_COMPLETO, leer).apply()
    }

    // ---- VELOCIDAD DE VOZ ----
    val velocidadVoz: Float
        get() = prefs.getFloat(WalinkKeys.VELOCIDAD_VOZ, 1.0f)

    suspend fun setVelocidadVoz(velocidad: Float) {
        prefs.edit().putFloat(WalinkKeys.VELOCIDAD_VOZ, velocidad).apply()
    }

    // ---- VOZ TTS (voz seleccionada para leer mensajes) ----
    // Guardamos el identificador (Voice.name) de la voz elegida. null = español por defecto.
    val vozTts: String?
        get() = prefs.getString(WalinkKeys.VOZ_TTS, null)

    suspend fun setVozTts(voz: String?) {
        prefs.edit().putString(WalinkKeys.VOZ_TTS, voz).apply()
    }

    // ---- ONBOARDING (primera ejecución) ----
    val onboardingCompletado: Boolean
        get() = prefs.getBoolean(WalinkKeys.ONBOARDING_COMPLETADO, false)

    fun marcarOnboardingCompletado() {
        prefs.edit().putBoolean(WalinkKeys.ONBOARDING_COMPLETADO, true).apply()
    }

    // ---- MODO DE LECTURA ----
    // true = preguntar antes de leer; false = leer el mensaje nada más recibido.
    val leerTrasPreguntar: Boolean
        get() = prefs.getBoolean(WalinkKeys.LEER_TRAS_PREGUNTAR, false)

    suspend fun setLeerTrasPreguntar(preguntar: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.LEER_TRAS_PREGUNTAR, preguntar).apply()
    }

    // ---- AUDIO: atenuar (bajar volumen) o pausar durante la lectura ----
    // true = bajar el volumen de la música mientras se habla (por defecto);
    // false = pausar la reproducción con AUDIOFOCUS_GAIN_TRANSIENT.
    val bajarVolumenEnLugarDePausar: Boolean
        get() = prefs.getBoolean(WalinkKeys.VOLUMEN_ATENUAR, true)

    suspend fun setBajarVolumenEnLugarDePausar(bajar: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.VOLUMEN_ATENUAR, bajar).apply()
    }

    // ---- RETRASO BLUETOOTH (milicés. de silencio previo, solo cuando la salida es BT) ----
    val retrasoBluetoothMs: Int
        get() = prefs.getInt(WalinkKeys.RETRASO_BLUETOOTH, 800)

    suspend fun setRetrasoBluetoothMs(milisegundos: Int) {
        prefs.edit().putInt(WalinkKeys.RETRASO_BLUETOOTH, milisegundos).apply()
    }

    // ---- LEER SIN COCHE (para pruebas, false por defecto) ----
    val leerSinCoche: Boolean
        get() = prefs.getBoolean(WalinkKeys.LEER_SIN_COCHE, false)

    suspend fun setLeerSinCoche(leer: Boolean) {
        prefs.edit().putBoolean(WalinkKeys.LEER_SIN_COCHE, leer).apply()
    }

    // ---- BORRADOR DE RESPUESTA POR VOZ (dictado tras leer un mensaje) ----
    val borradorRemitente: String?
        get() = prefs.getString(WalinkKeys.BORRADOR_REMITENTE, null)

    val borradorTexto: String?
        get() = prefs.getString(WalinkKeys.BORRADOR_TEXTO, null)

    val borradorTimestamp: Long?
        get() = prefs.getLong(WalinkKeys.BORRADOR_TIMESTAMP, 0).takeIf { it > 0 }

    fun guardarBorradorRespuesta(remitente: String, texto: String) {
        prefs.edit()
            .putString(WalinkKeys.BORRADOR_REMITENTE, remitente)
            .putString(WalinkKeys.BORRADOR_TEXTO, texto)
            .putLong(WalinkKeys.BORRADOR_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun borrarBorradorRespuesta() {
        prefs.edit()
            .remove(WalinkKeys.BORRADOR_REMITENTE)
            .remove(WalinkKeys.BORRADOR_TEXTO)
            .remove(WalinkKeys.BORRADOR_TIMESTAMP)
            .apply()
    }
}
