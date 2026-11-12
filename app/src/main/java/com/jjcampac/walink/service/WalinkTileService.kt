package com.jjcampac.walink.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.jjcampac.walink.R
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.diag.LogDiag

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
        if (activado) {
            // La pulsación del mosaico NO es una ventana elegible para el permiso
            // "while-in-use" del micrófono: arrancar aquí el FGS de tipo "microphone"
            // se rechaza con SecurityException (comprobado en el móvil real). Si el
            // servicio ya está en marcha solo hay que reafirmarlo; si no, abrimos una
            // actividad transparente que sí es elegible, arranca el servicio y se cierra.
            if (MicrofonoPermanenteService.estaActivo) {
                MicrofonoPermanenteService.iniciar(this)
            } else {
                abrirVentanaElegible()
            }
        } else {
            MicrofonoPermanenteService.detener(this)
        }
        actualizarEstado()
    }

    /**
     * Abre [ArranqueRapidoActivity] (sin interfaz) para arrancar el micro permanente
     * desde una ventana elegible. Si no se puede abrir, deja el aviso accionable.
     */
    // Lint pide solo la variante con PendingIntent, pero esa API existe desde Android 14
    // y minSdk es 29: por debajo hay que usar la variante con Intent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun abrirVentanaElegible() {
        val intent = Intent(this, ArranqueRapidoActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            Log.d(TAG, "Abierta ventana elegible para arrancar el micro permanente")
        } catch (t: Throwable) {
            // Si el sistema lo impide, al menos queda el aviso de reactivación a un toque.
            Log.w(TAG, "No se pudo abrir la ventana elegible", t)
            LogDiag.e("Tile: no se pudo abrir ArranqueRapidoActivity", t)
            MicrofonoPermanenteService.avisarReactivacion(this)
        }
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
