package com.jjcampac.walink.speech

import java.text.Normalizer

/**
 * Decide si un texto dictado es una respuesta corta e inequívoca, que se puede enviar
 * directamente sin repetirla y pedir confirmación.
 *
 * Conduciendo, confirmar cada respuesta supone dos interacciones más; para respuestas
 * del tipo "vale" o "de acuerdo" es desproporcionado. Aquí se decide con una **lista
 * blanca**: nunca se envía sin confirmar algo que no esté reconocido de antemano, de
 * modo que una frase mal transcrita no se manda sola.
 *
 * Criterio:
 *  - Hasta [MAX_PALABRAS] palabras (se ignoran acentos, mayúsculas y puntuación).
 *  - La frase completa está en [FRASES] o todas sus palabras en [PALABRAS].
 *
 * @author jjcampac
 * @author cline
 */
object RespuestaCorta {

    /** Tope de palabras para considerar la respuesta "corta". */
    private const val MAX_PALABRAS = 4

    /**
     * Frases completas que se envían sin confirmar. Se comparan ya normalizadas
     * (minúsculas, sin acentos ni puntuación).
     */
    private val FRASES = setOf(
        // Español
        "de acuerdo", "de nada", "muchas gracias", "ahora te llamo", "te llamo luego",
        "te llamo ahora", "hasta luego", "nos vemos", "no puedo", "estoy de camino",
        "voy para alla", "enseguida voy", "ya voy", "vale gracias", "si claro",
        // Inglés
        "thank you", "see you", "i am on my way", "on my way", "call you later",
        "will call you", "talk later", "no problem", "of course",
        // Alemán
        "bis später", "danke schön", "ich rufe dich an", "rufe dich später an",
        // Francés
        "merci beaucoup", "à plus tard", "je t appelle", "je t appelle plus tard",
        "pas de problème", "d accord",
        // Italiano
        "grazie mille", "a dopo", "ti chiamo dopo", "va bene",
        // Japonés
        "ありがとう", "またね", "あとで連絡します"
    )

    /**
     * Palabras que por sí solas (o combinadas hasta [MAX_PALABRAS]) no cambian el
     * sentido de una respuesta breve.
     */
    private val PALABRAS = setOf(
        // Español
        "vale", "ok", "okey", "okay", "si", "sí", "no", "claro", "perfecto", "genial",
        "gracias", "hola", "buenas", "estupendo", "hecho", "venga", "dale", "fenomenal",
        // Inglés
        "yes", "sure", "thanks", "great", "perfect", "fine", "hello", "hi",
        // Alemán
        "ja", "nein", "danke", "gut", "perfekt", "hallo",
        // Francés
        "oui", "non", "merci", "parfait", "salut",
        // Italiano
        "va", "bene", "perfetto", "ciao",
        // Japonés
        "はい", "いいえ", "わかりました", "こんにちは"
    )

    /**
     * true si [texto] es una respuesta corta reconocida y se puede enviar sin
     * confirmación.
     */
    fun esCorta(texto: String): Boolean {
        val normalizado = normalizar(texto)
        if (normalizado.isBlank()) return false

        val palabras = normalizado.split(' ').filter { it.isNotBlank() }
        if (palabras.isEmpty() || palabras.size > MAX_PALABRAS) return false

        if (FRASES.contains(normalizado)) return true
        return palabras.all { PALABRAS.contains(it) }
    }

    /**
     * Pasa el texto a minúsculas, sin acentos, sin puntuación y con espacios simples,
     * para que "¡Vale!" y "vale" cuenten igual. Se conservan las letras no latinas
     * (japonés) tal cual.
     */
    fun normalizar(texto: String): String {
        val sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        return sinAcentos
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
