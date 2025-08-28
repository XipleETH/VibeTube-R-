# ShootVibes

App Android en Kotlin que reproduce vibraciones inspiradas en disparos (single, burst, auto). Incluye base para añadir sonidos reales usando `SoundPool`.

## Funcionalidades actuales
1. Botones de disparo (single, burst, auto) con vibraciones simuladas.
2. Servicio en primer plano que analiza audio (micrófono por defecto) y genera vibraciones reactivas.
3. Perfiles de vibración configurables (FPS, Explosiones, Armamento Pesado).
4. Persistencia del perfil seleccionado (DataStore Preferences).
5. Botón de parada rápida en la notificación.
6. Preparado para Playback Capture (API 29+) mediante MediaProjection (requiere implementar retorno de resultado desde la Activity al servicio).

## Pendiente / Próximos pasos
- Integrar flujo completo de MediaProjection para captar audio del sistema (el servicio ya lanza el intent, falta manejar onActivityResult y pasar el token de proyección al servicio).
- Añadir samples de audio en `app/src/main/res/raw/` (`single_shot.wav`, `burst_shot.wav`, `auto_shot.wav`) y descomentar su uso en `MainActivity.kt`.
- Ajustar parámetros de filtrado y thresholds según pruebas en distintos dispositivos.

## Ejecución
1. Abrir el proyecto en Android Studio.
2. Sincronizar Gradle.
3. Ejecutar en un dispositivo físico (para mejor vibración) o emulador (limitado).

## Notas de vibración
Se usan patrones mediante `VibrationEffect.createWaveform`. Ajustar duraciones para refinar la sensación según el hardware.
