package com.jjcampac.walink.diag

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Registro de diagnóstico persistente en el almacenamiento interno de la app
 * (privado, sin red, sin permisos nuevos).
 *
 * Complementa a logcat. Un fallo real que solo ocurre de vez en cuando (p. ej.
 * estando en el coche) queda guardado en `filesDir/walink_diag.log` aunque
 * pasen horas o días, de modo que se puede consultar después con
 * `adb pull /data/data/com.jjcampac.walink/files/walink_diag.log` sin depender
 * de capturar logcat en vivo (que se rota y se vacía).
 *
 * El fichero está limitado (~512 KB) y conserva una única copia anterior.
 *
 * @author jjcampac
 * @author cline
 */
object LogDiag {

    private const val TAG = "WalinkDiag"
    private const val NOMBRE_FICHERO = "walink_diag.log"
    private const val NOMBRE_COPIA = "walink_diag_old.log"
    private const val MAX_BYTES = 512L * 1024L

    private var appContext: Context? = null
    private val ejecutor = Executors.newSingleThreadExecutor()
    private val formatoHora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Inicializa el contexto de la app. Idempotente y seguro llamarlo varias veces. */
    fun iniciar(contexto: Context) {
        if (appContext == null) {
            appContext = contexto.applicationContext
            escribir("I", "=== Walink: inicio de diagnóstico ===")
        }
    }

    fun d(mensaje: String) = escribir("D", mensaje)

    fun e(mensaje: String, error: Throwable? = null) {
        escribir("E", if (error == null) mensaje else "$mensaje -> $error")
    }

    /** Devuelve el contenido actual del fichero de diagnóstico (para verlo en la app). */
    fun contenido(contexto: Context): String {
        val fichero = File(contexto.applicationContext.filesDir, NOMBRE_FICHERO)
        return try {
            if (fichero.exists()) fichero.readText() else ""
        } catch (t: Throwable) {
            "Error al leer el diagnóstico: $t"
        }
    }

    /**
     * Foto del estado del teléfono en el momento exacto de llegar una notificación
     * (pantalla, no-molestar, batería y Bluetooth). Es justo lo que no conocemos del
     * fallo real: saber en qué estado estaba el móvil cuando se recibió el mensaje.
     */
    fun registrarEstado(contexto: Context, motivo: String) {
        val c = contexto.applicationContext

        val pantalla = try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) "ENCENDIDA" else "APAGADA"
        } catch (t: Throwable) {
            "desconocido"
        }

        val dnd = try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "permitido"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "silencio total"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "solo alarmas"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "solo prioritario"
                else -> "filtro ${nm.currentInterruptionFilter}"
            }
        } catch (t: Throwable) {
            "desconocido"
        }

        val bateria = try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(c.packageName)) {
                "excluida de optimización"
            } else {
                "NO excluida de optimización"
            }
        } catch (t: Throwable) {
            "desconocido"
        }

        val bluetooth = estadoBluetooth(c)

        val salidas = try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSortedSet().joinToString(",")
        } catch (t: Throwable) {
            "desconocido"
        }

        escribir(
            "I",
            "ESTADO[$motivo] pantalla=$pantalla | no-molestar=$dnd | bateria=$bateria | bluetooth=[$bluetooth] | salidas=[$salidas]"
        )
    }

    /** Estado de los perfiles Bluetooth A2DP y HANDSFREE (si el coche/auricular está conectado). */
    private fun estadoBluetooth(c: Context): String {
        return try {
            val gestor = c.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                ?: return "sin gestor BT"
            val adaptador = gestor.adapter ?: return "adaptador BT no disponible"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return "sin permiso BLUETOOTH_CONNECT"
            }
            val estadoDe = { perfil: Int ->
                when (adaptador.getProfileConnectionState(perfil)) {
                    BluetoothProfile.STATE_CONNECTED -> "OK"
                    BluetoothProfile.STATE_CONNECTING -> "conectando"
                    else -> "no"
                }
            }
            "a2dp=${estadoDe(BluetoothProfile.A2DP)}; headset=${estadoDe(BluetoothProfile.HEADSET)}"
        } catch (t: Throwable) {
            "error ${t.javaClass.simpleName}"
        }
    }

    private fun escribir(nivel: String, mensaje: String) {
        val contexto = appContext ?: return
        ejecutor.execute {
            try {
                val dir = contexto.filesDir
                val fichero = File(dir, NOMBRE_FICHERO)
                rotarSiCrece(File(dir, NOMBRE_COPIA), fichero)
                fichero.appendText("${formatoHora.format(Date())} $nivel/$TAG $mensaje\n")
                Log.d(TAG, mensaje)
            } catch (_: Throwable) {
                // Nunca debe romper el flujo por un problema del log.
            }
        }
    }

    /** Si el fichero supera el límite, conservamos una copia y empezamos uno nuevo. */
    private fun rotarSiCrece(copia: File, fichero: File) {
        if (fichero.length() > MAX_BYTES) {
            if (copia.exists()) copia.delete()
            fichero.renameTo(copia)
        }
    }
}