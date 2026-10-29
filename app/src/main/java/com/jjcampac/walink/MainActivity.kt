package com.jjcampac.walink

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jjcampac.walink.BuildConfig
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.debug.DebugSimulator
import com.jjcampac.walink.service.WhatsAppNotificationListener
import com.jjcampac.walink.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val dataStore by lazy { WalinkDataStore.getInstance(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Escucha cambios en SharedPreferences para reflejar en tiempo real el estado
    // "activado" cuando se alterna desde el mosaico de accesos rápidos (el panel de
    // Quick Settings no saca la Activity del foreground, por lo que onResume no se
    // dispara de nuevo y sin este listener la pantalla quedaría desactualizada).
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACTIVADO) {
                servicioActivado = dataStore.estaActivado
            }
        }

    // Launchers de permisos runtime. Cada uno encadena automáticamente el siguiente
    // permiso pendiente durante el onboarding (con un pequeño retardo para que el
    // sistema esté listo tras el diálogo anterior).
    private val solicitarNotificacionesPost =
        registerForActivityResult(RequestMultiplePermissions()) { concedidos ->
            permisoPostNotificaciones = concedidos.values.contains(true)
            avanzarOnboarding()
        }

    private val solicitarMicrofono =
        registerForActivityResult(RequestPermission()) { concedido ->
            permisoMicrofono = concedido
            avanzarOnboarding()
        }

    private val solicitarBluetooth =
        registerForActivityResult(RequestMultiplePermissions()) { concedidos ->
            permisoBluetooth = concedidos.values.contains(true)
            avanzarOnboarding()
        }

    // ---- Estados de la UI ----
    private var permisoNotificaciones by mutableStateOf(false)     // acceso especial del listener
    private var permisoPostNotificaciones by mutableStateOf(false)  // POST_NOTIFICATIONS (API 33+)
    private var permisoMicrofono by mutableStateOf(false)
    private var permisoBluetooth by mutableStateOf(false)
    private var bateriaExcluida by mutableStateOf(false)
    private var servicioActivado by mutableStateOf(false)
    private var mostrarOnboarding by mutableStateOf(false)

    // ---- Selección de voz TTS ----
    // TTS "explorador" usado únicamente para listar las voces disponibles del motor.
    private var vozTtsSelector: TextToSpeech? = null
    private var vocesDisponibles by mutableStateOf<List<VozTts>>(emptyList())
    private var vozActual: String? by mutableStateOf(null)

    // ---- Modo de lectura ----
    private var leerTrasPreguntar by mutableStateOf(false)

    // ---- Audio de fondo durante la lectura ----
    // true = bajar el volumen de la música (MAY_DUCK); false = pausarla (GAIN_TRANSIENT).
    private var audioBajaVolumen by mutableStateOf(true)

    /**
     * Comprueba si Walink tiene concedido el "Acceso a notificaciones" del
     * NotificationListenerService (ajuste especial de Android).
     *
     * El sistema guarda los listeners habilitados en Settings.Secure
     * ("enabled_notification_listeners") como una lista ":" separada de
     * pares "paquete/clase_completa".
     */
    private fun tieneAccesoListenerNotificaciones(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val componenteEsperado = ComponentName(this, WhatsAppNotificationListener::class.java)

        return enabled.split(":").any { entrada ->
            try {
                ComponentName.unflattenFromString(entrada.trim()) == componenteEsperado
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * true si queda algún permiso runtime pendiente durante el onboarding.
     */
    private fun hayPermisosRuntimePendientes(): Boolean {
        val postPendiente = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permisoPostNotificaciones
        return postPendiente || !permisoMicrofono || !permisoBluetooth
    }

    /**
     * Pide el siguiente permiso runtime pendiente (en el orden del onboarding).
     */
    private fun pedirSiguientePermisoOnboarding() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permisoPostNotificaciones ->
                solicitarNotificacionesPost.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            !permisoMicrofono ->
                solicitarMicrofono.launch(Manifest.permission.RECORD_AUDIO)
            !permisoBluetooth ->
                solicitarBluetooth.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                ))
        }
    }

    /**
     * Avanza la cascada de permisos del onboarding: si quedan pendientes,
     * pide el siguiente tras un breve retardo.
     */
    private fun avanzarOnboarding() {
        mainHandler.postDelayed({
            if (mostrarOnboarding && hayPermisosRuntimePendientes()) {
                pedirSiguientePermisoOnboarding()
            }
        }, 700L)
    }

    /**
     * Lleva al usuario a la pantalla del sistema de mosaicos de accesos rápidos
     * para que añada Walink manualmente. Funciona en todas las versiones.
     */
    private fun solicitarAnadirTile() {
        // Acción del sistema para la pantalla de mosaicos de accesos rápidos.
        startActivity(Intent("android.settings.ACTION_QS_TILE_SETTINGS"))
    }

    /**
     * true si Walink está excluida de la optimización de batería del sistema.
     */
    @Suppress("BatteryLife")
    private fun estaExcluidoDeBateria(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Abre los ajustes de batería para excluir a Walink de la optimización.
     * La pantalla específica (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
     * no existe en todos los dispositivos/OEM, por lo que si falla se cae a la
     * lista genérica de apps sin optimizar y, en último caso, al detalle de app.
     */
    @Suppress("BatteryLife")
    private fun irAjustesBateria() {
        val intencionPaquete = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:$packageName"))
        }
        if (intencionPaquete.resolveActivity(packageManager) != null) {
            Log.d("WalinkDebug", "Batería: resolveActivity = ${intencionPaquete.resolveActivity(packageManager)}")
            try {
                val intentConFlag = intencionPaquete.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intentConFlag)
                Log.d("WalinkDebug", "Batería: abierta pantalla de solicitud de exclusión")
                return
            } catch (e: Exception) {
                Log.e("WalinkDebug", "Batería: error al abrir solicitud", e)
            }
        } else {
            Log.d("WalinkDebug", "Batería: intent de solicitud no resuelto, probando lista genérica")
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Log.d("WalinkDebug", "Batería: abierta lista genérica")
        } catch (e: Exception) {
            Log.e("WalinkDebug", "Batería: error al abrir lista genérica", e)
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    setData(Uri.parse("package:$packageName"))
                }
            )
        }
    }

    /**
     * Reproduce una frase de prueba con la voz actualmente seleccionada, usando
     * el TTS "explorador" de la pantalla (no interfiere con el del listener).
     */
    private fun reproducirPruebaVoz() {
        val tts = vozTtsSelector ?: return
        val voz = vozActual?.let { nombre ->
            tts.voices?.firstOrNull { it.name == nombre }
        }
        if (voz != null) {
            tts.setVoice(voz)
        } else {
            @Suppress("DEPRECATION")
            tts.setLanguage(Locale("es", "ES"))
            @Suppress("DEPRECATION")
            tts.setLanguage(Locale("es", ""))
        }
        tts.speak(
            getString(R.string.voz_prueba_frase),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "walinkPruebaVoz"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: dibujar detrás de las barras del sistema. En Android 15+
        // es obligatorio con targetSdk 35+, y sin aplicar insets el título quedaría
        // oculto bajo el punch-hole del dispositivo. Cabecera verde + iconos claros.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0),                    // iconos claros, barra transparente
            navigationBarStyle = SystemBarStyle.light(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        )

        permisoNotificaciones = tieneAccesoListenerNotificaciones()
        permisoPostNotificaciones =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true // No aplica por debajo de Android 13.
            }
        permisoMicrofono = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permisoBluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

        servicioActivado = dataStore.estaActivado
        mostrarOnboarding = !dataStore.onboardingCompletado
        vozActual = dataStore.vozTts
        leerTrasPreguntar = dataStore.leerTrasPreguntar
        audioBajaVolumen = dataStore.bajarVolumenEnLugarDePausar

        // Crea un TTS "explorador" para listar las voces disponibles del motor.
        // Solo se usa para poblar el selector de voz; no interfiere con el TTS
        // de reproducción (que vive en SpeechReader).
        vozTtsSelector = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Solo mostramos voces en español; con la etiqueta que incluye género
                // y calidad cuando es posible determinarlo.
                vocesDisponibles = vozTtsSelector?.voices
                    ?.filter { it.locale?.language?.equals("es", ignoreCase = true) == true }
                    ?.map { VozTts(nombre = it.name, etiqueta = etiquetaVoz(it)) }
                    ?.sortedBy { it.etiqueta }
                    ?: emptyList()
            }
        }

        setContent {
            WalinkTheme {
                if (mostrarOnboarding) {
                    PantallaOnboarding(
                        permisoPostNotificaciones = permisoPostNotificaciones,
                        permisoMicrofono = permisoMicrofono,
                        permisoBluetooth = permisoBluetooth,
                        accesoListenerConcedido = permisoNotificaciones,
                        permisosRuntimePendientes = hayPermisosRuntimePendientes(),
                        onPedirPróximosPermisos = {
                            if (hayPermisosRuntimePendientes()) pedirSiguientePermisoOnboarding()
                        },
                        onConfigurarAccesoListener = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onAnadirTile = { solicitarAnadirTile() },
                        onIrBateria = { irAjustesBateria() },
                        onTerminar = {
                            dataStore.marcarOnboardingCompletado()
                            mostrarOnboarding = false
                        }
                    )
                } else {
                    PantallaPrincipal(
                        permisoNotificaciones = permisoNotificaciones,
                        permisoPostNotificaciones = permisoPostNotificaciones,
                        permisoMicrofono = permisoMicrofono,
                        permisoBluetooth = permisoBluetooth,
                        bateriaExcluida = bateriaExcluida,
                        servicioActivado = servicioActivado,
                        vozActual = vozActual,
                        voces = vocesDisponibles,
                        onSeleccionarVoz = { nombre ->
                            lifecycleScope.launch {
                                dataStore.setVozTts(nombre)
                                vozActual = nombre
                            }
                        },
                        leerTrasPreguntar = leerTrasPreguntar,
                        onCambiarModoLectura = { preguntar ->
                            lifecycleScope.launch {
                                dataStore.setLeerTrasPreguntar(preguntar)
                                leerTrasPreguntar = preguntar
                            }
                        },
                        bajarVolumen = audioBajaVolumen,
                        onCambiarAudioFondo = { bajar ->
                            lifecycleScope.launch {
                                dataStore.setBajarVolumenEnLugarDePausar(bajar)
                                audioBajaVolumen = bajar
                            }
                        },
                        onProbarVoz = { reproducirPruebaVoz() },
                        onIrAjustesNotificaciones = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onIrAjustesBateria = {
                            Log.d("WalinkDebug", "Botón batería pulsado")
                            irAjustesBateria()
                        },
                        onSolicitarMicrofono = { solicitarMicrofono.launch(Manifest.permission.RECORD_AUDIO) },
                        onSolicitarBluetooth = { solicitarBluetooth.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                        )) },
                        onSolicitarNotificacionesPost = {
                            solicitarNotificacionesPost.launch(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                            )
                        },
                        onSimularMensaje = { remitente, texto ->
                            Log.d("WalinkDebug", "Simular mensaje pulsado: $remitente -> $texto")
                            DebugSimulator.publicarMensajeSimulado(
                                applicationContext,
                                remitente,
                                texto,
                                leerTrasPreguntar
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registrarListenerPrefs()
    }

    override fun onStop() {
        super.onStop()
        desregistrarListenerPrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Libera el TTS explorador usado para listar voces.
        vozTtsSelector?.shutdown()
        vozTtsSelector = null
    }

    private fun registrarListenerPrefs() {
        val prefs = applicationContext.getSharedPreferences(PREFS_NOMBRE, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun desregistrarListenerPrefs() {
        val prefs = applicationContext.getSharedPreferences(PREFS_NOMBRE, Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        permisoNotificaciones = tieneAccesoListenerNotificaciones()
        permisoPostNotificaciones =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        permisoMicrofono = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permisoBluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        bateriaExcluida = estaExcluidoDeBateria()
        servicioActivado = dataStore.estaActivado
        leerTrasPreguntar = dataStore.leerTrasPreguntar
        audioBajaVolumen = dataStore.bajarVolumenEnLugarDePausar
    }

    companion object {
        private const val PREFS_NOMBRE = "walink_prefs"
        private const val KEY_ACTIVADO = "activado"
    }
}

/** Voz de TTS disponible para elegir: identificador interno + etiqueta legible. */
private data class VozTts(val nombre: String, val etiqueta: String)

/** Construye una etiqueta legible para una voz del motor TTS. */
private fun etiquetaVoz(voz: android.speech.tts.Voice): String {
    val nombre = voz.locale.getDisplayName(voz.locale)
    val genero = generoVoz(voz.name)?.let { " · $it" } ?: ""
    return nombre + genero
}

/**
 * Intenta deducir si una voz es femenina o masculina a partir de su nombre
 * interno. Muchos motores (p. ej. Google) no exponen el género de forma fiable,
 * por lo que devolvemos null cuando no se puede determinar.
 */
private fun generoVoz(nombreVoz: String): String? {
    val n = nombreVoz.lowercase()
    return when {
        "female" in n || "femenin" in n || "woman" in n || "-f-" in n || "_f_" in n ||
            n.endsWith("-f") || n.endsWith("-female") -> "Femenina"
        "male" in n || "masculin" in n || "man" in n || "-m-" in n || "_m_" in n ||
            n.endsWith("-m") || n.endsWith("-male") -> "Masculina"
        else -> null
    }
}

/**
 * Pantalla principal. El toggle de activación vive ahora en el mosaico de
 * accesos rápidos (WalinkTileService); aquí solo se informa del estado y se
 * gestionan los permisos. Estilo propio: cabecera con gradiente, tarjetas y
 * zona de depuración (solo debug) al final.
 */
@Composable
private fun PantallaPrincipal(
    permisoNotificaciones: Boolean,
    permisoPostNotificaciones: Boolean,
    permisoMicrofono: Boolean,
    permisoBluetooth: Boolean,
    bateriaExcluida: Boolean,
    servicioActivado: Boolean,
    vozActual: String?,
    voces: List<VozTts>,
    onSeleccionarVoz: (String) -> Unit,
    onProbarVoz: () -> Unit,
    leerTrasPreguntar: Boolean,
    onCambiarModoLectura: (Boolean) -> Unit,
    bajarVolumen: Boolean,
    onCambiarAudioFondo: (Boolean) -> Unit,
    onIrAjustesNotificaciones: () -> Unit,
    onIrAjustesBateria: () -> Unit,
    onSolicitarMicrofono: () -> Unit,
    onSolicitarBluetooth: () -> Unit,
    onSolicitarNotificacionesPost: () -> Unit,
    onSimularMensaje: (remitente: String, texto: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        CabeceraWalink(servicioActivado = servicioActivado)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Cómo activar/desactivar desde el mosaico de accesos rápidos.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📲", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.tile_como_activar_titulo),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.tile_como_activar_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = GrisTextoSuave,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selección de la voz con la que se leen los mensajes.
            TarjetaVoz(
                vozActual = vozActual,
                voces = voces,
                onSeleccionarVoz = onSeleccionarVoz,
                onProbarVoz = onProbarVoz
            )

            // Modo de lectura: inmediatamente o preguntar antes.
            TarjetaModoLectura(
                leerTrasPreguntar = leerTrasPreguntar,
                onCambiarModoLectura = onCambiarModoLectura
            )

            // Qué hacer con la música de fondo mientras Walink habla.
            TarjetaAudioFondo(
                bajarVolumen = bajarVolumen,
                onCambiarAudioFondo = onCambiarAudioFondo
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(id = R.string.titulo_seccion_permisos),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            TarjetaPermiso(
                emoji = "📖",
                titulo = stringResource(id = R.string.permiso_notif_titulo),
                estado = if (permisoNotificaciones) "Concedido ✓"
                else stringResource(id = R.string.permiso_notif_desc),
                concedido = permisoNotificaciones,
                requiereAjustesManuales = true,
                onClick = onIrAjustesNotificaciones
            )

            // Permiso en tiempo de ejecución para publicar notificaciones (API 33+).
            // Sin él, el simulador no puede publicar el mensaje de prueba y la lectura no se dispara.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permisoPostNotificaciones) {
                TarjetaPermiso(
                    emoji = "🔔",
                    titulo = "Notificaciones (permiso)",
                    estado = "Obligatorio para que funcione el simulador de mensajes",
                    concedido = false,
                    requiereAjustesManuales = false,
                    onClick = onSolicitarNotificacionesPost
                )
            }

            TarjetaPermiso(
                emoji = "🎤",
                titulo = "Micrófono (dictado)",
                estado = if (permisoMicrofono) "Concedido ✓" else "No concedido",
                concedido = permisoMicrofono,
                requiereAjustesManuales = false,
                onClick = onSolicitarMicrofono
            )

            TarjetaPermiso(
                emoji = "🔗",
                titulo = "Bluetooth (coche)",
                estado = if (permisoBluetooth) "Concedido ✓" else "No concedido",
                concedido = permisoBluetooth,
                requiereAjustesManuales = false,
                onClick = onSolicitarBluetooth
            )

            TarjetaPermiso(
                emoji = "🔋",
                titulo = "Optimización de batería",
                estado = if (bateriaExcluida) stringResource(id = R.string.permiso_bateria_estado_ok)
                else stringResource(id = R.string.permiso_bateria_estado_pend),
                concedido = bateriaExcluida,
                requiereAjustesManuales = true,
                textoBotonConceder = stringResource(id = R.string.bt_excluir_bateria),
                onClick = onIrAjustesBateria
            )

            // ---- Sección solo visible en buildType DEBUG ----
            if (BuildConfig.DEBUG) {
                SeccionDepuracion(onSimularMensaje = onSimularMensaje)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Cabecera con gradiente verde (estilo Walink) y chip de estado del servicio. */
@Composable
private fun CabeceraWalink(servicioActivado: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(WalinkVerdeOscuro, WalinkVerde))
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🔊  Walink",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = stringResource(id = R.string.subtitle_principal),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (servicioActivado) WalinkVerdeLuz else Color(0xFFFFD166),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (servicioActivado) stringResource(id = R.string.chip_estado_activo)
                        else stringResource(id = R.string.chip_estado_inactivo),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** Tarjeta para mostrar un permiso con su estado y una acción asociada. */
@Composable
private fun TarjetaPermiso(
    emoji: String,
    titulo: String,
    estado: String,
    concedido: Boolean,
    requiereAjustesManuales: Boolean,
    textoBotonConceder: String = "Conceder",
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titulo,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = estado,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (concedido) VerdeOk else GrisEstado,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            when {
                !concedido -> Button(onClick = onClick) {
                    Text(textoBotonConceder)
                }
                requiereAjustesManuales -> OutlinedButton(onClick = onClick) {
                    Text(stringResource(id = R.string.bt_ajustes))
                }
                else -> Text("✓", color = VerdeOk, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Tarjeta para elegir la voz con la que se leen los mensajes (menú desplegable). */
@Composable
private fun TarjetaVoz(
    vozActual: String?,
    voces: List<VozTts>,
    onSeleccionarVoz: (String) -> Unit,
    onProbarVoz: () -> Unit
) {
    val etiquetaActual = if (vozActual.isNullOrBlank()) {
        stringResource(id = R.string.voz_lectura_por_defecto)
    } else {
        voces.firstOrNull { it.nombre == vozActual }?.etiqueta
            ?: stringResource(id = R.string.voz_lectura_por_defecto)
    }

    var menuAbierto by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🗣", fontSize = 24.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.voz_lectura_titulo),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = etiquetaActual,
                    style = MaterialTheme.typography.bodySmall,
                    color = GrisEstado,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onProbarVoz,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            text = "🔊",
                            fontSize = 16.sp
                        )
                    }
                    OutlinedButton(onClick = { menuAbierto = true }) {
                        Text(stringResource(id = R.string.voz_lectura_cambiar))
                    }
                }
                DropdownMenu(
                    expanded = menuAbierto,
                    onDismissRequest = { menuAbierto = false },
                    modifier = Modifier.wrapContentSize()
                ) {
                    if (voces.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.voz_lectura_sin_voces)) },
                            onClick = { menuAbierto = false }
                        )
                    } else {
                        voces.forEach { voz ->
                            DropdownMenuItem(
                                text = { Text(voz.etiqueta) },
                                onClick = {
                                    onSeleccionarVoz(voz.nombre)
                                    menuAbierto = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Tarjeta para elegir cuándo se lee cada mensaje (inmediato o previa confirmación). */
@Composable
private fun TarjetaModoLectura(
    leerTrasPreguntar: Boolean,
    onCambiarModoLectura: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🔔", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(id = R.string.modo_lectura_titulo),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(id = R.string.modo_lectura_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = GrisEstado
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FilaModoLectura(
                emoji = "⚡",
                titulo = stringResource(id = R.string.modo_inmediato),
                descripcion = stringResource(id = R.string.modo_inmediato_desc),
                seleccionado = !leerTrasPreguntar,
                onClick = { onCambiarModoLectura(false) }
            )
            FilaModoLectura(
                emoji = "❓",
                titulo = stringResource(id = R.string.modo_preguntar),
                descripcion = stringResource(id = R.string.modo_preguntar_desc),
                seleccionado = leerTrasPreguntar,
                onClick = { onCambiarModoLectura(true) }
            )
        }
    }
}

/** Fila seleccionable con radio button para cada modo de lectura. */
@Composable
private fun FilaModoLectura(
    emoji: String,
    titulo: String,
    descripcion: String,
    seleccionado: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = seleccionado, onClick = onClick)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = emoji, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = titulo,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (seleccionado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = descripcion,
                style = MaterialTheme.typography.bodySmall,
                color = GrisEstado
            )
        }
    }
}

/** Tarjeta para elegir qué hacer con la música de fondo mientras Walink habla. */
@Composable
private fun TarjetaAudioFondo(
    bajarVolumen: Boolean,
    onCambiarAudioFondo: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎵", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(id = R.string.audio_fondo_titulo),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(id = R.string.audio_fondo_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = GrisEstado
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FilaModoLectura(
                emoji = "🔉",
                titulo = stringResource(id = R.string.audio_bajar_volumen),
                descripcion = stringResource(id = R.string.audio_bajar_volumen_desc),
                seleccionado = bajarVolumen,
                onClick = { onCambiarAudioFondo(true) }
            )
            FilaModoLectura(
                emoji = "⏸️",
                titulo = stringResource(id = R.string.audio_pausar),
                descripcion = stringResource(id = R.string.audio_pausar_desc),
                seleccionado = !bajarVolumen,
                onClick = { onCambiarAudioFondo(false) }
            )
        }
    }
}

/**
 * Pantalla de configuración inicial (onboarding) que se muestra la primera vez
 * que se abre la app. Pide los permisos runtime en secuencia y guía al usuario
 * por los permisos especiales.
 */
@Composable
private fun PantallaOnboarding(
    permisoPostNotificaciones: Boolean,
    permisoMicrofono: Boolean,
    permisoBluetooth: Boolean,
    accesoListenerConcedido: Boolean,
    permisosRuntimePendientes: Boolean,
    onPedirPróximosPermisos: () -> Unit,
    onConfigurarAccesoListener: () -> Unit,
    onAnadirTile: () -> Unit,
    onIrBateria: () -> Unit,
    onTerminar: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "🚗 Walink",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(id = R.string.onboarding_subtitulo),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF666666)
        )

        // ---- Paso 1: permisos runtime en cascada ----
        Text(
            text = "1. Permisos básicos",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 12.dp)
        )

        FilaPermisoOnboarding(
            titulo = "Notificaciones",
            estado = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "No necesario"
                permisoPostNotificaciones -> "Concedido ✓"
                else -> "Pendiente"
            },
            concedido = permisoPostNotificaciones || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        )
        FilaPermisoOnboarding(
            titulo = "Micrófono",
            estado = if (permisoMicrofono) "Concedido ✓" else "Pendiente",
            concedido = permisoMicrofono
        )
        FilaPermisoOnboarding(
            titulo = "Bluetooth",
            estado = if (permisoBluetooth) "Concedido ✓" else "Pendiente",
            concedido = permisoBluetooth
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (permisosRuntimePendientes) {
            Button(
                onClick = onPedirPróximosPermisos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(id = R.string.onboarding_bt_continuar))
            }
        } else {
            Text(
                text = stringResource(id = R.string.onboarding_permisos_listos) + " ✓",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1B873B)
            )
        }

        // ---- Paso 2: permisos especiales ----
        Text(
            text = "2. Configuración en segundo plano",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 12.dp)
        )

        // Acceso a notificaciones del listener
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.onboarding_acceso_notif_claro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(id = R.string.onboarding_acceso_notif_desc_claro),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
            if (accesoListenerConcedido) {
                Text("✓", color = Color(0xFF1B873B))
            } else {
                OutlinedButton(onClick = onConfigurarAccesoListener) {
                    Text("Configurar")
                }
            }
        }

        // Añadir tile a accesos rápidos
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.onboarding_tile),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(id = R.string.onboarding_tile_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                OutlinedButton(onClick = onAnadirTile) {
                    Text("Añadir")
                }
            }
        }

        // Optimización de batería
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.onboarding_bateria),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(id = R.string.onboarding_bateria_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
            OutlinedButton(onClick = onIrBateria) {
                Text("Configurar")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onTerminar,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(id = R.string.onboarding_bt_terminar))
        }
    }
}

