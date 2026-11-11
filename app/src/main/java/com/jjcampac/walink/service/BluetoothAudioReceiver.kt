/*
 * Copyright 2026 jjcampac
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 *
 * @author jjcampac
 * @author cline
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
