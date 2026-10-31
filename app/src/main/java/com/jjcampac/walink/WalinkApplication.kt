package com.jjcampac.walink

import android.app.Application
import android.content.Context
import com.jjcampac.walink.idioma.IdiomaHelper

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
}