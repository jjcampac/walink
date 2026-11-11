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

import org.junit.Assert.*
import org.junit.Test

/**
 * Pruebas unitarias para las reglas de filtrado y procesamiento de la Fase 4.
 */
class Fase4LogicTest {

    @Test
    fun filtroContactosBloqueados_identificaContactoCorrectamente() {
        val bloqueados = setOf("Spam", "Comercial", "Jefe Pesado")
        val remitente1 = "Comercial"
        val remitente2 = "Mamá"

        val estaBloqueado1 = bloqueados.any { contacto ->
            remitente1.equals(contacto, ignoreCase = true) ||
            remitente1.contains(contacto, ignoreCase = true)
        }
        val estaBloqueado2 = bloqueados.any { contacto ->
            remitente2.equals(contacto, ignoreCase = true) ||
            remitente2.contains(contacto, ignoreCase = true)
        }

        assertTrue(estaBloqueado1)
        assertFalse(estaBloqueado2)
    }

    @Test
    fun filtroContactosPermitidos_identificaContactoCorrectamente() {
        val permitidos = setOf("Mamá", "Papá", "Hermana")
        val remitentePermitido = "Mamá"
        val remitenteNoPermitido = "Desconocido"

        val estaPermitido1 = permitidos.any { contacto ->
            remitentePermitido.equals(contacto, ignoreCase = true) ||
            remitentePermitido.contains(contacto, ignoreCase = true)
        }
        val estaPermitido2 = permitidos.any { contacto ->
            remitenteNoPermitido.equals(contacto, ignoreCase = true) ||
            remitenteNoPermitido.contains(contacto, ignoreCase = true)
        }

        assertTrue(estaPermitido1)
        assertFalse(estaPermitido2)
    }

    @Test
    fun detectorNotasDeVoz_reconoceVariaciones() {
        fun esNotaDeVoz(texto: String): Boolean {
            val t = texto.lowercase()
            return t.contains("mensaje de voz") ||
                   t.contains("nota de voz") ||
                   t.contains("voice message") ||
                   t.contains("audio (") ||
                   t.startsWith("🎤")
        }

        assertTrue(esNotaDeVoz("🎤 Mensaje de voz (0:15)"))
        assertTrue(esNotaDeVoz("Nota de voz"))
        assertTrue(esNotaDeVoz("Voice message (0:45)"))
        assertTrue(esNotaDeVoz("Audio (0:03)"))
        assertFalse(esNotaDeVoz("Hola, ¿cómo estás?"))
    }
}
