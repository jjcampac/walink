package com.jjcampac.walink.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import com.jjcampac.walink.data.WalinkDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Utilidades para trabajar con el Bluetooth del teléfono: listar los
 * dispositivos emparejados y comprobar si un dispositivo concreto está
 * conectado en este momento.
 *
 * Se usa para el ajuste "Dispositivo Bluetooth del coche": si el usuario elige
 * un dispositivo, Walink solo lee mensajes cuando ese dispositivo está
 * conectado (ver [WhatsAppNotificationListener]).
 *
 * Nota de permisos: desde Android 12 (API 31) listar los dispositivos
 * emparejados requiere el permiso runtime BLUETOOTH_CONNECT (ya declarado en
 * el manifest y solicitado en el onboarding). Sin permiso devolvemos una lista
 * vacía. La comprobación de "conectado" usa AudioManager (estado de audio), que
 * no requiere ningún permiso adicional.
 *
 * @author jjcampac
 * @author cline
 */
object BluetoothCoche {

    private const val TAG = "WalinkBluetooth"
    // Evita registrar el observador de conexión ACL más de una vez por proceso.
    @Volatile
    private var observadorRegistrado = false

    // Estado reactivo (por dispositivo) de conexión Bluetooth para la UI: conjunto de
    // MACs actualmente conectadas por enlace ACL. Lo actualizan el observador ACL y
    // refrescarConectado; la lógica (NotificationListener) usa el valor persistente
    // del datastore, y la UI se suscribe a este flow para reaccionar en vivo.
    private val _macConectadosAcl = MutableStateFlow<Set<String>>(emptySet())
    val estadoConectado: StateFlow<Set<String>> = _macConectadosAcl.asStateFlow()

    /** Notifica a la UI que cambió la conexión ACL de [direccionMac]. */
    fun notificarConexion(direccionMac: String, conectado: Boolean) {
        val actual = _macConectadosAcl.value.toMutableSet()
        if (conectado) {
            actual.add(direccionMac)
        } else {
            actual.remove(direccionMac)
        }
        _macConectadosAcl.value = actual
    }

    /**
     * Dispositivo Bluetooth emparejado tal y como se muestra en la UI.
     *
     * @author jjcampac
     * @author cline
     */
    data class Dispositivo(val nombre: String, val direccion: String)

    /** true si el sistema nos deja consultar dispositivos Bluetooth. */
    private fun tienePermiso(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun obtenerAdapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (e: Exception) {
        Log.e(TAG, "Error obteniendo el adaptador Bluetooth", e)
        null
    }

    /**
     * Lista los dispositivos Bluetooth emparejados con el teléfono, ordenados
     * por nombre. Devuelve una lista vacía si no hay permiso o si el teléfono
     * no tiene Bluetooth.
     */
    fun obtenerDispositivosEmparejados(context: Context): List<Dispositivo> {
        if (!tienePermiso(context)) return emptyList()
        val adapter = obtenerAdapter() ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            adapter.bondedDevices
                .map { Dispositivo(nombre = it.name ?: "Sin nombre", direccion = it.address) }
                .sortedBy { it.nombre.lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "Error listando dispositivos emparejados", e)
            emptyList()
        }
    }

