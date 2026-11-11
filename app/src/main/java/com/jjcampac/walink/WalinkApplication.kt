package com.jjcampac.walink

import android.app.Application
import android.content.Context
import com.jjcampac.walink.idioma.IdiomaHelper
import com.jjcampac.walink.service.BluetoothCoche

/**
 * Application de Walink. Única función extra: aplicar el idioma elegido a todo el
 * proceso envolviendo el contexto base, de modo que los servicios y componentes que
 * usan `applicationContext.getString(...)` devuelvan textos localizados.
 *
 * @author jjcampac
 * @author cline
 */
class WalinkApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(IdiomaHelper.contextoLocalizado(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Observa la conexión del coche por enlace ACL (para saber si está conectado
        // aunque no esté sonando audio) y recupera el estado si arrancamos tarde.
        BluetoothCoche.registrarObservadorConexion(this)
        BluetoothCoche.refrescarConectado(this)
    }
}