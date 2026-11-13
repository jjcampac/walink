package com.jjcampac.walink.notasvoz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.jjcampac.walink.diag.LogDiag
import java.io.File
import kotlin.math.abs

/**
 * Localiza en el almacenamiento el archivo de audio de una nota de voz de WhatsApp.
 *
 * La notificación de WhatsApp **no incluye el audio**: solo el texto que la describe
 * ("🎤 Audio (0:12)"). El archivo real lo guarda WhatsApp en el almacenamiento
 * compartido, con un subdirectorio por semana y un fichero por nota:
 *
 * ```
 * /storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/
 *     202646/PTT-20261112-WA0000.opus
 * ```
 *
 * (Comprobado en el Nothing Phone (3a): carpetas `202645`, `202646`… con ficheros
 * `PTT-AAAAMMDD-WA####.opus`, propietario `u0_a256:media_rw` y permisos `rw-rw----`,
 * accesibles a otras apps solo con permiso de audio.)
 *
 * Como WhatsApp no expone a qué chat pertenece cada fichero, se elige el `.opus`
 * modificado **más cerca de la marca de tiempo del mensaje**, dentro de una ventana
 * de seguridad. Para una nota recién recibida, ese es con diferencia el archivo más
 * reciente de la carpeta.
 *
 * @author jjcampac
 * @author cline
 */
object NotasVoz {

    private const val TAG = "WalinkNotasVoz"

    /** Prefijo de los archivos de nota de voz de WhatsApp ("PTT-20261112-WA0000.opus"). */
    private const val PREFIJO_FICHERO = "PTT-"

    /** Solo se miran las dos semanas más recientes (año+semana en el nombre). */
    private const val MAX_SEMANAS = 2

    /**
     * Ventana máxima entre la marca de tiempo del mensaje y la fecha de modificación
     * del archivo. Amplia a propósito: el fichero se escribe al descargarlo, y en
     * WhatsApp el mensaje puede quedar marcado unos segundos antes.
     */
    private const val VENTANA_MS = 15 * 60 * 1000L

    /** Paquetes de WhatsApp cuyos archivos se consultan. */
    private val PAQUETES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Resultado de la búsqueda.
     *
     * @param archivo fichero encontrado o null si no hay ninguno utilizable.
     * @param motivo explicación legible del fallo (para el diagnóstico).
     * @param candidatos número de ficheros que entraban en la ventana de tiempo.
     * @param edadMs diferencia entre la marca del mensaje y el fichero elegido.
     */
    data class Resultado(
        val archivo: File?,
        val motivo: String,
        val candidatos: Int = 0,
        val edadMs: Long = 0L
    )

    /** Candidato reducido a lo que necesita la lógica de selección (testeable). */
    data class Candidato(val archivo: File, val modificado: Long)

    /**
     * Permiso de audio necesario para leer los ficheros de otro paquete.
     * En Android 13+ es `READ_MEDIA_AUDIO`; antes, `READ_EXTERNAL_STORAGE`.
     */
    fun permisoRequerido(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** true si la app puede leer los audios del almacenamiento compartido. */
    fun permisoConcedido(contexto: Context): Boolean =
        ContextCompat.checkSelfPermission(contexto, permisoRequerido()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Busca el archivo de la nota de voz correspondiente a un mensaje.
     *
     * @param marcaTiempoMensaje marca de tiempo del mensaje según la notificación
     * (0 si WhatsApp no la expone: en ese caso se usa la hora actual).
     */
    fun localizar(contexto: Context, marcaTiempoMensaje: Long): Resultado {
        if (!permisoConcedido(contexto)) {
            return Resultado(null, "sin permiso de audio (${permisoRequerido()})")
        }

        val referencia =
            if (marcaTiempoMensaje > 0L) marcaTiempoMensaje else System.currentTimeMillis()

        val existentes = raices(contexto).filter { it.isDirectory }
        if (existentes.isEmpty()) {
            return Resultado(null, "no existe ninguna carpeta de notas de voz")
        }

        var totalFicheros = 0
        val candidatos = mutableListOf<Candidato>()
        for (raiz in existentes) {
            val semanas = raiz.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?.take(MAX_SEMANAS)
                ?: continue
            for (semana in semanas) {
                val ficheros = semana.listFiles { f ->
                    f.isFile && f.name.startsWith(PREFIJO_FICHERO)
                } ?: continue
                totalFicheros += ficheros.size
                ficheros.forEach { candidatos += Candidato(it, it.lastModified()) }
            }
        }

        if (totalFicheros == 0) {
            return Resultado(null, "la carpeta de notas de voz está vacía")
        }

        val elegido = seleccionar(candidatos, referencia, VENTANA_MS)
            ?: return Resultado(
                null,
                "ningún .opus dentro de la ventana de ${VENTANA_MS / 1000} s " +
                    "($totalFicheros ficheros revisados)",
                candidatos.size
            )

        val edad = abs(elegido.modificado - referencia)
        Log.d(TAG, "Nota de voz localizada: ${elegido.archivo.name} (diferencia=${edad} ms)")
        LogDiag.d(
            "Nota de voz localizada: ${elegido.archivo.name} " +
                "(diferencia=${edad / 1000} s, semana=${elegido.archivo.parentFile?.name})"
        )
        return Resultado(elegido.archivo, "", 1, edad)
    }

    /**
     * Elige el candidato con la fecha de modificación más cercana a [referencia],
     * descartando los que queden fuera de [ventanaMs]. Devuelve null si no queda
     * ninguno. Se aísla del acceso a ficheros para poder probarlo con JUnit.
     */
    fun seleccionar(
        candidatos: List<Candidato>,
        referencia: Long,
        ventanaMs: Long
    ): Candidato? = candidatos
        .filter { abs(it.modificado - referencia) <= ventanaMs }
        .minByOrNull { abs(it.modificado - referencia) }

    /**
     * Carpetas donde WhatsApp guarda las notas de voz, de la más moderna a la más
     * antigua:
     *  - `Android/media/<paquete>/WhatsApp/Media/WhatsApp Voice Notes` (Android 10+).
     *  - `<almacenamiento>/WhatsApp/Media/WhatsApp Voice Notes` (ubicación antigua).
     *
     * Se deducen a partir del directorio externo propio de la app para no depender de
     * `Environment.getExternalStorageDirectory()` (obsoleto) y para soportar tanto
     * almacenamiento interno como tarjeta SD.
     */
    fun raices(contexto: Context): List<File> {
        val almacenamiento = almacenamientoPrincipal(contexto)
        return PAQUETES.flatMap { paquete ->
            listOf(
                File(almacenamiento, "Android/media/$paquete/WhatsApp/Media/WhatsApp Voice Notes"),
                File(almacenamiento, "WhatsApp/Media/WhatsApp Voice Notes")
            )
        }
    }

    /**
     * Raíz del almacenamiento compartido (`/storage/emulated/0` o equivalente):
     * el directorio externo de la app es `<raíz>/Android/data/<paquete>/files`, así
     * que se suben cuatro niveles. Si no está disponible se usa el de Environment.
     */
    @Suppress("DEPRECATION")
    private fun almacenamientoPrincipal(contexto: Context): File {
        var raiz: File? = contexto.getExternalFilesDir(null)
        repeat(4) { raiz = raiz?.parentFile }
        return raiz ?: Environment.getExternalStorageDirectory()
    }
}