    /**
     * true si el dispositivo con esa dirección MAC está conectado AHORA mismo.
     *
     * Lo comprobamos con el estado de audio actual (AudioManager): si el coche
     * está emparejado y conectado por A2DP/SCO, aparece como dispositivo de
     * salida con su dirección MAC. Es síncrono, sin permisos adicionales y sin
     * APIs deprecadas. Si no se puede comprobar, devolvemos false: con un coche
     * seleccionado es la opción segura, porque el usuario no quiere oír por el
     * teléfono.
     */
    fun estaConectado(context: Context, direccionMac: String): Boolean {
        if (direccionMac.isBlank()) return false
        // Señal 1: el coche aparece AHORA como dispositivo de audio Bluetooth en uso.
        // Nota: A2DP solo se informa mientras hay audio reproduciéndose, por lo que
        // esta comprobación por sí sola NO basta (es justo el problema que se corrige).
        val porAudio = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { info ->
                info.address.equals(direccionMac, ignoreCase = true) &&
                    esDispositivoBluetooth(info.type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando dispositivos de salida", e)
            false
        }
        if (porAudio) return true
        // Señal 2: el MAC está en el conjunto de dispositivos conectados por enlace
        // ACL (mantenido por BluetoothAclReceiver / refrescarConectado). Es POR
        // dispositivo, así que al cambiar de coche solo importa el estado del elegido.
        return WalinkDataStore.getInstance(context).cochesConectadosAcl
            .any { it.equals(direccionMac, ignoreCase = true) }
    }

    /**
     * Registra (una sola vez por proceso) un observador de la conexión/desconexión
     * Bluetooth por enlace ACL del dispositivo del coche. Se llama desde la
     * Application al arrancar; los eventos ACL se reciben aunque la app esté en
     * segundo plano, mientras el proceso esté vivo.
     */
    fun registrarObservadorConexion(context: Context) {
        if (observadorRegistrado) return
        val receptor = BluetoothAclReceiver()
        val filtro = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receptor, filtro)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el observador ACL", e)
            return
        }
        observadorRegistrado = true
        Log.d(TAG, "Observador de conexión ACL registrado")
    }

    /**
     * Recupera el estado real de conexión del coche consultando los perfiles A2DP
     * y manos libres. Se usa al arrancar: si el proceso estaba cerrado cuando el
     * coche se conectó (se perdió el evento ACL), así no dejamos el estado en
     * "desconectado" de forma errónea.
     */
    fun refrescarConectado(context: Context) {
        if (!tienePermiso(context)) return
        val dataStore = WalinkDataStore.getInstance(context)
        val coche = dataStore.dispositivoBluetooth ?: return
        if (coche.isBlank()) return
        val adaptador = obtenerAdapter() ?: return
        Log.d(TAG, "Refrescando estado de conexión del coche por perfil")
        validarEstadoPerfil(context, adaptador, coche, dataStore, BluetoothProfile.A2DP)
        validarEstadoPerfil(context, adaptador, coche, dataStore, BluetoothProfile.HEADSET)
    }

    private fun validarEstadoPerfil(
        context: Context,
        adaptador: BluetoothAdapter,
        coche: String,
        dataStore: WalinkDataStore,
        perfil: Int
    ) {
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                try {
                    val dispositivo = adaptador.getRemoteDevice(coche)
                    val conectado = proxy?.getConnectionState(dispositivo) == BluetoothProfile.STATE_CONNECTED
                    // Solo confirmamos la conexión (nunca la desconexión aquí): la
                    // desconexión la comunica el observador ACL. Así, si el coche va
                    // conectado por A2DP pero no por manos libres (o al revés), no
                    // sobrescribimos un perfil conectado con el otro.
                    if (conectado) {
                        dataStore.setCocheConectadoAcl(coche, true)
                        notificarConexion(coche, true)
                    }
                    Log.d(TAG, "Perfil $perfil del coche: ${if (conectado) "conectado" else "desconectado"}")
                    adaptador.closeProfileProxy(profile, proxy)
                } catch (e: Exception) {
                    Log.w(TAG, "Error comprobando perfil $perfil del coche", e)
                }
            }

            override fun onServiceDisconnected(profile: Int) { /* noop */ }
        }
        adaptador.getProfileProxy(context, listener, perfil)
    }

    /** true si el tipo de dispositivo de audio es Bluetooth (A2DP o manos libres). */
    private fun esDispositivoBluetooth(tipo: Int): Boolean =
        tipo == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            tipo == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /**
     * Nombre legible de un dispositivo por su dirección MAC entre los
     * emparejados (o null si ya no está emparejado).
     */
    fun nombreDe(context: Context, direccionMac: String): String? =
        obtenerDispositivosEmparejados(context)
            .firstOrNull { it.direccion.equals(direccionMac, ignoreCase = true) }
            ?.nombre
}
