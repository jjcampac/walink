package com.jjcampac.walink.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
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
 */
object BluetoothCoche {

    private const val TAG = "WalinkBluetooth"

    /** Dispositivo Bluetooth emparejado tal y como se muestra en la UI. */
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
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { info ->
                info.address.equals(direccionMac, ignoreCase = true) &&
                    esDispositivoBluetooth(info.type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando dispositivos de salida", e)
            false
        }
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
