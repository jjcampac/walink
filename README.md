# Walink 🚗💬

App nativa de Android (Kotlin + Jetpack Compose) que lee en voz alta los mensajes de WhatsApp mientras conduces, sin tocar el teléfono.

**Estado actual: Fase 1** — Detección de notificaciones de WhatsApp y lectura en voz alta (TTS en español de España).

## Funcionalidad implementada (Fase 1)

- ✅ `NotificationListenerService` que detecta notificaciones de WhatsApp (`com.whatsapp` y `com.whatsapp.w4b`).
- ✅ Parseo de remitente y texto del mensaje.
- ✅ Filtrado de notificaciones de resumen ("3 mensajes nuevos") y de llamadas.
- ✅ Lectura en voz alta con `TextToSpeech` en español de España: *"Mensaje de [remitente]: [texto]"*.
- ✅ Audio focus con `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (baja la música en lugar de cortarla).
- ✅ Interruptor en el mosaico de accesos rápidos (Quick Settings Tile) con pulsación larga que abre la MainActivity.
- ✅ **Onboarding la primera vez:** al abrir la app por primera vez, pide los permisos runtime (notificaciones, micrófono, Bluetooth) en secuencia automática y guía por los permisos especiales (acceso a notificaciones, batería, añadir tile).
- ✅ Pantalla de guía de permisos (notificaciones, micrófono, Bluetooth, batería).
- ✅ Preferencias locales con `SharedPreferences` (todo local, sin red, sin analítica).
- ✅ **Modo de depuración (solo buildType debug):** botón "Simular mensaje" que publica una notificación propia con categoría MESSAGE, `MessagingStyle` y acción "Responder" con `RemoteInput`. Al responder, el texto se muestra en Logcat (etiqueta `WalinkDebug`) en lugar de enviarse a WhatsApp.

## Próximas fases

- **Fase 2:** Detección de Bluetooth del coche + foreground service persistente.
- **Fase 3:** Dictado por voz, confirmación y respuesta usando `RemoteInput` de la notificación.
- **Fase 4:** Ajustes avanzados (contactos permitidos/bloqueados, grupos, velocidad de voz).

## Requisitos

- **Dispositivo:** Android 10+ (minSdk 29).
- **Android Studio** con SDK 37 (última estable).
- **WhatsApp** instalado (estándar o Business).

## Instalación

1. Clona o abre este proyecto en Android Studio.
2. Conecta tu dispositivo por USB (o usa el emulador).
3. Ejecuta la app pulsando **Run** (▶).
4. Sigue los pasos de la pantalla principal para conceder los permisos.

## Permisos requeridos

| Permiso | Para qué |
|---------|----------|
| **Acceso a notificaciones** | El servicio `NotificationListenerService` necesita ver las notificaciones de WhatsApp. Se concede en *Ajustes → Apps → Acceso especial → Acceso a notificaciones*. |
| **Micrófono** (`RECORD_AUDIO`) | Necesario en fases posteriores para dictar respuestas por voz. |
| **Bluetooth** (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) | Necesario en la Fase 2 para detectar el coche conectado. |
| **Optimización de batería** (excluir) | Para que el sistema no mate el servicio en segundo plano. |

## Cómo se activa

Walink se activa/desactiva desde el **mosaico de accesos rápidos** de Android:

1. Despliega la barra de notificaciones desde el borde superior (dos veces en algunos dispositivos).
2. Si no ves el mosaico "Walink", pulsa el icono ✏️ (editar) y arrástralo a los accesos rápidos.
3. **Pulsar el mosaico** alterna activado/desactivado.
4. **Pulsación larga** sobre el mosaico abre la MainActivity (para el simulador de mensajes y futuros ajustes).

> También puedes activarlo desde la app la primera vez (el onboarding guía para añadir el tile).

## Modo de depuración (solo buildType debug)

En el **buildType debug** aparece una sección "🧪 Modo depuración" en la pantalla principal:

1. Escribe el **remitente** (p. ej. "Ana (Prueba)").
2. Escribe el **mensaje de prueba** (p. ej. "Hola, ¿cómo estás?").
3. Pulsa **"Simular mensaje"** — Walink publica una notificación propia que imita a WhatsApp (categoría MESSAGE, `MessagingStyle` y acción "Responder" con `RemoteInput`).
4. El `NotificationParser` (en debug también acepta el paquete de la propia app) detecta la notificación y la lee en voz alta.
5. Toca **"Responder"** en la notificación simulada, escribe/dicta una respuesta y envíala.
6. El texto de la respuesta se muestra en **Logcat** con la etiqueta `WalinkDebug` (no se envía a WhatsApp).

> 💡 Esto permite probar el flujo completo del listener de notificaciones + TTS sin depender de un mensaje real de WhatsApp.

## Estructura del proyecto

```
app/src/main/java/com/jjcampac/walink/
├── MainActivity.kt                    # Pantalla principal + permisos + sección debug
├── data/
│   └── WalinkDataStore.kt             # Preferencias locales (SharedPreferences)
├── debug/
│   └── DebugSimulator.kt              # Simulador de mensajes + DebugReplyReceiver (solo activo en debug)
├── parser/
│   └── NotificationParser.kt          # Parseo de notificaciones de WhatsApp
├── service/
│   ├── WhatsAppNotificationListener.kt # Listener de notificaciones
│   └── WalinkTileService.kt            # Mosaico de accesos rápidos (activar/desactivar)
├── speech/
│   └── SpeechReader.kt                # TextToSpeech + audio focus
└── ui/theme/                          # Tema Compose

app/src/debug/
├── AndroidManifest.xml                # Registrar DebugReplyReceiver solo en debug
```

## Privacidad

- ✅ **Todo es local.** No hay llamadas de red, no hay analítica, no se guardan mensajes en disco.
- ✅ Los mensajes se leen en voz alta y se descartan inmediatamente.
- ✅ Las preferencias se guardan localmente con `SharedPreferences`.

## Notas técnicas

- `minSdk 29`, `targetSdk 37` (última estable), `compileSdk 37`.
- Jetpack Compose para la UI, un único módulo `:app`.
- El TTS usa audio focus con `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` para bajar la música del coche en lugar de cortarla.
- El ciclo de vida de `TextToSpeech` se maneja en `onDestroy()` para evitar fugas de memoria.

---

**Autor:** Proyecto Walink — Desarrollado por fases para pruebas en dispositivo real.
