#!/usr/bin/env bash
# =============================================================================
# Verificación OBLIGATORIA antes de abrir un PR, publicar un Release o subir
# cambios a main (ver CLAUDE.md -> "Regla: probar en el celular").
#
#   1. Pruebas unitarias (motor de rutas sobre la red vial real)
#   2. Compilación del APK
#   3. Celular conectado por USB (si no hay, FALLA)
#   4. Instala la app, la abre y revisa que no se cierre
#   5. Abre cada menú (Negocios, Cerca mío, Buscar) y revisa que no falle
#   6. Guarda capturas en verificacion/ para revisarlas
#
# Uso (Git Bash, desde la raíz del repo):   bash tools/verificar.sh
# Sale con código 0 solo si todo pasó.
# =============================================================================
set -u
export MSYS_NO_PATHCONV=1   # que Git Bash no convierta rutas como /sdcard/...

cd "$(dirname "$0")/.."
PKG=com.radiohonda.radaruy
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=verificacion
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -n "${LOCALAPPDATA:-}" ] && [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  elif [ -n "${ANDROID_HOME:-}" ]; then ADB="$ANDROID_HOME/platform-tools/adb"
  else echo "No se encontró adb"; exit 1; fi
fi

FALLAS=0
ok()   { echo "  ✔ $1"; }
mal()  { echo "  ✘ $1"; FALLAS=$((FALLAS + 1)); }
paso() { echo; echo "== $1"; }
espera() { a shell "i=0; while [ \$i -lt $1 ]; do sleep 1; i=\$((i+1)); done"; }

paso "1/6 Datos necesarios"
for f in uruguay.pmtiles buildings.pmtiles graph.bin speedlimits.bin search.json pois.geojson radars.geojson; do
  if [ -s "app/src/main/assets/$f" ]; then ok "$f"; else mal "falta app/src/main/assets/$f (ver README -> Datos o el Release)"; fi
done
[ $FALLAS -gt 0 ] && { echo; echo "VERIFICACIÓN FALLIDA: faltan datos"; exit 1; }

paso "2/6 Pruebas unitarias"
if ./gradlew testDebugUnitTest --console=plain -q; then ok "pruebas del motor de rutas"; else mal "fallaron las pruebas"; fi

paso "3/6 Compilación"
if ./gradlew assembleDebug --console=plain -q && [ -s "$APK" ]; then ok "APK $(du -h "$APK" | cut -f1)"; else mal "no compiló"; fi
[ $FALLAS -gt 0 ] && { echo; echo "VERIFICACIÓN FALLIDA"; exit 1; }

paso "4/6 Celular conectado"
DEVS=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
N=$(echo "$DEVS" | grep -c . || true)
if [ "$N" -eq 0 ]; then
  mal "no hay ningún celular conectado con Depuración USB (es obligatorio para esta verificación)"
  echo; echo "VERIFICACIÓN FALLIDA: conectá el celular y volvé a correr el script"; exit 1
fi
if [ "$N" -gt 1 ]; then echo "  (hay $N dispositivos; uso el primero)"; fi
SERIAL=$(echo "$DEVS" | head -1)
a() { "$ADB" -s "$SERIAL" "$@"; }
MODELO=$(a shell getprop ro.product.model | tr -d '\r')
ANDROID=$(a shell getprop ro.build.version.release | tr -d '\r')
ok "$MODELO (Android $ANDROID)"

paso "5/6 Instalar y abrir"
if a install -r "$APK" 2>&1 | grep -q Success; then ok "instalada"; else mal "no se pudo instalar (en Xiaomi activar 'Instalar vía USB')"; fi
mkdir -p "$OUT"
a logcat -c
a shell am force-stop $PKG
a shell am start -n $PKG/.MainActivity >/dev/null
espera 12
if [ -n "$(a shell pidof $PKG | tr -d '\r')" ]; then ok "la app sigue abierta después de 12 s"; else mal "la app se cerró al abrir"; fi
a exec-out screencap -p > "$OUT/01-mapa.png" && ok "captura $OUT/01-mapa.png"

paso "6/6 Menús"
i=2
for menu in negocios cerca buscar; do
  a shell am force-stop $PKG
  a shell am start -n $PKG/.MainActivity --es abrir $menu >/dev/null
  espera 7
  if [ -n "$(a shell pidof $PKG | tr -d '\r')" ]; then ok "menú '$menu'"; else mal "la app se cerró al abrir el menú '$menu'"; fi
  a exec-out screencap -p > "$OUT/0$i-$menu.png"
  i=$((i + 1))
done
a shell am force-stop $PKG

CRASH=$(a logcat -d -b crash 2>/dev/null | grep -A 15 "Process: $PKG" || true)
if [ -z "$CRASH" ]; then CRASH=$(a logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | grep -A 15 "$PKG" || true); fi
if [ -n "$CRASH" ]; then mal "hubo errores graves en el registro:"; echo "$CRASH" | head -30 | sed 's/^/      /'; else ok "sin errores graves en el registro"; fi

echo
if [ $FALLAS -eq 0 ]; then
  echo "VERIFICACIÓN OK en $MODELO — revisá las capturas en $OUT/ antes de abrir el PR"
  exit 0
else
  echo "VERIFICACIÓN FALLIDA ($FALLAS problemas)"
  exit 1
fi
