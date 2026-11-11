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

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore

/**
 * Mosaico de accesos rápidos (Quick Settings Tile) de Walink.
 *
 * - Pulsar el mosaico: activa/desactiva Walink (lectura de mensajes).
 * - Pulsación larga: abre la MainActivity (declarada con ACTION_QS_TILE_PREFERENCES
 *   en el manifest), para acceder al simulador y futuros ajustes.
 *
 * @author jjcampac
 * @author cline
 */
class WalinkTileService : TileService() {

    private val dataStore by lazy { WalinkDataStore.getInstance(applicationContext) }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile añadido por el usuario")
        actualizarEstado()
    }

    override fun onStartListening() {
        super.onStartListening()
        // El tile se hace visible: sincronizamos su estado con la preferencia.
        actualizarEstado()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Toggle de activación.
        val activado = !dataStore.estaActivado
        dataStore.setActivado(activado)
        Log.d(TAG, "Toggle desde tile -> activado=$activado")
        // Al activar Walink, arrancamos en primer plano el servicio de micro permanente
        // (aún tenemos la ventana elegible de la pulsación del tile). Al desactivar, se
        // detiene.
        if (activado) {
            MicrofonoPermanenteService.iniciar(this)
        } else {
            MicrofonoPermanenteService.detener(this)
        }
        actualizarEstado()
    }

    /**
     * Sincroniza el icono, estado y texto del tile con la preferencia actual.
     */
    private fun actualizarEstado() {
        val tile = qsTile ?: return
        val activado = dataStore.estaActivado

        tile.state = if (activado) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            if (activado) R.string.tile_descripcion_activo else R.string.tile_descripcion_inactivo
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_walink)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "WalinkTile"
    }
}