@Composable
private fun FilaPermisoOnboarding(titulo: String, estado: String, concedido: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = estado,
            style = MaterialTheme.typography.bodySmall,
            color = if (concedido) Color(0xFF1B873B) else Color(0xFF999999)
        )
    }
}

/**
 * Sección de depuración (solo visible en buildType DEBUG).
 * Permite publicar una notificación simulada de WhatsApp para probar
 * el flujo completo de Walink sin necesitar un mensaje real.
 */
@Composable
private fun SeccionDepuracion(
    onSimularMensaje: (remitente: String, texto: String) -> Unit
) {
    // Valores editables por defecto para la simulación.
    var remitente by remember { mutableStateOf("Ana (Prueba)") }
    var textoMensaje by remember { mutableStateOf("Hola, ¿cómo estás?") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(color = Color(0xFFCCCCCC))

        Text(
            text = stringResource(id = R.string.seccion_debug_titulo),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF6200EE)
        )

        OutlinedTextField(
            value = remitente,
            onValueChange = { remitente = it },
            label = { Text("Remitente") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = textoMensaje,
            onValueChange = { textoMensaje = it },
            label = { Text("Mensaje de prueba") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onSimularMensaje(remitente, textoMensaje) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simular mensaje")
        }

        Text(
            text = "La respuesta del RemoteInput se muestra en Logcat (etiqueta WalinkDebug).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF666666)
        )
    }
}
