# Walink 🚗💬

App nativa de Android (Kotlin + Jetpack Compose), pensada para **uso personal e instalación manual** (no está en Play Store), que lee en voz alta los mensajes de WhatsApp y permite responder por voz **mientras conduces, sin tocar el teléfono**.

### El porqué de Walink

Algunos vehículos con sistemas de infoentretenimiento propietarios (p. ej. **Tesla**) o con el sistema operativo **Android Automotive** no ofrecen aplicaciones nativas de mensajería (WhatsApp, Telegram, etc.), y en muchos casos tampoco permiten vincular el móvil mediante **Android Auto** o **CarPlay**.

Coches así suelen aceptar, en cambio, la conexión **Bluetooth** con el teléfono. De esa idea nace Walink: aprovechar la tecnología de notificaciones de Android y esa conexión Bluetooth para que, mientras conduces y **sin tocar el móvil**, la app:

- Te **informe por voz de la recepción** de un mensaje de WhatsApp y lo **lea en voz alta** en el idioma activo de la app (español, inglés, alemán, italiano, francés o japonés).
- Te permita **responder por voz** (dictado) y **enviar la respuesta** usando la acción oficial de respuesta de la propia notificación, sin Accessibility, ni apps de terceros, ni APIs no oficiales.

Todo ello con una **privacidad estricta**: el procesamiento es 100 % local, sin red y sin analítica.

---

## ✅ Fase 1 — Recepción y lectura de mensajes *(completada)*

