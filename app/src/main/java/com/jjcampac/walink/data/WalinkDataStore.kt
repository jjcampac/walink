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
    const val COCHE_ACL_CONECTADOS = "coche_acl_conectados"
    const val BORRADOR_REMITENTE = "borrador_remitente"
    const val BORRADOR_TEXTO = "borrador_texto"
    const val BORRADOR_TIMESTAMP = "borrador_timestamp"
    const val IDIOMA_APP = "idioma_app"
}

/**
 * Acceso a preferencias persistentes con SharedPreferences.
 * Toda la información se guarda localmente en el dispositivo.
 *
 * @author jjcampac
 * @author cline
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

    // ---- DISPOSITIVOS BLUETOOTH (uno o varios coches) ---
    // Conjunto de direcciones MAC de los dispositivos Bluetooth que el usuario
    // ha elegido como "coche". Walink solo lee mensajes cuando ALGUNO de ellos
    // está conectado. Si el conjunto está vacío, lee siempre por el teléfono.
    private val DISPOSITIVOS_BLUETOOTH = "dispositivos_bluetooth"

    val dispositivosBluetooth: Set<String>
        get() {
            val guardados = prefs.getStringSet(DISPOSITIVOS_BLUETOOTH, emptySet()) ?: emptySet()
            if (guardados.isNotEmpty()) return guardados
            // Migración perezosa desde la clave antigua (una sola vez)
            val antiguo = prefs.getString(WalinkKeys.DISPOSITIVO_BLUETOOTH, null)
            if (antiguo?.isNotBlank() == true) {
                prefs.edit().putStringSet(DISPOSITIVOS_BLUETOOTH, setOf(antiguo!!)).apply()
                return setOf(antiguo!!)
            }
            return emptySet()
        }

    /** Guarda el conjunto completo de direcciones MAC de los coches seleccionados. */
    suspend fun setDispositivosBluetooth(direcciones: Set<String>) {
        prefs.edit().putStringSet(DISPOSITIVOS_BLUETOOTH, direcciones).apply()
    }

    /** Agrega una dirección MAC al conjunto de coches seleccionados. */
    suspend fun agregarDispositivoBluetooth(direccionMac: String) {
        val actual = dispositivosBluetooth.toMutableSet()
        actual.add(direccionMac)
        prefs.edit().putStringSet(DISPOSITIVOS_BLUETOOTH, actual).apply()
    }

    /** Quita una dirección MAC del conjunto de coches seleccionados. */
    suspend fun removerDispositivoBluetooth(direccionMac: String) {
        val actual = dispositivosBluetooth.toMutableSet()
        actual.remove(direccionMac)
        prefs.edit().putStringSet(DISPOSITIVOS_BLUETOOTH, actual).apply()
    }

    /** Limpia todos los dispositivos Bluetooth seleccionados. */
    suspend fun limpiarDispositivosBluetooth() {
        prefs.edit().remove(DISPOSITIVOS_BLUETOOTH).apply()
    }

    // Mantén esta propiedad para retrocompatibilidad con código que aún lea
    // un único dispositivo: devuelve el primero del conjunto o null.
    @Deprecated("Usar dispositivosBluetooth. Se mantiene solo para migración.")
    val dispositivoBluetooth: String?
        get() = dispositivosBluetooth.firstOrNull()

    // ---- CONEXIÓN BLUETOOTH POR ENLACE ACL (por dispositivo) ----
    // Conjunto de MACs de dispositivos Bluetooth actualmente conectados por enlace
    // ACL. Lo mantienen BluetoothAclReceiver y refrescarConectado. Al ser POR
    // dispositivo, cada uno es independiente: cambiar de coche no arrastra el estado
    // de otro dispositivo que pueda seguir conectado.
    val cochesConectadosAcl: Set<String>
        get() = prefs.getStringSet(WalinkKeys.COCHE_ACL_CONECTADOS, emptySet())
            ?: emptySet()

    fun setCocheConectadoAcl(direccionMac: String, conectado: Boolean) {
        val actual = cochesConectadosAcl.toMutableSet()
        if (conectado) {
            actual.add(direccionMac)
        } else {
            actual.remove(direccionMac)
        }
        prefs.edit().putStringSet(WalinkKeys.COCHE_ACL_CONECTADOS, actual).apply()
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

    // ---- IDIOMA DE LA APP ----
    // Código ISO 639-1 del idioma elegido ("es", "en", "de"...). Vacío ("")
    // significa "seguir el idioma del sistema" (comportamiento por defecto).
    val idiomaApp: String
        get() = prefs.getString(WalinkKeys.IDIOMA_APP, "") ?: ""

    fun setIdiomaApp(idioma: String) {
        prefs.edit().putString(WalinkKeys.IDIOMA_APP, idioma).apply()
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
