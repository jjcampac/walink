package com.jjcampac.walink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.util.Log
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.speech.LectorAsistenteService

/**
 * BroadcastReceiver que detecta cuándo el teléfono se conecta o desconecta
 * de un dispositivo de audio (p. ej. Bluetooth del coche).
 *
 * Cuando se conecta audio y el modo "preguntar antes de leer" está activo,
 * arrancamos el [LectorAsistenteService] en primer plano para poder escuchar
 * respuestas por voz mientras conducimos.
 *
 * Cuando se desconecta, detenemos el servicio.
 */
class BluetoothAudioReceiver : BroadcastReceiver() {

    private val TAG = "BluetoothAudioReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let { ctx ->
            if (intent == null) return

            val action = intent.action

            when (action) {
                "android.media.AudioDevice_ADD" -> {
                    Log.d(TAG, "Dispositivo de audio conectado (Bluetooth del coche)")
                    // Solo arrancamos si el modo "preguntar" está activado.
                    val dataStore = WalinkDataStore.getInstance(ctx)
                    if (dataStore.leerTrasPreguntar) {
                        ctx.startForegroundService(
                            Intent(ctx, LectorAsistenteService::class.java)
                        )
                    }
                }
                "android.media.AudioDevice_REMOVE" -> {
                    Log.d(TAG, "Dispositivo de audio desconectado")
                    // Detenemos el servicio cuando el Bluetooth del coche se apaga.
                    ctx.stopService(
                        Intent(ctx, LectorAsistenteService::class.java)
                    )
                }
                else -> Log.d(TAG, "Acción de Bluetooth desconocida: $action")
            }
        }
    }
}