- ✅ `NotificationListenerService` que detecta notificaciones de WhatsApp (`com.whatsapp` y `com.whatsapp.w4b`).
- ✅ Parseo de remitente y texto (`NotificationParser`), incluyendo `MessagingStyle`.
- ✅ Filtrado de notificaciones de resumen ("3 mensajes nuevos") y de llamadas.
- ✅ Lectura en voz alta con `TextToSpeech` (primera versión en español de España; desde la Fase 5 siempre en el **idioma activo** de la app): *"Mensaje de [remitente]: [texto]"*.
- ✅ Audio focus con `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / `CONTENT_TYPE_SPEECH`.
- ✅ Ajuste de **bajar volumen** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) o **pausar música** (`AUDIOFOCUS_GAIN_TRANSIENT`).
- ✅ Interruptor en el mosaico de accesos rápidos (Quick Settings Tile) con pulsación larga que abre la `MainActivity`.
- ✅ **Onboarding** la primera vez: permisos runtime (notificaciones, micrófono, Bluetooth) + permisos especiales (acceso a notificaciones, batería, tile).
- ✅ Preferencias locales con `SharedPreferences` (todo local, sin red, sin analítica).
- ✅ **Modo depuración:** botón "Simular mensaje" que publica una notificación propia idéntica a WhatsApp (categoría MESSAGE, `MessagingStyle` y acción "Responder" con `RemoteInput`). Siempre visible en debug; en release se desbloquea con la puerta oculta de 7 pulsaciones sobre la versión.

## ✅ Fase 2 — Bluetooth, modos de lectura y asistente de voz *(completada)*

- ✅ Detección de conexión/desconexión del audio del coche (`BluetoothAudioReceiver`).
- ✅ **Dispositivo Bluetooth del coche seleccionable** en la pantalla principal: lista los dispositivos emparejados del teléfono y permite elegir cuál es el coche (o "Ninguno").
  - **Sin selección** → Walink lee siempre que esté activo, por el altavoz del teléfono (comportamiento por defecto).
  - **Con selección** → solo lee/pregunta/responder cuando ese dispositivo está conectado (comprobación en el momento de llegar el mensaje mediante el estado de audio, `AudioManager`).
  - La tarjeta muestra el estado en vivo del dispositivo elegido ("Conectado ✓" / "No conectado") y un botón para actualizar la lista y el estado.
- ✅ **Modo "Leer inmediatamente"**: el mensaje se lee nada más llegar, sin servicio en primer plano.
- ✅ **Modo "Preguntar antes de leer"**: el `LectorAsistenteService` (servicio en primer plano) pregunta por voz *"¿Quieres que lo lea?"* y escucha sí/no.
- ✅ El servicio en primer plano se usa **solo** en el modo "preguntar antes"; en el modo "inmediato" el mensaje se lee directamente sin arrancar el servicio.
- ✅ **Retraso inicial de Bluetooth** (por defecto 800 ms de silencio solo cuando la salida es Bluetooth) para que no se corte el inicio de la frase.
- ✅ Servicio en primer plano declarado tipo **`microphone|specialUse`**: arranca como `specialUse` en API 34+ (para poder iniciar con la app en segundo plano) y **conmuta a `microphone`** justo antes de `startListening` (así mantiene el permiso *while-in-use* del micrófono mientras dura el reconocimiento).
- ✅ Correcciones de estabilidad: notificaciones del canal de servicio ignoradas por el parser (evita bucle), IDs de locución TTS únicos (evita callbacks fantasma), y cada simulación usa un ID de notificación nuevo.

## ✅ Fase 3 — Respuesta por voz: dictado, corrección y envío *(completada)*

- ✅ Tras leer un mensaje (en cualquiera de los dos modos) se ofrece por voz responder al remitente.
- ✅ Pregunta sí/no para responder.
- ✅ **Dictado** de la respuesta con `SpeechRecognizer`.
- ✅ Se anuncia el texto redactado y se pregunta **"¿Enviar, corregir o cancelar?"**.
- ✅ Si se elige **corregir**, vuelve a escuchar el dictado (se descarta el borrador anterior).
- ✅ Si se elige **cancelar**, se descarta el borrador y no se envía nada.
- ✅ Si se elige **enviar**, la respuesta se envía mediante la acción `RemoteInput` de la propia notificación de WhatsApp (sin accesibilidad ni APIs no oficiales).
- ✅ El `PendingIntent` de respuesta y las claves del `RemoteInput` se extraen de la notificación original y se pasan al servicio.
- ✅ **Borrador de respuesta** persistente en `WalinkDataStore` (`borrador_remitente`, `borrador_texto`, `borrador_timestamp`).
- ✅ Verificado con el simulador: el `DebugReplyReceiver` muestra en Logcat la respuesta enviada por RemoteInput.

## ✅ Fase 4 — Filtros, grupos, velocidad, modo pruebas y pulido *(completada)*

- ✅ **Filtros por contacto:** interfaz completa y filtrado efectivo en `WhatsAppNotificationListener` para:
  - Lista blanca (solo se leen contactos permitidos).
  - Lista negra (se ignoran contactos bloqueados).
  - Todos los contactos (sin restricción).
- ✅ **Grupos:** interruptor en UI y filtro en el listener para leer o ignorar mensajes de chats de grupo.
- ✅ **Ajuste "Leer también sin coche (pruebas)":** interruptor cuando hay un coche seleccionado para permitir probar lecturas por el altavoz del teléfono sin estar conectado físicamente al vehículo.
- ✅ **Velocidad de voz:** Slider interactivo (de 0.5x a 2.0x) con guardado persistente y actualización en vivo del motor `TextToSpeech` (`setSpeechRate`).
- ✅ **Detección de notas de voz:** `NotificationParser` detecta audios/notas de voz de WhatsApp y locuta amigablemente que se ha recibido una nota de voz.
- ✅ **Pulido final con R8 / ProGuard:**
  - `isMinifyEnabled = true` y `isShrinkResources = true` activados para el buildType `release`.
  - Archivo `proguard-rules.pro` configurado para mantener componentes Android, servicios en primer plano y serializaciones.
  - Compilación `assembleRelease` y `assembleDebug` verificadas con éxito.

## ✅ Fase 5 — Micrófono permanente, arranque al encender, diagnóstico y pulido *(completada)*

- ✅ **Micrófono permanente con pantalla apagada:** el `MicrofonoPermanenteService` (FGS tipo `microphone`, en segundo plano) mantiene el permiso *while-in-use* del micrófono mientras Walink está activo. Corrige el fallo por el que, con la pantalla apagada, el `SpeechRecognizer` solo captaba el primer buffer y devolvía `ERROR_NO_MATCH` (pico RMS 0).
- ✅ **Arranque automático al encender el teléfono:** `BootReceiver` arranca el servicio de micrófono permanente al completarse el arranque del dispositivo, sin necesidad de abrir la app.
- ✅ **Registro de diagnóstico persistente** (`diag/LogDiag.kt`): archivo local `filesDir/walink_diag.log` con rotación (~512 KB + una copia anterior) que guarda el flujo de voz (audio focus, FGS y reconocedor) y una foto del estado del teléfono (pantalla, no-molestar, batería y Bluetooth) en el momento de cada mensaje. Se puede ver y compartir desde la app, o extraer con `adb` en builds debug.
- ✅ **Se respeta la voz elegida:** el `SpeechReader` vuelve a aplicar la voz guardada (local o de red) antes de cada locución. Antes, las voces de red se descartaban y siempre se usaba la voz local por defecto (la previsualización del selector sí funcionaba porque usaba otra instancia de TTS).
- ✅ **Voz y respuestas en el idioma activo de la app** (ES/EN/DE/IT/FR/JA):
  - `SpeechRecognizer` reconoce en el idioma activo (antes iba fijado a `es-ES`).
  - Las respuestas esperadas (sí/no y enviar/corregir/cancelar) están localizadas por idioma en `strings.xml`, con comparación de palabra completa (y frases como "no envíes", "go ahead" o "don't send").
- ✅ Versión **1.1** (versionCode 2).

---

## ✔️ Decisiones de arquitectura ya fijadas

- **Respuestas:** se envían con la acción `RemoteInput` de la propia notificación de WhatsApp. No se usa accesibilidad ni APIs no oficiales.
- **Voz:** `TextToSpeech` y `SpeechRecognizer` en el idioma activo de la app (el elegido en ajustes o, si es "seguir sistema", el del teléfono). Las respuestas esperadas (sí/no, enviar/corregir/cancelar) están localizadas por idioma.
- **FGS con micrófono:** desde Android 14 (API 34) el tipo `microphone` no puede arrancar en segundo plano; por eso el servicio de voz se inicia como `specialUse` y **conmuta a `microphone`** justo antes de escuchar. Además, un FGS permanente de tipo `microphone` (`MicrofonoPermanenteService`) mantiene el permiso *while-in-use* del micrófono y permite oír y responder con la pantalla apagada.
- **Solo el coche:** si el usuario elige un dispositivo Bluetooth como "coche", la app solo lee/pregunta/reproduce cuando ese dispositivo está conectado. Sin selección, lee siempre por el teléfono.

## Requisitos

- **Dispositivo:** Android 10+ (minSdk 29).
- **Android Studio** con SDK 37 (última estable), Kotlin, Jetpack Compose.
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
| **Micrófono** (`RECORD_AUDIO`) | Para el modo "preguntar antes" y para dictar respuestas por voz. |
| **Bluetooth** (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) | Detectar cuándo el coche está conectado (Fase 2). |
| **Optimización de batería** (excluir) | Para que el sistema no mate el servicio en segundo plano. |

## Cómo se activa

Walink se activa/desactiva desde el **mosaico de accesos rápidos** de Android:

1. Despliega la barra de notificaciones desde el borde superior (dos veces en algunos dispositivos).
2. Si no ves el mosaico "Walink", pulsa el icono ✏️ (editar) y arrástralo a los accesos rápidos.
3. **Pulsar el mosaico** alterna activado/desactivado.
4. **Pulsación larga** sobre el mosaico abre la `MainActivity` (simulador de mensajes y ajustes).

> También puedes activarlo desde la app la primera vez (el onboarding guía para añadir el tile).

## Modo de depuración (simulación de mensajes)

En el buildType **debug** la sección "🧪 Modo depuración" aparece siempre en la pantalla principal.
En buildType **release** está oculta por defecto y se habilita con una **puerta oculta**:
pulsa **7 veces sobre la versión** (texto "vX.Y" bajo la cabecera de Walink). Otras **7
pulsaciones** la vuelven a ocultar. Un *Toast* confirma el cambio y el estado se guarda de
forma persistente (SharedPreferences). El `NotificationParser` acepta el paquete propio y
`DebugReplyReceiver` se registra en el manifiesto principal para que la simulación (y la
respuesta por RemoteInput) funcione también en release.

1. Escribe el **remitente** (p. ej. "Ana (Prueba)").
2. Escribe el **mensaje de prueba** (p. ej. "Hola, ¿cómo estás?").
3. Pulsa **"Simular mensaje"** — Walink publica una notificación propia que imita a WhatsApp.
4. El `NotificationParser` (en debug siempre; en release solo con la puerta oculta activada; solo del canal del simulador) detecta la notificación y comienza el flujo (leer → ofrecer responder → dictado → enviar/corregir).
5. La respuesta enviada por RemoteInput se muestra en **Logcat** con la etiqueta `WalinkDebug` (no se envía a WhatsApp).

## Estructura del proyecto

```
app/src/main/java/com/jjcampac/walink/
├── MainActivity.kt                       # Pantalla principal + ajustes + permisos + sección debug
├── WalinkApplication.kt                  # Locale activo para todo el proceso + observador del coche (ACL)
├── data/
│   └── WalinkDataStore.kt                # Preferencias locales (SharedPreferences) + borrador de respuesta
├── debug/
│   └── DebugSimulator.kt                 # Simulador de mensajes + DebugReplyReceiver (solo debug)
├── diag/
│   └── LogDiag.kt                        # Log persistente walink_diag.log con rotación (~512 KB)
├── idioma/
│   └── IdiomaHelper.kt                   # Idiomas soportados (ES/EN/DE/IT/FR/JA) + locale del contexto
├── parser/
│   └── NotificationParser.kt             # Parseo de notificaciones de WhatsApp
├── service/
│   ├── WhatsAppNotificationListener.kt    # Listener de notificaciones (+ RemoteInput + filtro de coche/contactos)
│   ├── BluetoothCoche.kt                 # Dispositivo emparejado del coche + estado de conexión por dispositivo
│   ├── BluetoothAudioReceiver.kt         # Conectividad de audio Bluetooth (A2DP/HEADSET)
│   ├── BluetoothAclReceiver.kt           # Enlace ACL del Bluetooth (conexión real por MAC)
│   ├── WalinkTileService.kt              # Mosaico de accesos rápidos (activar/desactivar)
│   ├── BootReceiver.kt                   # Arranca MicrofonoPermanenteService al encender el teléfono
│   └── MicrofonoPermanenteService.kt     # FGS tipo microphone permanente (micrófono con pantalla apagada)
├── speech/
│   ├── SpeechReader.kt                   # TextToSpeech + audio focus + retraso Bluetooth
│   └── LectorAsistenteService.kt         # FGS de voz: preguntar/responder/dictar/enviar
└── ui/theme/                             # Tema Compose

