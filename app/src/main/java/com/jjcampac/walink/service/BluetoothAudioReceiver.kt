package com.jjcampac.walink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor que observa cuándo el teléfono se conecta o desconecta de un
 * dispositivo de audio (p. ej. Bluetooth del coche).
 *
 * En esta versión NO arranca ni detiene ningún servicio: la lectura y el modo
 * "preguntar antes de leer" se orquestan por completo desde
 * [WhatsAppNotificationListener] + [LectorAsistenteService], que se activan al
 * llegar un mensaje. Este receptor queda como observador de la conectividad de
 * audio para futuros ajustes (p. ej. selección del dispositivo del coche o
 * retraso de Bluetooth).
 */
class BluetoothAudioReceiver : BroadcastReceiver() {

    private val TAG = "BluetoothAudioReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "android.media.AudioDevice_ADD" ->
                Log.d(TAG, "Dispositivo de audio conectado (Bluetooth del coche)")
            "android.media.AudioDevice_REMOVE" ->
                Log.d(TAG, "Dispositivo de audio desconectado")
            else -> Log.d(TAG, "Acción de audio desconocida: ${intent?.action}")
        }
    }
}
