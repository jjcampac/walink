package com.jjcampac.walink

import com.jjcampac.walink.speech.RespuestaCorta
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detección de respuestas cortas inequívocas, que se envían sin pedir confirmación.
 *
 * La regla es una lista blanca: solo pasan las confirmaciones reconocidas. Cuanto más
 * estricta, menos riesgo de enviar una frase mal transcrita.
 *
 * @author jjcampac
 * @author cline
 */
class RespuestaCortaTest {

    @Test
    fun `acepta confirmaciones de una palabra`() {
        assertTrue(RespuestaCorta.esCorta("vale"))
        assertTrue(RespuestaCorta.esCorta("Ok"))
        assertTrue(RespuestaCorta.esCorta("Sí"))
        assertTrue(RespuestaCorta.esCorta("gracias"))
    }

    @Test
    fun `acepta frases habituales completas`() {
        assertTrue(RespuestaCorta.esCorta("de acuerdo"))
        assertTrue(RespuestaCorta.esCorta("ahora te llamo"))
        assertTrue(RespuestaCorta.esCorta("hasta luego"))
        assertTrue(RespuestaCorta.esCorta("muchas gracias"))
    }

    @Test
    fun `ignora acentos mayusculas y puntuacion`() {
        assertTrue(RespuestaCorta.esCorta("¡VALE!"))
        assertTrue(RespuestaCorta.esCorta("Sí, claro."))
        assertTrue(RespuestaCorta.esCorta("  ok  "))
    }

    @Test
    fun `combina palabras sueltas de la lista hasta el limite`() {
        assertTrue(RespuestaCorta.esCorta("vale gracias"))
        assertTrue(RespuestaCorta.esCorta("si claro"))
        assertTrue(RespuestaCorta.esCorta("no gracias"))
    }

    @Test
    fun `rechaza frases con contenido propio`() {
        assertFalse(RespuestaCorta.esCorta("No puedo ir mañana"))
        assertFalse(RespuestaCorta.esCorta("Voy a llegar tarde a la reunión"))
        assertFalse(RespuestaCorta.esCorta("¿Dónde estás?"))
        assertFalse(RespuestaCorta.esCorta("Llámame cuando puedas"))
    }

    @Test
    fun `rechaza textos largos aunque empiecen por una confirmacion`() {
        assertFalse(
            RespuestaCorta.esCorta("vale pero al final resulta que no voy a poder llegar")
        )
    }

    @Test
    fun `rechaza texto vacio`() {
        assertFalse(RespuestaCorta.esCorta(""))
        assertFalse(RespuestaCorta.esCorta("   "))
        assertFalse(RespuestaCorta.esCorta("..."))
    }

    @Test
    fun `normaliza para comparar`() {
        assertTrue(RespuestaCorta.normalizar("  ¡HÓLA,  qué  tal! ") == "hola que tal")
    }
}