app/src/debug/
└── AndroidManifest.xml                   # Registrar DebugReplyReceiver solo en debug
```

## Privacidad

- ✅ **Todo es local.** No hay llamadas de red, no hay analítica, no se guardan mensajes en disco.
- ✅ Los mensajes se leen en voz alta y se descartan inmediatamente.
- ✅ La respuesta dictada solo se conserva como borrador local hasta que se envía.
- ✅ Las preferencias se guardan localmente con `SharedPreferences`.
- ✅ El registro de diagnóstico (`walink_diag.log`) es local y no contiene el contenido de los mensajes.

## Notas técnicas

- `minSdk 29`, `targetSdk 37` (última estable), `compileSdk 37`. Un único módulo `:app`.
- Jetpack Compose para la UI; las preferencias se guardan con `SharedPreferences` (clase `WalinkDataStore`).
- El TTS usa audio focus con `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` por defecto (baja la música); configurable a pausar.
- `SpeechRecognizer` se usa desde el hilo principal; se destruye en cuanto llega `onResults`/`onError` para no dejar callbacks colgados.
- Logs con `Log.d` y un tag por clase (`WalinkTTS`, `WalinkLectorVoz`, `WalinkListener`, `WalinkDebug`, `WalinkParser`) para facilitar el diagnóstico.

### El comando exacto para ver el log de la app en logcat

```bash
adb logcat -s WalinkListener WalinkLectorVoz WalinkTTS WalinkDebug WalinkParser WalinkDiag
```

### Log persistente para diagnosticar fallos difíciles de reproducir

El flujo de voz (audio focus, FGS de micrófono, reconocedor) y el estado del
teléfono en el momento de recibir un mensaje (pantalla, no-molestar, batería y
Bluetooth) se guardan también en un fichero local `filesDir/walink_diag.log`
(privado, sin red, con rotación de ~512 KB). Así, un fallo que solo ocurre en el
coche queda registrado aunque no haya un ordenador conectado en ese instante.
Para extraerlo con el móvil enchufado por USB:

```bash
adb exec-out run-as com.jjcampac.walink cat files/walink_diag.log > walink_diag.txt
```

El comando `run-as` solo funciona en builds **debuggable**. En una build release
(instalada sin ordenador) se puede ver en pantalla desde la propia app: en la
tarjeta de depuración hay un botón **"Ver diagnóstico"** que abre el contenido de
`walink_diag.log` y permite **compartirlo** (p. ej. guardarlo como texto).

> En debug, el botón **"Simular mensaje"** también rellena este fichero, de modo
> que se puede verificar que el registro funciona sin esperar a un mensaje real.

---

**Autor:** Proyecto Walink — desarrollado por **jjcampac**, por fases para pruebas en dispositivo real.

**IA:** Con la asistencia de  **cline**

---

## Licencia

Esta aplicación se distribuye bajo la licencia Creative Commons
**Atribución-NoComercial 4.0 Internacional** (CC BY-NC 4.0).

- ✅ **Uso libre:** se puede usar, copiar, modificar y compartir libremente,
  siempre que el uso sea de carácter **no comercial**.
- ✅ **Prohibido su uso comercial:** cualquier uso destinado a fines
  comerciales (incluso apps comerciales, servicios de pago o integración
  dentro de productos con fines de lucro) está **expressamente prohibido**
  sin el permiso expreso del autor.
- ✅ **Atribución:** en cualquier copia o trabajo derivado debes mencionar
  siempre al autor (**jjcampac**), el nombre de la aplicación (**Walink**)
  y la URL del repositorio original
  (**https://github.com/jjcampac/walink**), indicando si se han realizado
  cambios.
- ✅ **Sin garantía:** el software se ofrece "tal cual" (`as-is`),
  sin garantía de ningún tipo, expresada o implícita.

Visita el siguiente enlace para consultar la licencia completa en español:

https://creativecommons.org/licenses/by-nc/4.0/deed.es

El texto completo de la licencia está disponible en el archivo
[LICENSE](../../LICENSE) en la raíz del repositorio.
