# GPS Uruguay — Radar UY

Navegador GPS **sin conexión** para Uruguay, pensado para usarse en la pantalla de un
**Honda Civic 2017** (Display Audio de 7") a través de **Android Auto**, y también
directamente en el teléfono.

La app no se instala en la radio: corre en el teléfono y Android Auto la proyecta en la
pantalla del auto, igual que Waze o Google Maps (con Spotify al costado en la pantalla dividida).

## Funciones

| | |
|---|---|
| **Mapa de todo Uruguay sin internet** | Estilo "noche" azul oscuro (vista desde arriba) o **vista 3D** detrás de la flecha con edificios en relieve. |
| **Edificios** | Los de OpenStreetMap + 2,4 millones detectados por satélite (Google Open Buildings y Microsoft, vía Overture Maps) para los barrios que faltan en OSM. |
| **Rutas giro a giro sin internet** | Ruta más rápida respetando **sentidos de calles** y **1.804 prohibiciones de giro**. Indicaciones en pantalla y **por voz en español rioplatense** (baja el volumen de Spotify mientras habla). Recalcula si te desviás. |
| **Radares** | 234 radares (fijos, de tramo y de semáforo) con aviso visual y sonoro. |
| **Velocidad y límite** | Velocímetro tipo Waze + cartel con el límite de la calle actual; se pone rojo si te pasás. |
| **Buscador** | 35.000 ciudades, barrios, calles y negocios; destinos recientes. |
| **"Cerca mío"** | Farmacias, monumentos, discotecas, bancos, estaciones de servicio, hospitales, comisarías, shoppings, restaurantes… ordenados por cercanía al abrir el menú, con botón **Ir**. |
| **Negocios en el mapa** | 17 tipos con íconos propios; se elige cuáles mostrar. Tocar uno muestra una tarjeta con **Ir**. |
| **Android Auto** | Tarjeta de indicaciones nativa, búsqueda, "Cerca", filtros, zoom, mover el mapa, cambiar 2D/3D. |

## Requisitos

- Teléfono Android 8.0 o superior (probado en Redmi Note 13 Pro 5G, Android 16).
- Para el auto: Android Auto en el teléfono. El Civic 2017 LX (pantalla de 5") **no** tiene Android Auto; las versiones con pantalla de 7" sí.
- Para compilar: JDK 17+, Android SDK 35 (Gradle 8.11 se descarga solo con `gradlew`).

## Compilar

Los mapas grandes **no están en el repositorio** (pesan más de lo que permite GitHub).
Antes de compilar hay que generarlos (ver [Datos](#datos)) o copiarlos a
`app/src/main/assets/`:

- `uruguay.pmtiles` (187 MB)
- `buildings.pmtiles` (34 MB)

Además crear `local.properties` con la ruta del SDK:

```
sdk.dir=C\:/Users/<usuario>/AppData/Local/Android/Sdk
```

Luego:

```
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # pruebas del motor de rutas con la red vial real
```

## Instalar en el teléfono

1. Activar las **opciones de desarrollador** (tocar 7 veces el número de versión en *Ajustes → Sobre el teléfono*) y la **Depuración USB**.
   - En **Xiaomi / Redmi** además activar **"Instalar vía USB"** (puede pedir cuenta Xiaomi y datos móviles).
2. Conectar por USB y ejecutar:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   (o copiar el APK al teléfono e instalarlo desde el administrador de archivos).
3. Abrir **Radar UY** y aceptar el permiso de ubicación. La primera vez copia los mapas al almacenamiento interno (unos segundos).

## Habilitarla en Android Auto

Como no viene de Play Store, Android Auto la oculta hasta habilitar fuentes desconocidas:

1. Abrir la app **Android Auto** en el teléfono, ir al final y tocar **Versión** 10 veces.
2. Menú ⋮ → **Configuración para desarrolladores** → activar **Fuentes desconocidas**.
3. Conectar al auto: **Radar UY** aparece en el lanzador.

Para probar sin el auto: en Android Auto, menú ⋮ → *Iniciar servidor de unidad principal*, y en la PC
`adb forward tcp:5277 tcp:5277` + `desktop-head-unit.exe` (instalar *Android Auto Desktop Head Unit Emulator* desde el SDK Manager).

## Datos

Todo se genera con los scripts de `tools/` y queda dentro del APK (la app funciona sin internet).

| Archivo en `app/src/main/assets/` | Contenido | Generación | En el repo |
|---|---|---|---|
| `uruguay.pmtiles` | Mapa base (Protomaps / OSM) hasta zoom 15 | `pmtiles_extract.py` | No (187 MB) |
| `buildings.pmtiles` | Edificios satelitales que faltan en OSM, zoom 15 | `download_overture.py` + `build_buildings.py` | No (34 MB) |
| `radars.geojson` | 234 radares | `build_data.py` | Sí |
| `pois.geojson` | ~9.200 negocios en 17 categorías | `build_data.py` | Sí |
| `speedlimits.bin` | 77.000 tramos con límite de velocidad | `build_data.py` | Sí |
| `graph.bin` | Red vial (128.000 cruces, 190.000 tramos, giros prohibidos) | `build_routing.py` | Sí |
| `search.json` | Buscador (lugares, calles, negocios) | `build_routing.py` | Sí |
| `style.json` / `style3d.json` | Estilos del mapa (noche / 3D) | a mano + `make_styles.py` | Sí |
| `fonts/` | Tipografías Noto Sans para las etiquetas | Protomaps basemaps-assets | Sí |

Orden para regenerar todo:

```
pip install requests
python tools/pmtiles_extract.py https://build.protomaps.com/AAAAMMDD.pmtiles data-build/uruguay.pmtiles \
       --bbox=-58.50,-35.10,-53.05,-30.05 --maxzoom 15
cp data-build/uruguay.pmtiles app/src/main/assets/
python tools/build_data.py          # radares, negocios, límites (Overpass API)
python tools/build_routing.py       # grafo de rutas + buscador
python -m venv tools/.venv
tools/.venv/Scripts/pip install -r tools/requirements.txt
tools/.venv/Scripts/python tools/download_overture.py 2026-11-18.0
tools/.venv/Scripts/python tools/build_buildings.py
python tools/make_styles.py
```

Las descargas de OpenStreetMap se guardan en `data-build/` (no se sube). Para actualizar,
borrar `data-build/osm_*.json` y volver a correr los scripts. `AAAAMMDD` es la fecha de un
build diario de Protomaps (por ejemplo `20261120`).

## Limitaciones

- **Límites de velocidad:** solo ~21% de los tramos los tiene cargados en OSM; el resto se **estima** según la Ley 18.191 (45 km/h urbano, 90 km/h rutas nacionales) y el cartel dice "aprox.".
- **Radares:** los de OSM; puede faltar alguno nuevo.
- **Rutas:** sin tránsito en tiempo real; búsqueda por nombre de calle, no por número de puerta (se puede marcar el punto en el mapa).
- **Edificios satelitales:** sin altura real; se dibujan de 5 m.
- En el auto, Android Auto bloquea el teclado mientras se maneja.
- Siempre manda la señalización de la calle.

## Documentación

- [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md): cómo está hecha la app, formatos de datos, algoritmos.
- [docs/CONTEXTO.md](docs/CONTEXTO.md): historia del proyecto, decisiones tomadas y por qué, pendientes.

## Créditos y licencias de datos

- Mapa, calles, radares, negocios: © colaboradores de [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL).
- Teselas vectoriales: [Protomaps](https://protomaps.com) basemap.
- Edificios satelitales: [Overture Maps Foundation](https://overturemaps.org) (Google Open Buildings — CC BY 4.0 / ODbL; Microsoft ML Buildings — ODbL).
- Motor de mapa: [MapLibre Native](https://maplibre.org) · Android for Cars App Library.
