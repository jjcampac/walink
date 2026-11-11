package com.jjcampac.walink.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jjcampac.walink.data.WalinkDataStore

/**
 * Observa la conexión/desconexión por enlace Bluetooth (ACL) del dispositivo que
 * el usuario tiene seleccionado como coche y guarda ese estado en el datastore.
 *
 * A diferencia de comprobar los dispositivos de audio "en uso" (que solo se
 * informan mientras hay audio reproduciéndose), el enlace ACL se abre/cierra con
 * cualquier perfil conectado (A2DP, manos libres, etc.). Por eso representa "de
 * verdad" que el coche está presente y conectado, aunque no esté sonando nada.
 *
 * @author cline
 */
class BluetoothAclReceiver : BroadcastReceiver() {

    private val TAG = "BluetoothAclReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        val action = intent?.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        try {
            val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val dataStore = WalinkDataStore.getInstance(ctx)
            val conectado = action == BluetoothDevice.ACTION_ACL_CONNECTED
            val mac = device.address
            if (mac.isBlank()) return
            // Registramos POR dispositivo (MAC), no solo del "coche" configurado:
            // así cada dispositivo tiene su propio estado y cambiar de coche no
            // arrastra el estado de otro que siga conectado.
            dataStore.setCocheConectadoAcl(mac, conectado)
            BluetoothCoche.notificarConexion(mac, conectado)
            Log.d(TAG, "ACL del dispositivo '$mac' ${if (conectado) "conectado" else "desconectado"}")
        } catch (e: Exception) {
            Log.w(TAG, "Error procesando evento ACL de Bluetooth", e)
        }
    }
}