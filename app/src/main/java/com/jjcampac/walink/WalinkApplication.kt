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