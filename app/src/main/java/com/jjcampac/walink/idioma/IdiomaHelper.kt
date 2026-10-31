package com.jjcampac.walink.idioma

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Gestión del idioma de la app.
 *
 * Reglas (ver README):
 *  - Se soportan 6 idiomas: español, inglés, alemán, italiano, francés y japonés.
 *  - El idioma por defecto es el del teléfono si coincide con uno de los soportados;
 *    en caso contrario (o si el elegido no está soportado) se usa español.
 *  - El usuario puede elegir idioma desde la MainActivity; al elegirlo se guarda en
 *    las preferencias y se recrea la Activity para aplicar el nuevo locale.
 *
 * Para aplicar el locale a todo el proceso sin añadir dependencias de appcompat,
 * se envuelve el contexto base en `attachBaseContext` (Application, MainActivity y
 * los servicios) con [contextoLocalizado], que re-crea la configuración del idioma
 * elegido ANTES de que el contexto construya sus Resources.
 *
 * @author jjcampac
 * @author cline
 */
object IdiomaHelper {

    /** Valor de la preferencia que significa "seguir el idioma del sistema". */
    const val SISTEMA = "system"

    /** Idioma que se usa como respaldo cuando el del sistema no está soportado. */
    private const val IDIOMA_RESPALDO = "es"

    /** Idiomas soportados: código ISO 639-1 -> nombre en su propio idioma. */
    val soportados: Map<String, String> = linkedMapOf(
        "es" to "Español",
        "en" to "English",
        "de" to "Deutsch",
        "it" to "Italiano",
        "fr" to "Français",
        "ja" to "日本語"
    )

    /**
     * Devuelve el [Locale] que debe usarse según la preferencia guardada.
     * - Si el usuario eligió un idioma soportado, se usa ese.
     * - Si la preferencia está vacía o es "system": se usa el idioma del dispositivo
     *   si coincide con uno soportado; si no, español.
     * - Si el código guardado no es soportado (p. ej. quedó un valor obsoleto),
     *   se usa español.
     */
    fun localeResuelto(contexto: Context): Locale {
        val guardado = WalinkDataStorePrefs.leer(contexto)
        val codigo = when {
            guardado.isNullOrEmpty() || guardado == SISTEMA ->
                if (Locale.getDefault().language in soportados.keys) Locale.getDefault().language
                else IDIOMA_RESPALDO
            guardado in soportados.keys -> guardado
            else -> IDIOMA_RESPALDO
        }
        return Locale(codigo)
    }

    /**
     * Envuelve [contexto] con la configuración (locale) del idioma elegido, de modo
     * que `getString`/`getResources` devuelvan los textos localizados. Llámese en
     * `attachBaseContext`.
     */
    fun contextoLocalizado(contexto: Context): Context {
        val locale = localeResuelto(contexto)
        Locale.setDefault(locale)
        val config = Configuration(contexto.resources.configuration)
        config.setLocale(locale)
        return contexto.createConfigurationContext(config)
    }

    /** Función de respaldo para leer la preferencia sin depender del singleton. */
    private object WalinkDataStorePrefs {
        fun leer(contexto: Context): String? =
            contexto.getSharedPreferences("walink_prefs", Context.MODE_PRIVATE)
                .getString("idioma_app", null)
    }
}