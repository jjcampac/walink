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
import android.speech.tts.Voice
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jjcampac.walink.BuildConfig
import com.jjcampac.walink.data.WalinkDataStore
import com.jjcampac.walink.idioma.IdiomaHelper
import com.jjcampac.walink.debug.DebugSimulator
import com.jjcampac.walink.service.BluetoothCoche
import com.jjcampac.walink.service.WhatsAppNotificationListener
import com.jjcampac.walink.speech.SpeechReader
import com.jjcampac.walink.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Clase de Walink.
 *
 * @author jjcampac
 * @author cline
 */
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
    private var idiomaActual by mutableStateOf("")

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

    // ---- Dispositivos Bluetooth del coche (Fase 2) ----
    // Uno o varios dispositivos. Walink solo actúa cuando ALGUNO de ellos
    // está conectado. Si el conjunto está vacío, lee siempre por el teléfono.
    private var dispositivosSeleccionados by mutableStateOf<Set<String>>(emptySet())   // direcciones MAC
    private var dispositivosEmparejados by mutableStateOf<List<BluetoothCoche.Dispositivo>>(emptyList())
    private var dispositivosConectados by mutableStateOf<Set<String>>(emptySet())      // MACs conectadas entre las seleccionadas

    // ---- Fase 4: Ajustes adicionales ----
    private var leerSinCoche by mutableStateOf(false)
    private var leerGrupos by mutableStateOf(true)
    private var velocidadVoz by mutableStateOf(1.0f)
    private var contactosPermitidosTexto by mutableStateOf("")
    private var contactosBloqueadosTexto by mutableStateOf("")
    private var modoFiltroContactos by mutableStateOf(0) // 0: todos, 1: permitidos, 2: bloqueados

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
            tts.setLanguage(IdiomaHelper.localeResuelto(this))
        }
        tts.setSpeechRate(velocidadVoz)
        tts.speak(
            getString(R.string.voz_prueba_frase),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "walinkPruebaVoz"
        )
    }

    /**
     * Recarga la lista de dispositivos Bluetooth emparejados con el teléfono y
     * actualiza el estado de conexión del dispositivo actualmente seleccionado.
     * Se llama al abrir la pantalla, al volver de segundo plano y al pulsar
     * "Actualizar lista" en la tarjeta del coche.
     */
    private fun refrescarDispositivosBluetooth() {
        dispositivosEmparejados =
            if (permisoBluetooth) BluetoothCoche.obtenerDispositivosEmparejados(applicationContext)
            else emptyList()
        refrescarEstadoConexion()
    }

    /** Recalcula cuáles de los dispositivos Bluetooth seleccionados están conectados ahora. */
    private fun refrescarEstadoConexion() {
        dispositivosConectados = dispositivosSeleccionados.filter { mac ->
            BluetoothCoche.estaConectado(applicationContext, mac)
        }.toSet()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(IdiomaHelper.contextoLocalizado(newBase))
    }

    /**
     * Cambia el idioma de la app y recrea la Activity para aplicar el nuevo locale.
     * La preferencia vacía ("") significa "seguir el idioma del sistema".
     */
    private fun cambiarIdioma(codigo: String) {
        if (idiomaActual == codigo) return
        dataStore.setIdiomaApp(codigo)
        idiomaActual = codigo
        // Al cambiar de idioma se descarta la voz elegida (suele ser del idioma
        // anterior y no estar disponible): se usará la voz por defecto del nuevo.
        lifecycleScope.launch { dataStore.setVozTts(null) }
        recreate()
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
        idiomaActual = dataStore.idiomaApp
        vozActual = dataStore.vozTts
        leerTrasPreguntar = dataStore.leerTrasPreguntar
        audioBajaVolumen = dataStore.bajarVolumenEnLugarDePausar
        dispositivosSeleccionados = dataStore.dispositivosBluetooth
        leerSinCoche = dataStore.leerSinCoche
        leerGrupos = dataStore.leerGrupos
        velocidadVoz = dataStore.velocidadVoz
        contactosPermitidosTexto = dataStore.contactosPermitidos.joinToString(", ")
        contactosBloqueadosTexto = dataStore.contactosBloqueados.joinToString(", ")
        modoFiltroContactos = when {
            dataStore.contactosPermitidos.isNotEmpty() -> 1
            dataStore.contactosBloqueados.isNotEmpty() -> 2
            else -> 0
        }
        refrescarDispositivosBluetooth()

        // Crea un TTS "explorador" para listar las voces disponibles del motor.
        // Solo se usa para poblar el selector de voz; no interfiere con el TTS
        // de reproducción (que vive en SpeechReader). La lista se adapta al
        // idioma activo de la app (el elegido o, si es "seguir sistema", el del teléfono).
        val idiomaVoz = IdiomaHelper.localeResuelto(this).language
        vozTtsSelector = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Mostramos únicamente las voces del idioma activo, con la etiqueta
                // que incluye género y calidad cuando es posible determinarlo.
                vocesDisponibles = vozTtsSelector?.voices
                    ?.filter { it.locale?.language.equals(idiomaVoz, ignoreCase = true) == true }
                    ?.mapIndexed { index, voz ->
                        val base = voz.locale.getDisplayName(voz.locale)
                        val etiqueta = "$base — Voz ${index + 1}"
                        VozTts(nombre = voz.name, etiqueta = etiqueta, idioma = voz.locale?.language ?: "")
                    }
                    ?.sortedBy { it.idioma }
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
                    // Estado en vivo del coche: reacciona a las conexiones/desconexiones
                    // Bluetooth (enlace ACL) sin necesidad de pulsar "actualizar".
                    LaunchedEffect(dispositivosSeleccionados, permisoBluetooth) {
                        if (permisoBluetooth) {
                            refrescarEstadoConexion()
                            // El estado es POR dispositivo (conjunto de MACs conectadas):
                            // actualizamos cuando el observador ACL notifique cambios.
                            BluetoothCoche.estadoConectado.collect { macsConectados ->
                                dispositivosConectados = dispositivosSeleccionados.filter { sel ->
                                    macsConectados.any { it.equals(sel, ignoreCase = true) }
                                }.toSet()
                            }
                        }
                    }
                    PantallaPrincipal(
                        permisoNotificaciones = permisoNotificaciones,
                        permisoPostNotificaciones = permisoPostNotificaciones,
                        permisoMicrofono = permisoMicrofono,
                        permisoBluetooth = permisoBluetooth,
                        bateriaExcluida = bateriaExcluida,
                        servicioActivado = servicioActivado,
                        idiomaActual = idiomaActual,
                        onCambiarIdioma = ::cambiarIdioma,
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
                        dispositivosSeleccionados = dispositivosSeleccionados,
                        dispositivosEmparejados = dispositivosEmparejados,
                        dispositivosConectados = dispositivosConectados,
                        onSeleccionarDispositivo = { direccionMac ->
                            if (direccionMac == null) {
                                // Limpiar toda la selección (lea siempre por el teléfono)
                                lifecycleScope.launch {
                                    dataStore.limpiarDispositivosBluetooth()
                                }
                                dispositivosSeleccionados = emptySet()
                            } else {
                                val nuevoConjunto = dispositivosSeleccionados.toMutableSet()
                                if (nuevoConjunto.remove(direccionMac)) {
                                    // Ya estaba seleccionado: lo quitamos
                                } else {
                                    // No estaba: lo agregamos
                                    nuevoConjunto.add(direccionMac)
                                }
                                lifecycleScope.launch {
                                    dataStore.setDispositivosBluetooth(nuevoConjunto)
                                }
                                dispositivosSeleccionados = nuevoConjunto
                            }
                            refrescarEstadoConexion()
                            // Consulta el estado real del/los dispositivos (por perfil),
                            // para marcarlo(s) conectado(s)/desconectado(s) ya.
                            BluetoothCoche.refrescarConectado(applicationContext)
                        },
                        onRefrescarDispositivos = { refrescarDispositivosBluetooth() },
                        leerSinCoche = leerSinCoche,
                        onCambiarLeerSinCoche = { activo ->
                            lifecycleScope.launch {
                                dataStore.setLeerSinCoche(activo)
                                leerSinCoche = activo
                            }
                        },
                        leerGrupos = leerGrupos,
                        onCambiarLeerGrupos = { leer ->
                            lifecycleScope.launch {
                                dataStore.setLeerGrupos(leer)
                                leerGrupos = leer
                            }
                        },
                        velocidadVoz = velocidadVoz,
                        onCambiarVelocidadVoz = { vel ->
                            lifecycleScope.launch {
                                dataStore.setVelocidadVoz(vel)
                                velocidadVoz = vel
                                SpeechReader.obtener(applicationContext).actualizarVelocidad(vel)
                            }
                        },
                        modoFiltro = modoFiltroContactos,
                        contactosPermitidosTexto = contactosPermitidosTexto,
                        contactosBloqueadosTexto = contactosBloqueadosTexto,
                        onCambiarModoFiltro = { nuevoModo ->
                            modoFiltroContactos = nuevoModo
                            lifecycleScope.launch {
                                when (nuevoModo) {
                                    0 -> { // Todos
                                        dataStore.setContactosPermitidos(emptySet())
                                        dataStore.setContactosBloqueados(emptySet())
                                    }
                                    1 -> { // Permitidos
                                        val permitidos = contactosPermitidosTexto.split(",")
                                            .map { it.trim() }.filter { it.isNotBlank() }.toSet()
                                        dataStore.setContactosPermitidos(permitidos)
                                        dataStore.setContactosBloqueados(emptySet())
                                    }
                                    2 -> { // Bloqueados
                                        val bloqueados = contactosBloqueadosTexto.split(",")
                                            .map { it.trim() }.filter { it.isNotBlank() }.toSet()
                                        dataStore.setContactosBloqueados(bloqueados)
                                        dataStore.setContactosPermitidos(emptySet())
                                    }
                                }
                            }
                        },
                        onGuardarContactosPermitidos = { texto ->
                            contactosPermitidosTexto = texto
                            val lista = texto.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                            lifecycleScope.launch {
                                dataStore.setContactosPermitidos(lista)
                            }
                        },
                        onGuardarContactosBloqueados = { texto ->
                            contactosBloqueadosTexto = texto
                            val lista = texto.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                            lifecycleScope.launch {
                                dataStore.setContactosBloqueados(lista)
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
        dispositivosSeleccionados = dataStore.dispositivosBluetooth
        refrescarDispositivosBluetooth()
    }

    companion object {
        private const val PREFS_NOMBRE = "walink_prefs"
        private const val KEY_ACTIVADO = "activado"
    }
}

/** Voz de TTS disponible para elegir: identificador interno + etiqueta legible.
 *
 * @author jjcampac
 * @author cline
 */
private data class VozTts(val nombre: String, val etiqueta: String, val idioma: String)

/** Construye una etiqueta legible para una voz del motor TTS. */
private fun etiquetaVoz(voz: Voice): String {
    return voz.locale.getDisplayName(voz.locale)
}

/** Emoji de género deducido del objeto [Voice] del motor TTS. */
private fun generoVozEmoji(voz: Voice): String {
    // Intenta usar getGender() por reflexión (disponible en API 21+).
    val gender = try {
        val method = voz.javaClass.getMethod("getGender")
        method.invoke(voz) as? Int ?: -1
    } catch (e: Exception) {
        -1
    }
    return when (gender) {
        2 -> "👩"   // GENDER_FEMALE
        1 -> "👨"   // GENDER_MALE
        else -> {
            // Respaldo: deducir del nombre interno
            val n = voz.name.lowercase()
            when {
                "female" in n || "femenin" in n || "woman" in n || "-f-" in n || "_f_" in n ||
                    n.endsWith("-f") || n.endsWith("-female") -> "👩"
                "male" in n || "masculin" in n || "man" in n || "-m-" in n || "_m_" in n ||
                    n.endsWith("-m") || n.endsWith("-male") -> "👨"
                else -> "⚪"
            }
        }
    }
}

/**
 * Orden numérico para agrupar por género en el listado:
 * 0 = Femenina, 1 = Masculina, 2 = Indeterminada.
 */
private fun generoOrden(etiqueta: String): Int =
    when {
        etiqueta.startsWith("👩") -> 0
        etiqueta.startsWith("👨") -> 1
        else -> 2
    }

/**
 * Extrae un fragmento corto del nombre interno de la voz para diferenciar
 * voces que comparten el mismo nombre mostrado (p. ej. varias "español (España)").
 * Devuelve vacío si el nombre interno ya es suficientemente descriptivo.
 */
private fun nombreInternoCorto(nombreVoz: String): String {
    val n = nombreVoz.lowercase()
    // Quita prefijos comunes de motor para dejar solo el identificador distintivo
    return when {
        n.contains("google") -> n.substringAfterLast("google").trim().takeIf { it.isNotBlank() } ?: ""
        n.contains("espeak") -> n.substringAfterLast("espeak").trim().takeIf { it.isNotBlank() } ?: ""
        n.contains("pico") -> n.substringAfterLast("pico").trim().takeIf { it.isNotBlank() } ?: ""
        n.contains("svox") -> n.substringAfterLast("svox").trim().takeIf { it.isNotBlank() } ?: ""
        else -> ""
    }
}

/**
 * Intenta deducir si una voz es femenina o masculina a partir de su nombre
 * interno. Muchos motores (p. ej. Google) no exponen el género de forma fiable,
 * por lo que devolvemos null cuando no se puede determinar.
 * @deprecated Usar [generoVozEmoji] para la UI.
 */
@Deprecated("Usar generoVozEmoji para la UI")
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
    idiomaActual: String,
    onCambiarIdioma: (String) -> Unit,
    vozActual: String?,
    voces: List<VozTts>,
    onSeleccionarVoz: (String) -> Unit,
    onProbarVoz: () -> Unit,
    leerTrasPreguntar: Boolean,
    onCambiarModoLectura: (Boolean) -> Unit,
    bajarVolumen: Boolean,
    onCambiarAudioFondo: (Boolean) -> Unit,
    dispositivosSeleccionados: Set<String>,
    dispositivosEmparejados: List<BluetoothCoche.Dispositivo>,
    dispositivosConectados: Set<String>,
    onSeleccionarDispositivo: (String?) -> Unit,
    onRefrescarDispositivos: () -> Unit,
    leerSinCoche: Boolean,
    onCambiarLeerSinCoche: (Boolean) -> Unit,
    leerGrupos: Boolean,
    onCambiarLeerGrupos: (Boolean) -> Unit,
    velocidadVoz: Float,
    onCambiarVelocidadVoz: (Float) -> Unit,
    modoFiltro: Int,
    contactosPermitidosTexto: String,
    contactosBloqueadosTexto: String,
    onCambiarModoFiltro: (Int) -> Unit,
    onGuardarContactosPermitidos: (String) -> Unit,
    onGuardarContactosBloqueados: (String) -> Unit,
    onIrAjustesNotificaciones: () -> Unit,
    onIrAjustesBateria: () -> Unit,
    onSolicitarMicrofono: () -> Unit,
    onSolicitarBluetooth: () -> Unit,
    onSolicitarNotificacionesPost: () -> Unit,
    onSimularMensaje: (remitente: String, texto: String) -> Unit
) {
    var tabSeleccionada by remember { mutableStateOf(0) }
    val titulosTabs = listOf(
        "🚗 " + stringResource(id = R.string.tab_conduccion),
        "🎧 " + stringResource(id = R.string.tab_voz_filtros),
        "⚙️ " + stringResource(id = R.string.tab_ajustes_permisos)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CabeceraWalink(servicioActivado = servicioActivado)

        // Barra de Pestañas (TabRow de Material 3)
        TabRow(
            selectedTabIndex = tabSeleccionada,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            titulosTabs.forEachIndexed { indice, titulo ->
                Tab(
                    selected = tabSeleccionada == indice,
                    onClick = { tabSeleccionada = indice },
                    text = {
                        Text(
                            text = titulo,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (tabSeleccionada == indice) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            when (tabSeleccionada) {
                0 -> {
                    // PESTAÑA 1: CONDUCCIÓN
                    // Idioma de la app (multilengua)
                    TarjetaIdioma(
                        idiomaActual = idiomaActual,
                        onCambiarIdioma = onCambiarIdioma
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    // Coche y Conectividad
                    TarjetaCocheConectividad(
                        permisoBluetooth = permisoBluetooth,
                        dispositivos = dispositivosEmparejados,
                        seleccionados = dispositivosSeleccionados,
                        conectados = dispositivosConectados,
                        onSeleccionarDispositivo = onSeleccionarDispositivo,
                        onRefrescar = onRefrescarDispositivos
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Modo de lectura (¿Cuándo leer?)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            CabeceraTarjetaTematica(
                                emoji = "🔔",
                                titulo = stringResource(id = R.string.modo_lectura_titulo),
                                subtitulo = stringResource(id = R.string.modo_lectura_desc)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !leerTrasPreguntar,
                                    onClick = { onCambiarModoLectura(false) },
                                    label = { Text(stringResource(id = R.string.chip_inmediato)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = leerTrasPreguntar,
                                    onClick = { onCambiarModoLectura(true) },
                                    label = { Text(stringResource(id = R.string.chip_preguntar)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Música de fondo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            CabeceraTarjetaTematica(
                                emoji = "🎵",
                                titulo = stringResource(id = R.string.audio_fondo_titulo),
                                subtitulo = stringResource(id = R.string.audio_fondo_desc)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = bajarVolumen,
                                    onClick = { onCambiarAudioFondo(true) },
                                    label = { Text(stringResource(id = R.string.chip_bajar_volumen)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = !bajarVolumen,
                                    onClick = { onCambiarAudioFondo(false) },
                                    label = { Text(stringResource(id = R.string.chip_pausar_musica)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // PESTAÑA 2: VOZ Y FILTROS
                    TarjetaVozAudio(
                        vozActual = vozActual,
                        voces = voces,
                        onSeleccionarVoz = onSeleccionarVoz,
                        onProbarVoz = onProbarVoz,
                        velocidad = velocidadVoz,
                        onCambiarVelocidad = onCambiarVelocidadVoz,
                        bajarVolumen = bajarVolumen,
                        onCambiarAudioFondo = onCambiarAudioFondo
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    TarjetaLecturaPrivacidad(
                        leerTrasPreguntar = leerTrasPreguntar,
                        onCambiarModoLectura = onCambiarModoLectura,
                        leerGrupos = leerGrupos,
                        onCambiarLeerGrupos = onCambiarLeerGrupos,
                        modoFiltro = modoFiltro,
                        contactosPermitidosTexto = contactosPermitidosTexto,
                        contactosBloqueadosTexto = contactosBloqueadosTexto,
                        onCambiarModoFiltro = onCambiarModoFiltro,
                        onGuardarPermitidos = onGuardarContactosPermitidos,
                        onGuardarBloqueados = onGuardarContactosBloqueados
                    )
                }

                2 -> {
                    // PESTAÑA 3: PERMISOS Y DEPURACIÓN
                    TarjetaPermisosSistema(
                        permisoNotificaciones = permisoNotificaciones,
                        permisoPostNotificaciones = permisoPostNotificaciones,
                        permisoMicrofono = permisoMicrofono,
                        permisoBluetooth = permisoBluetooth,
                        bateriaExcluida = bateriaExcluida,
                        onIrAjustesNotificaciones = onIrAjustesNotificaciones,
                        onSolicitarNotificacionesPost = onSolicitarNotificacionesPost,
                        onSolicitarMicrofono = onSolicitarMicrofono,
                        onSolicitarBluetooth = onSolicitarBluetooth,
                        onIrAjustesBateria = onIrAjustesBateria
                    )

                    if (BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(14.dp))
                        TarjetaDepuracionPlegable(
                            onSimularMensaje = onSimularMensaje,
                            leerSinCoche = leerSinCoche,
                            onCambiarLeerSinCoche = onCambiarLeerSinCoche
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Tarjeta de selección de idioma de la app. Ofrece "seguir el sistema" y los
 * 6 idiomas soportados (español, inglés, alemán, italiano, francés y japonés).
 */
@Composable
private fun TarjetaIdioma(idiomaActual: String, onCambiarIdioma: (String) -> Unit) {
    var menuIdiomaAbierto by remember { mutableStateOf(false) }

    // Etiqueta actual: el nombre del idioma elegido o "seguir el dispositivo".
    val etiquetaActual = if (idiomaActual.isBlank() || idiomaActual == IdiomaHelper.SISTEMA) {
        stringResource(id = R.string.idioma_seguir_sistema)
    } else {
        IdiomaHelper.soportados[idiomaActual] ?: stringResource(id = R.string.idioma_seguir_sistema)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.idioma_titulo),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { menuIdiomaAbierto = true }) {
                Text("🌐 $etiquetaActual")
            }
            DropdownMenu(
                expanded = menuIdiomaAbierto,
                onDismissRequest = { menuIdiomaAbierto = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.idioma_seguir_sistema)) },
                    onClick = {
                        menuIdiomaAbierto = false
                        onCambiarIdioma("")
                    }
                )
                IdiomaHelper.soportados.forEach { (codigo, nombre) ->
                    DropdownMenuItem(
                        text = { Text(nombre) },
                        onClick = {
                            menuIdiomaAbierto = false
                            onCambiarIdioma(codigo)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.idioma_desc),
                style = MaterialTheme.typography.bodySmall,
                color = GrisEstado
            )
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

            // Tarjeta "¿Cómo activar Walink?": integrada en la cabecera verde
            Surface(
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📲", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.tile_como_activar_titulo),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(id = R.string.tile_como_activar_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Estado de la aplicación (Activo / Inactivo)
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 14.dp)
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

/**
 * ============================================================================
 * REDISEÑO DE INTERFAZ: OPCIÓN A - TARJETAS TEMÁTICAS SECCIONADAS
 * ============================================================================
 */

/** 1. Coche y Conectividad: selector de uno o varios dispositivos Bluetooth. */
@Composable
private fun TarjetaCocheConectividad(
    permisoBluetooth: Boolean,
    dispositivos: List<BluetoothCoche.Dispositivo>,
    seleccionados: Set<String>,
    conectados: Set<String>,
    onSeleccionarDispositivo: (String?) -> Unit,
    onRefrescar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            CabeceraTarjetaTematica(
                emoji = "🚗",
                titulo = stringResource(id = R.string.seccion_coche_titulo),
                subtitulo = stringResource(id = R.string.seccion_coche_desc)
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (!permisoBluetooth) {
                Text(
                    text = stringResource(id = R.string.dispositivo_permiso_faltante),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = stringResource(id = R.string.dispositivo_seleccionar_coches),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                FilterChip(
                    selected = seleccionados.isEmpty(),
                    onClick = { onSeleccionarDispositivo(null) },
                    label = { Text(stringResource(id = R.string.dispositivo_ninguno)) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (dispositivos.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.dispositivo_sin_emparejados),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        dispositivos.forEach { dispositivo ->
                            val estaSeleccionado = dispositivo.direccion in seleccionados
                            val estaConectado = dispositivo.direccion in conectados
                            FilterChip(
                                selected = estaSeleccionado,
                                onClick = { onSeleccionarDispositivo(dispositivo.direccion) },
                                label = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val emojiEstado = when {
                                            estaSeleccionado && estaConectado -> "✅ "
                                            estaSeleccionado -> "⚪ "
                                            else -> ""
                                        }
                                        Text(
                                            text = emojiEstado + dispositivo.nombre,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val seleccionadosConNombre = dispositivos.filter { it.direccion in seleccionados }
                val conectadosConNombre = dispositivos.filter { it.direccion in conectados }
                when {
                    seleccionados.isEmpty() -> {
                        Text(
                            text = stringResource(id = R.string.dispositivo_ninguno),
                            style = MaterialTheme.typography.bodySmall,
                            color = GrisEstado
                        )
                    }
                    seleccionadosConNombre.size == 1 && conectadosConNombre.isEmpty() -> {
                        Text(
                            text = stringResource(id = R.string.dispositivo_no_conectado),
                            style = MaterialTheme.typography.bodySmall,
                            color = GrisEstado
                        )
                    }
                    else -> {
                        val texto = stringResource(
                            id = R.string.dispositivos_estado_resumen,
                            seleccionadosConNombre.size,
                            conectadosConNombre.size
                        )
                        Text(
                            text = texto,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (conectadosConNombre.isNotEmpty()) VerdeOk else GrisEstado
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRefrescar) {
                        Text(text = "🔄", fontSize = 16.sp)
                    }
                }

                // (el ajuste "leer sin coche" vive ahora en Modo depuración, pestaña Permisos)
            }
        }
    }
}

/** 2. Voz y Audio: selección de locución, velocidad y atenuación de música. */
@Composable
private fun TarjetaVozAudio(
    vozActual: String?,
    voces: List<VozTts>,
    onSeleccionarVoz: (String) -> Unit,
    onProbarVoz: () -> Unit,
    velocidad: Float,
    onCambiarVelocidad: (Float) -> Unit,
    bajarVolumen: Boolean,
    onCambiarAudioFondo: (Boolean) -> Unit
) {
    val etiquetaActual = if (vozActual.isNullOrBlank()) {
        stringResource(id = R.string.voz_lectura_por_defecto)
    } else {
        voces.firstOrNull { it.nombre == vozActual }?.etiqueta
            ?: stringResource(id = R.string.voz_lectura_por_defecto)
    }
    var menuVozAbierto by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            CabeceraTarjetaTematica(
                emoji = "🎧",
                titulo = stringResource(id = R.string.seccion_voz_audio_titulo),
                subtitulo = stringResource(id = R.string.seccion_voz_audio_desc)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Sub-bloque 1: Voz
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.voz_lectura_titulo),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = etiquetaActual,
                        style = MaterialTheme.typography.bodySmall,
                        color = GrisEstado
                    )
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onProbarVoz) {
                            Text("🔊", fontSize = 16.sp)
                        }
                        OutlinedButton(onClick = { menuVozAbierto = true }) {
                            Text(stringResource(id = R.string.voz_lectura_cambiar))
                        }
                    }
                    DropdownMenu(
                        expanded = menuVozAbierto,
                        onDismissRequest = { menuVozAbierto = false }
                    ) {
                        if (voces.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.voz_lectura_sin_voces)) },
                                onClick = { menuVozAbierto = false }
                            )
                        } else {
                            voces.groupBy { it.idioma }.forEach { (idioma, vocesDelIdioma) ->
                                // Cabecera de idioma (no clickeable)
                                DropdownMenuItem(
                                    text = { Text(idioma.uppercase(), style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) },
                                    enabled = false,
                                    onClick = { }
                                )
                                vocesDelIdioma.forEach { voz ->
                                    DropdownMenuItem(
                                        text = { Text(voz.etiqueta) },
                                        onClick = {
                                            onSeleccionarVoz(voz.nombre)
                                            menuVozAbierto = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            // Sub-bloque 2: Velocidad
            Text(
                text = stringResource(id = R.string.ajuste_velocidad_desc, velocidad),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = velocidad,
                onValueChange = { onCambiarVelocidad((Math.round(it * 10f) / 10f)) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(id = R.string.vel_lenta), style = MaterialTheme.typography.labelSmall, color = GrisEstado)
                Text(stringResource(id = R.string.vel_normal), style = MaterialTheme.typography.labelSmall, color = GrisEstado)
                Text(stringResource(id = R.string.vel_rapida), style = MaterialTheme.typography.labelSmall, color = GrisEstado)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            // Sub-bloque 3: Música de fondo (Chips selectores modernos)
            Text(
                text = stringResource(id = R.string.audio_fondo_titulo),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = bajarVolumen,
                    onClick = { onCambiarAudioFondo(true) },
                    label = { Text(stringResource(id = R.string.chip_bajar_volumen)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !bajarVolumen,
                    onClick = { onCambiarAudioFondo(false) },
                    label = { Text(stringResource(id = R.string.chip_pausar_musica)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 3. Lectura y Privacidad: modo de confirmación, grupos y filtros de contacto. */
@Composable
private fun TarjetaLecturaPrivacidad(
    leerTrasPreguntar: Boolean,
    onCambiarModoLectura: (Boolean) -> Unit,
    leerGrupos: Boolean,
    onCambiarLeerGrupos: (Boolean) -> Unit,
    modoFiltro: Int,
    contactosPermitidosTexto: String,
    contactosBloqueadosTexto: String,
    onCambiarModoFiltro: (Int) -> Unit,
    onGuardarPermitidos: (String) -> Unit,
    onGuardarBloqueados: (String) -> Unit
) {
    var textoEditadoPermitidos by remember(contactosPermitidosTexto) { mutableStateOf(contactosPermitidosTexto) }
    var textoEditadoBloqueados by remember(contactosBloqueadosTexto) { mutableStateOf(contactosBloqueadosTexto) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            CabeceraTarjetaTematica(
                emoji = "🛡️",
                titulo = stringResource(id = R.string.seccion_lectura_filtros_titulo),
                subtitulo = stringResource(id = R.string.seccion_lectura_filtros_desc)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Sub-bloque 1: Modo de lectura (Chips)
            Text(
                text = stringResource(id = R.string.modo_lectura_titulo),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !leerTrasPreguntar,
                    onClick = { onCambiarModoLectura(false) },
                    label = { Text(stringResource(id = R.string.chip_inmediato)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = leerTrasPreguntar,
                    onClick = { onCambiarModoLectura(true) },
                    label = { Text(stringResource(id = R.string.chip_preguntar)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            // Sub-bloque 2: Grupos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCambiarLeerGrupos(!leerGrupos) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.ajuste_grupos_titulo),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (leerGrupos) stringResource(id = R.string.ajuste_grupos_activado)
                        else stringResource(id = R.string.ajuste_grupos_desactivado),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (leerGrupos) VerdeOk else GrisEstado
                    )
                }
                Switch(checked = leerGrupos, onCheckedChange = onCambiarLeerGrupos)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            // Sub-bloque 3: Filtro de contactos
            Text(
                text = stringResource(id = R.string.ajuste_filtros_contacto_titulo),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = modoFiltro == 0,
                    onClick = { onCambiarModoFiltro(0) },
                    label = { Text(stringResource(id = R.string.chip_filtro_todos)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = modoFiltro == 1,
                    onClick = { onCambiarModoFiltro(1) },
                    label = { Text(stringResource(id = R.string.chip_filtro_permitidos)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = modoFiltro == 2,
                    onClick = { onCambiarModoFiltro(2) },
                    label = { Text(stringResource(id = R.string.chip_filtro_bloqueados)) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Edición de contactos cuando se elige Lista Blanca o Lista Negra
            AnimatedVisibility(
                visible = modoFiltro != 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (modoFiltro == 1) {
                        OutlinedTextField(
                            value = textoEditadoPermitidos,
                            onValueChange = { textoEditadoPermitidos = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(id = R.string.filtro_contactos_hint)) },
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { onGuardarPermitidos(textoEditadoPermitidos) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(id = R.string.filtro_contactos_guardar))
                        }
                    } else if (modoFiltro == 2) {
                        OutlinedTextField(
                            value = textoEditadoBloqueados,
                            onValueChange = { textoEditadoBloqueados = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(id = R.string.filtro_contactos_hint)) },
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { onGuardarBloqueados(textoEditadoBloqueados) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(id = R.string.filtro_contactos_guardar))
                        }
                    }
                }
            }
        }
    }
}

/** 4. Permisos del Sistema: resumen colapsable/expandible inteligente. */
@Composable
private fun TarjetaPermisosSistema(
    permisoNotificaciones: Boolean,
    permisoPostNotificaciones: Boolean,
    permisoMicrofono: Boolean,
    permisoBluetooth: Boolean,
    bateriaExcluida: Boolean,
    onIrAjustesNotificaciones: () -> Unit,
    onSolicitarNotificacionesPost: () -> Unit,
    onSolicitarMicrofono: () -> Unit,
    onSolicitarBluetooth: () -> Unit,
    onIrAjustesBateria: () -> Unit
) {
    val totalPendientes = (if (permisoNotificaciones) 0 else 1) +
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permisoPostNotificaciones) 1 else 0) +
        (if (permisoMicrofono) 0 else 1) +
        (if (permisoBluetooth) 0 else 1) +
        (if (bateriaExcluida) 0 else 1)

    val todosOk = totalPendientes == 0
    var expandido by remember(todosOk) { mutableStateOf(!todosOk) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (todosOk) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandido = !expandido },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = if (todosOk) "✅" else "⚠️", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.titulo_seccion_permisos),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (todosOk) stringResource(id = R.string.seccion_permisos_ok)
                        else stringResource(id = R.string.seccion_permisos_atencion, totalPendientes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (todosOk) VerdeOk else MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = { expandido = !expandido }) {
                    Text(if (expandido) "▲" else "▼", fontSize = 14.sp, color = GrisEstado)
                }
            }

            AnimatedVisibility(
                visible = expandido,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(6.dp))

                    FilaPermisoCompacta(
                        emoji = "📖",
                        titulo = "Acceso a notificaciones",
                        concedido = permisoNotificaciones,
                        requiereAjustes = true,
                        onClick = onIrAjustesNotificaciones
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permisoPostNotificaciones) {
                        FilaPermisoCompacta(
                            emoji = "🔔",
                            titulo = "Notificaciones",
                            concedido = false,
                            requiereAjustes = false,
                            onClick = onSolicitarNotificacionesPost
                        )
                    }
                    FilaPermisoCompacta(
                        emoji = "🎤",
                        titulo = "Micrófono (dictado)",
                        concedido = permisoMicrofono,
                        requiereAjustes = false,
                        onClick = onSolicitarMicrofono
                    )
                    FilaPermisoCompacta(
                        emoji = "🔗",
                        titulo = "Bluetooth (coche)",
                        concedido = permisoBluetooth,
                        requiereAjustes = false,
                        onClick = onSolicitarBluetooth
                    )
                    FilaPermisoCompacta(
                        emoji = "🔋",
                        titulo = "Optimización de batería",
                        concedido = bateriaExcluida,
                        requiereAjustes = true,
                        textoBoton = stringResource(id = R.string.bt_excluir_bateria),
                        onClick = onIrAjustesBateria
                    )
                }
            }
        }
    }
}

/** Fila compacta de permiso con botón o check verde. */
@Composable
private fun FilaPermisoCompacta(
    emoji: String,
    titulo: String,
    concedido: Boolean,
    requiereAjustes: Boolean,
    textoBoton: String = "Conceder",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (concedido) {
            Text(stringResource(id = R.string.estado_concedido), color = VerdeOk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        } else {
            Button(
                onClick = onClick,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(if (requiereAjustes) stringResource(id = R.string.bt_ajustes) else textoBoton, fontSize = 12.sp)
            }
        }
    }
}

/** Encabezado reutilizable con emoji, título y subtítulo para las tarjetas temáticas. */
@Composable
private fun CabeceraTarjetaTematica(
    emoji: String,
    titulo: String,
    subtitulo: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = titulo,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitulo,
                style = MaterialTheme.typography.bodySmall,
                color = GrisEstado
            )
        }
    }
}

/** Tarjeta colapsable para la zona de depuración. */
@Composable
private fun TarjetaDepuracionPlegable(
    onSimularMensaje: (remitente: String, texto: String) -> Unit,
    leerSinCoche: Boolean,
    onCambiarLeerSinCoche: (Boolean) -> Unit
) {
    var expandido by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandido = !expandido },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🧪", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(id = R.string.seccion_debug_titulo),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF6200EE),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { expandido = !expandido }) {
                    Text(if (expandido) "▲" else "▼", fontSize = 14.sp, color = GrisEstado)
                }
            }

            AnimatedVisibility(
                visible = expandido,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    SeccionDepuracion(
                            onSimularMensaje = onSimularMensaje,
                            leerSinCoche = leerSinCoche,
                            onCambiarLeerSinCoche = onCambiarLeerSinCoche
                        )
                }
            }
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
            text = stringResource(id = R.string.onboarding_paso1),
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
            text = stringResource(id = R.string.onboarding_paso2),
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
                    Text(stringResource(id = R.string.bt_configurar))
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
                    Text(stringResource(id = R.string.bt_anadir))
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
                Text(stringResource(id = R.string.bt_configurar))
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
    onSimularMensaje: (remitente: String, texto: String) -> Unit,
    leerSinCoche: Boolean,
    onCambiarLeerSinCoche: (Boolean) -> Unit
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
            label = { Text(stringResource(id = R.string.debug_remitente)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = textoMensaje,
            onValueChange = { textoMensaje = it },
            label = { Text(stringResource(id = R.string.debug_mensaje_prueba)) },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onSimularMensaje(remitente, textoMensaje) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(id = R.string.debug_simular))
        }

        Text(
            text = stringResource(id = R.string.debug_nota_respuesta),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF666666)
        )

        HorizontalDivider(color = Color(0xFFCCCCCC))

        // Ajuste "leer también sin coche": solo útil en pruebas, por eso vive aquí.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCambiarLeerSinCoche(!leerSinCoche) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.ajuste_leer_sin_coche),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(id = R.string.ajuste_leer_sin_coche_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
            Switch(
                checked = leerSinCoche,
                onCheckedChange = onCambiarLeerSinCoche
            )
        }
    }
}
