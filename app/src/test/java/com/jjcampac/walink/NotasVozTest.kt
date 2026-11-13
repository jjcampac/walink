package com.jjcampac.walink

import com.jjcampac.walink.notasvoz.NotasVoz
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lógica de selección del archivo de una nota de voz de WhatsApp.
 *
 * La notificación solo trae el texto ("🎤 Audio (0:12)"); el audio se busca en la
 * carpeta de notas de voz y hay que quedarse con el fichero correcto. Estas pruebas
 * fijan la regla: dentro de la ventana de tiempo, el `.opus` modificado más cerca de
 * la marca de tiempo del mensaje.
 *
 * @author jjcampac
 * @author cline
 */
class NotasVozTest {

    private val ventana = 15 * 60 * 1000L

    private fun candidato(nombre: String, modificado: Long) =
        NotasVoz.Candidato(File("/almacenamiento/$nombre"), modificado)

    @Test
    fun `elige el fichero mas cercano a la marca del mensaje`() {
        val referencia = 1_000_000L
        val elegido = NotasVoz.seleccionar(
            listOf(
                candidato("PTT-1.opus", referencia - 300_000L),
                candidato("PTT-2.opus", referencia - 2_000L),
                candidato("PTT-3.opus", referencia + 90_000L)
            ),
            referencia,
            ventana
        )
        assertEquals("PTT-2.opus", elegido?.archivo?.name)
    }

    @Test
    fun `prefiere el mensaje posterior aunque haya uno anterior mas antiguo`() {
        val referencia = 5_000_000L
        val elegido = NotasVoz.seleccionar(
            listOf(
                candidato("PTT-antes.opus", referencia - 15_000L),
                candidato("PTT-despues.opus", referencia + 4_000L)
            ),
            referencia,
            ventana
        )
        assertEquals("PTT-despues.opus", elegido?.archivo?.name)
    }

    @Test
    fun `descarta los ficheros fuera de la ventana`() {
        val referencia = 2_000_000L
        val elegido = NotasVoz.seleccionar(
            listOf(
                candidato("PTT-vieja.opus", referencia - ventana - 1),
                candidato("PTT-otra.opus", referencia - 86_400_000L)
            ),
            referencia,
            ventana
        )
        assertNull(elegido)
    }

    @Test
    fun `sin candidatos no hay nota de voz`() {
        assertNull(NotasVoz.seleccionar(emptyList(), 1_000_000L, ventana))
    }

    @Test
    fun `la cercania se mide en valor absoluto`() {
        val referencia = 9_000_000L
        val elegido = NotasVoz.seleccionar(
            listOf(candidato("PTT-justo.opus", referencia - 1_000L)),
            referencia,
            ventana
        )
        assertEquals("PTT-justo.opus", elegido?.archivo?.name)
    }
}
