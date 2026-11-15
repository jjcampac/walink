package com.jjcampac.walink.notasvoz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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

    /**
     * Preferencias propias (no DataStore): la carpeta elegida con SAF es una
     * *credencial de acceso* que hay que leer de forma **síncrona** desde el listener de
     * notificaciones, y DataStore solo ofrece lectura asíncrona.
     */
    private const val PREFS = "notas_voz"
    private const val CLAVE_CARPETA = "carpeta_saf"

    /** Paquetes de WhatsApp cuyos archivos se consultan. */
    private val PAQUETES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Carpetas por semana: año (4 cifras) + semana del año (2 cifras), p. ej. `202646`. */
    private val REGEX_SEMANA = Regex("^\\d{6}$")

    /**
     * Audio localizado.
     *
     * Puede venir del sistema de ficheros ([ruta], con el permiso de "todos los
     * archivos") o de la carpeta elegida con SAF ([uri]); `MediaPlayer` admite los dos
     * casos, así que se conservan ambos y se usa el que esté disponible.
     *
     * @param nombre nombre del fichero (`PTT-20261112-WA0000.opus`).
     * @param modificado fecha de modificación del fichero, en milisegundos.
     */
    data class Archivo(
        val nombre: String,
        val modificado: Long,
        val ruta: String? = null,
        val uri: Uri? = null
    ) {
        /** Texto para el diagnóstico (ruta o URI). */
        val ubicacion: String get() = uri?.toString() ?: ruta ?: nombre
    }

    /**
     * Resultado de la búsqueda.
     *
     * @param archivo fichero encontrado o null si no hay ninguno utilizable.
     * @param motivo explicación legible del fallo (para el diagnóstico).
     * @param candidatos número de ficheros que entraban en la ventana de tiempo.
     * @param edadMs diferencia entre la marca del mensaje y el fichero elegido.
     * @param faltaAcceso true si no hay ninguna vía de acceso configurada (ni carpeta
     * elegida ni permiso de "todos los archivos"): el usuario debe configurarla.
     */
    data class Resultado(
        val archivo: Archivo?,
        val motivo: String,
        val candidatos: Int = 0,
        val edadMs: Long = 0L,
        val faltaAcceso: Boolean = false
    )

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
     * Hay dos formas de llegar a la carpeta de WhatsApp y se usa la que esté
     * disponible:
     *
     *  1. **SAF**: la carpeta que el usuario elige una vez con
     *     `ACTION_OPEN_DOCUMENT_TREE` (permiso persistente). Es la vía recomendada:
     *     no requiere ningún permiso global.
     *  2. **Acceso a todos los archivos** (`MANAGE_EXTERNAL_STORAGE`): alternativa
     *     cuando el selector no deja elegir la carpeta; se lee con el sistema de
     *     ficheros clásico.
     *
     * Con solo el permiso de audio `READ_MEDIA_AUDIO` **no** se puede entrar en
     * `Android/media/com.whatsapp`: comprobado en el Nothing Phone (3a), el sistema
     * deniega el acceso porque los `.opus` no se indexan en MediaStore.
     *
     * @param marcaTiempoMensaje marca de tiempo del mensaje según la notificación
     * (0 si WhatsApp no la expone: en ese caso se usa la hora actual).
     */
    fun localizar(contexto: Context, marcaTiempoMensaje: Long): Resultado {
        val referencia =
            if (marcaTiempoMensaje > 0L) marcaTiempoMensaje else System.currentTimeMillis()

        val arbol = carpetaElegida(contexto)
        val conFicheros = accesoTotal(contexto)
        if (arbol == null && !conFicheros) {
            val motivo = "sin acceso a la carpeta de notas de voz: hay que elegirla"
            Log.d(TAG, motivo)
            return Resultado(null, motivo, faltaAcceso = true)
        }

        val candidatos = mutableListOf<Archivo>()
        var detalle = ""
        if (conFicheros) {
            val (lista, resumen) = listarFicheros(contexto)
            candidatos += lista
            detalle += "ficheros($resumen)"
        }
        // Si el acceso clásico no devuelve nada (o no existe), se usa la carpeta SAF.
        if (candidatos.isEmpty() && arbol != null) {
            val lista = try {
                listarSaf(contexto, arbol)
            } catch (e: SecurityException) {
                LogDiag.e(
                    "Notas de voz: la carpeta elegida ya no es accesible " +
                        "(${e.javaClass.simpleName})"
                )
                olvidarCarpeta(contexto)
                return Resultado(
                    null,
                    "la carpeta elegida ya no es accesible; hay que volver a elegirla",
                    faltaAcceso = true
                )
            }
            candidatos += lista
            detalle += (if (detalle.isEmpty()) "" else ", ") + "saf(${lista.size} ficheros)"
        }

        if (candidatos.isEmpty()) {
            return Resultado(null, "la carpeta de notas de voz está vacía [$detalle]")
        }

        val elegido = seleccionar(candidatos, referencia, VENTANA_MS)
            ?: return Resultado(
                null,
                "ningún .opus dentro de la ventana de ${VENTANA_MS / 1000} s " +
                    "(${candidatos.size} ficheros revisados)",
                candidatos.size
            )

        val edad = abs(elegido.modificado - referencia)
        Log.d(TAG, "Nota de voz localizada: ${elegido.nombre} (diferencia=${edad} ms)")
        LogDiag.d(
            "Nota de voz localizada: ${elegido.nombre} " +
                "(diferencia=${edad / 1000} s, origen=" +
                (if (elegido.uri != null) "SAF" else "ficheros") + ")"
        )
        return Resultado(elegido, "", 1, edad)
    }

    /**
     * Elige el candidato con la fecha de modificación más cercana a [referencia],
     * descartando los que queden fuera de [ventanaMs]. Devuelve null si no queda
     * ninguno. Se aísla del acceso a ficheros para poder probarlo con JUnit.
     */
    fun seleccionar(
        candidatos: List<Archivo>,
        referencia: Long,
        ventanaMs: Long
    ): Archivo? = candidatos
        .filter { abs(it.modificado - referencia) <= ventanaMs }
        .minByOrNull { abs(it.modificado - referencia) }

    // ---------------------------------------------------------------------------
    // Acceso a la carpeta: SAF (recomendado) o "todos los archivos" (alternativa)
    // ---------------------------------------------------------------------------

    /**
     * true si la app tiene concedido el permiso de "todos los archivos"
     * (`MANAGE_EXTERNAL_STORAGE`), que sí permite leer con el sistema de ficheros las
     * carpetas de otras apps dentro de `Android/media`.
     *
     * En Android 10 y anteriores basta el permiso de almacenamiento clásico.
     */
    fun accesoTotal(contexto: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return permisoConcedido(contexto)
        return Environment.isExternalStorageManager()
    }

    /** true si hay alguna vía configurada para leer las notas de voz. */
    fun hayAcceso(contexto: Context): Boolean =
        accesoTotal(contexto) || carpetaElegida(contexto) != null

    /**
     * Carpeta elegida con SAF, o null si no hay ninguna o si su permiso ya no vale
     * (por ejemplo, tras restablecer los permisos de la app).
     */
    fun carpetaElegida(contexto: Context): Uri? {
        val guardada = prefs(contexto).getString(CLAVE_CARPETA, null) ?: return null
        val uri = try {
            Uri.parse(guardada)
        } catch (e: Exception) {
            Log.w(TAG, "La carpeta guardada no es una URI válida: $guardada")
            return null
        }
        val vigente = contexto.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!vigente) {
            Log.d(TAG, "La carpeta elegida ya no tiene permiso persistente: $uri")
            return null
        }
        return uri
    }

    /**
     * Guarda la carpeta elegida con SAF y conserva el permiso entre reinicios.
     * Devuelve true si el sistema ha concedido un permiso persistente.
     */
    fun guardarCarpeta(contexto: Context, uri: Uri): Boolean {
        val persistido = try {
            contexto.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (e: SecurityException) {
            LogDiag.e(
                "Notas de voz: el sistema no ha concedido permiso persistente " +
                    "(${e.javaClass.simpleName})"
            )
            false
        }
        prefs(contexto).edit().putString(CLAVE_CARPETA, uri.toString()).apply()
        LogDiag.d(
            "Notas de voz: carpeta elegida (${nombreCarpeta(uri)}, persistente=$persistido)"
        )
        return persistido
    }

    /** Olvida la carpeta elegida (permiso perdido o cambio de dispositivo). */
    fun olvidarCarpeta(contexto: Context) {
        prefs(contexto).edit().remove(CLAVE_CARPETA).apply()
    }

    /** Nombre legible de la carpeta elegida (`Android/media/com.whatsapp/...`). */
    fun nombreCarpeta(uri: Uri): String {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return uri.toString()
        }
        return id.substringAfterLast(':').ifBlank { id }
    }

    /**
     * Intent para que el usuario elija la carpeta de notas de voz con SAF, abriendo el
     * selector directamente en la carpeta de WhatsApp cuando el proveedor lo admite.
     */
    fun intentElegirCarpeta(paquete: String = PAQUETES.first()): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        val idDocumento = "primary:Android/media/$paquete/WhatsApp/Media/WhatsApp Voice Notes"
        val inicial = Uri.parse(
            "content://com.android.externalstorage.documents/document/" + Uri.encode(idDocumento)
        )
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, inicial)
        return intent
    }

    private fun prefs(contexto: Context) =
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------
    // Listado de ficheros
    // ---------------------------------------------------------------------------

    /**
     * Listado con el sistema de ficheros. Solo funciona con el permiso de "todos los
     * archivos" (o en Android 10 y anteriores con el permiso de almacenamiento). Si el
     * sistema bloquea el acceso, `listFiles` devuelve null y la lista queda vacía.
     */
    private fun listarFicheros(contexto: Context): Pair<List<Archivo>, String> {
        val raices = raices(contexto).filter { it.isDirectory }
        if (raices.isEmpty()) return emptyList<Archivo>() to "sin carpeta de WhatsApp"

        val salida = mutableListOf<Archivo>()
        var semanas = 0
        for (raiz in raices) {
            val carpetas = raiz.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?.take(MAX_SEMANAS)
                ?: continue
            for (semana in carpetas) {
                semanas++
                val ficheros = semana.listFiles { f ->
                    f.isFile && f.name.startsWith(PREFIJO_FICHERO)
                } ?: continue
                ficheros.forEach {
                    salida += Archivo(
                        nombre = it.name,
                        modificado = it.lastModified(),
                        ruta = it.absolutePath
                    )
                }
            }
        }
        return salida to "raices=${raices.size}, semanas=$semanas, ${salida.size} ficheros"
    }

    /** Listado a través de la carpeta que el usuario eligió con SAF. */
    private fun listarSaf(contexto: Context, arbol: Uri): List<Archivo> {
        val idRaiz = DocumentsContract.getTreeDocumentId(arbol)
        val semanas = hijosDe(contexto, arbol, idRaiz)
            .filter { it.esCarpeta && REGEX_SEMANA.matches(it.nombre) }
            .sortedByDescending { it.nombre }
            .take(MAX_SEMANAS)

        val salida = mutableListOf<Archivo>()
        for (semana in semanas) {
            hijosDe(contexto, arbol, semana.documentId)
                .filter { !it.esCarpeta && it.nombre.startsWith(PREFIJO_FICHERO) }
                .forEach {
                    salida += Archivo(
                        nombre = it.nombre,
                        modificado = it.modificado,
                        uri = DocumentsContract.buildDocumentUriUsingTree(arbol, it.documentId)
                    )
                }
        }
        return salida
    }

    /** Entrada de un directorio consultado a través de SAF. */
    private data class EntradaSaf(
        val documentId: String,
        val nombre: String,
        val esCarpeta: Boolean,
        val modificado: Long
    )

    /** Hijos de un documento de la carpeta SAF elegida. */
    private fun hijosDe(contexto: Context, arbol: Uri, documentId: String): List<EntradaSaf> {
        val uriHijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, documentId)
        val proyeccion = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val salida = mutableListOf<EntradaSaf>()
        contexto.contentResolver.query(uriHijos, proyeccion, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                salida += EntradaSaf(
                    documentId = cursor.getString(0),
                    nombre = cursor.getString(1) ?: "",
                    esCarpeta = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    modificado = cursor.getLong(3)
                )
            }
        }
        return salida
    }

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
