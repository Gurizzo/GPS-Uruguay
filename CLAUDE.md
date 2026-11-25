# CLAUDE.md

Navegador GPS sin conexión para Uruguay ("UYPS — Uruguay Positioning System"; antes "Radar UY"), app Android + Android Auto para un Honda Civic 2017.
El usuario escribe en español rioplatense; responder y documentar en español.

## Regla: probar en el celular (OBLIGATORIA)

Antes de **abrir un PR, publicar un Release, subir cambios a `main` o agregar/cambiar una regla del proyecto**:

1. Pedirle al usuario que **conecte su celular por USB** (Depuración USB e "Instalar vía USB" en Xiaomi). Sin celular conectado no se sigue.
2. Correr `bash tools/verificar.sh` (pruebas unitarias + compilación + instalar en el celular + abrir la app y cada menú + revisar errores graves). Tiene que terminar en `VERIFICACIÓN OK`.
3. Revisar las capturas de `verificacion/` (mirarlas, no solo confiar en el código de salida) y probar a mano lo que cambió.
4. Recién ahí commit/PR/Release, y contar en la descripción qué se probó y en qué celular.

Si algo falla, se arregla y se vuelve a verificar; no se sube "para probar después".

Leer primero: `README.md`, `docs/ARQUITECTURA.md` (clases, formatos binarios) y `docs/CONTEXTO.md` (decisiones y pendientes).

## Comandos

- Compilar: `./gradlew assembleDebug` (requiere `app/src/main/assets/uruguay.pmtiles` y `buildings.pmtiles`, que no están en git).
- Pruebas: `./gradlew testDebugUnitTest` (rutas sobre `graph.bin` real; la salida queda en `app/build/test-results`).
- Instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (Xiaomi: activar "Instalar vía USB").
- Abrir un menú por adb (solo debug): `adb shell am start -n com.radiohonda.radaruy/.MainActivity --es abrir negocios|cerca|buscar`.
- Demo pública: `bash tools/grabar_demo.sh` (modo demo, sin GPS real). Revisar SIEMPRE los cuadros antes de publicar: el repo es público y no puede aparecer la ubicación, la pantalla de inicio ni las búsquedas del usuario.
- Datos: scripts en `tools/` (ver README → Datos). Las descargas crudas van a `data-build/` (ignorado).

## Convenciones

- Íconos: dibujados en `Icons.kt` (espacio -20..20); no agregar PNG.
- Colores del diseño: `Ui.kt` (BG `#131A2E`, CARD `#1B2442`, acento cian `#2FE6E9`; ruta 3D rosa `#FF2E93`).
- `style3d.json` se genera desde `style.json` con `tools/make_styles.py`: editar `style.json` y regenerar.
- Si cambia el formato de `graph.bin`, subir la versión y mantener la lectura de versiones anteriores en `RoadGraph.load`.
- Cambios en el router: correr `testDebugUnitTest` (verifica contramano y giros prohibidos).
