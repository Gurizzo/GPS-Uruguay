#!/usr/bin/env bash
# =============================================================================
# Graba la demo pública (docs/demo.gif para el README + verificacion/demo.mp4 para el Release) desde un celular por USB.
#
# Privacidad:
#  - Usa el MODO DEMO de la versión debug: un recorrido simulado por el Centro de
#    Montevideo que IGNORA el GPS real, así no aparece la ubicación de nadie.
#  - Abre la app ANTES de empezar a grabar y descarta el primer medio segundo, así
#    nunca se graba la pantalla de inicio del celular (fondo, apps, notificaciones).
#  - No muestra el buscador (tiene recientes y Casa/Trabajo del dueño del celular).
#  - Recorta la barra de estado (notificaciones) y la barra de navegación.
#  - Al terminar restaura las preferencias (vista 2D/3D, etc.) del usuario.
#  SIEMPRE revisar los cuadros del resultado antes de publicarlo.
#
# Requisitos: la app debug instalada (bash tools/verificar.sh), adb y ffmpeg.
# Uso:  bash tools/grabar_demo.sh
# =============================================================================
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
PKG=com.radiohonda.radaruy
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  else ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; fi
fi
SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')
[ -n "$SERIAL" ] || { echo "Conectá el celular por USB"; exit 1; }
a() { "$ADB" -s "$SERIAL" "$@"; }
espera() { a shell "i=0; while [ \$i -lt $1 ]; do sleep 1; i=\$((i+1)); done"; }
abrir() { a shell am start -n $PKG/.MainActivity "$@" >/dev/null 2>&1; }

# Carpeta temporal relativa dentro del repo (ignorada por git): ruta que entienden
# tanto Git Bash como adb.exe y ffmpeg.exe en Windows.
TMP=verificacion/demo-tmp
rm -rf "$TMP" && mkdir -p "$TMP"

restaurar() {
  a shell am force-stop $PKG
  a shell rm -f /sdcard/uyps-demo.mp4
  if [ -s "$TMP/prefs.xml" ]; then
    a push "$TMP/prefs.xml" /data/local/tmp/radaruy-prefs.xml >/dev/null
    a shell "run-as $PKG sh -c 'cat /data/local/tmp/radaruy-prefs.xml > shared_prefs/radaruy.xml'"
    a shell rm -f /data/local/tmp/radaruy-prefs.xml
  fi
}
trap restaurar EXIT   # pase lo que pase, dejar la app como estaba

echo "== Preparando"
a shell am force-stop $PKG
a shell "run-as $PKG cat shared_prefs/radaruy.xml" > "$TMP/prefs.xml" 2>/dev/null || true
a shell "run-as $PKG sh -c 'rm -f files/*.pmtiles'" || true   # así se ve la pantalla de carga
a shell rm -f /sdcard/uyps-demo.mp4

echo "== Grabando (≈45 s)"
abrir --ez demo true --es vista 2d       # primero la app (carga + navegación en vista noche 2D)
a shell screenrecord --bit-rate 8000000 --time-limit 60 /sdcard/uyps-demo.mp4 &
REC=$!
espera 16
abrir --es vista 3d                      # vista 3D detrás de la flecha
espera 12
abrir --es abrir cerca                   # menú "Cerca mío"
espera 4
abrir --es abrir negocios                # negocios en el mapa
espera 4
abrir --es abrir nada
espera 3
a shell pkill -INT screenrecord || true
wait $REC 2>/dev/null || true
espera 2
a pull /sdcard/uyps-demo.mp4 "$TMP/demo.mp4" >/dev/null || { echo "No se pudo bajar el video"; exit 1; }

echo "== Convirtiendo"
mkdir -p docs
# sin barra de estado (arriba) ni de navegación (abajo); se descarta el primer medio segundo
CROP="crop=iw:ih*0.915:0:ih*0.045"
VF="$CROP,fps=8,scale=280:-1:flags=lanczos"   # ~10 MB para el README
ffmpeg -loglevel error -y -ss 0.5 -i "$TMP/demo.mp4" -vf "$VF,palettegen=max_colors=64:stats_mode=full" "$TMP/pal.png"
ffmpeg -loglevel error -y -ss 0.5 -i "$TMP/demo.mp4" -i "$TMP/pal.png" \
  -lavfi "$VF [x]; [x][1:v] paletteuse=dither=none" docs/demo.gif
ffmpeg -loglevel error -y -ss 0.5 -i "$TMP/demo.mp4" -vf "$CROP,scale=720:-2" \
  -c:v libx264 -crf 28 -preset slow -an -movflags +faststart verificacion/demo.mp4
echo "listo: docs/demo.gif ($(du -h docs/demo.gif | cut -f1)) y verificacion/demo.mp4 ($(du -h verificacion/demo.mp4 | cut -f1), para el Release)"
echo "IMPORTANTE: revisar los cuadros antes de publicar (que no aparezca nada personal)."
