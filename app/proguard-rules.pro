# Reglas ProGuard / R8 para Walink

# Mantener componentes que Android instancia o interactúa por reflexión / manifest
-keep class com.jjcampac.walink.service.** { *; }
-keep class com.jjcampac.walink.speech.** { *; }
-keep class com.jjcampac.walink.debug.** { *; }
-keep class com.jjcampac.walink.MainActivity { *; }
-keep class com.jjcampac.walink.parser.** { *; }
-keep class com.jjcampac.walink.data.** { *; }
