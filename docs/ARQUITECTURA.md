# Arquitectura

App Android en Kotlin, un solo módulo (`app`), paquete `com.radiohonda.radaruy`.
Sin dependencias de Google Play Services. Todo funciona sin internet.

```
                 ┌────────────── teléfono ──────────────┐     ┌──── Android Auto ────┐
 GPS ──► Drive ──┤ MainActivity (MapView + HudView)     │     │ RadarSession          │
          │      │   menús: Ui.Sheet                    │     │  MapScreen (NavigationTemplate)
          │      └──────────────────────────────────────┘     │  CarMapRenderer       │
          │                     ▲                             │   VirtualDisplay +    │
          ▼                     │ MapController               │   Presentation +      │
     Navigator ─────────────────┴──── (mapa MapLibre) ────────┤   MapView + HudView   │
      Router / RoadGraph / Maneuvers / Voice                  └───────────────────────┘
```

## Dependencias

| Librería | Versión | Para qué |
|---|---|---|
| `org.maplibre.gl:android-sdk` | 11.13.5 | Mapa vectorial, lectura de PMTiles locales (`pmtiles://file://…`) |
| `androidx.car.app:app` | 1.4.0 | Android Auto (plantillas de navegación) |
| `androidx.core:core-ktx` | 1.13.1 | Utilidades |

Build: AGP 8.7.3, Kotlin 2.1.0, compileSdk/targetSdk 35, minSdk 26. Solo se empaquetan
librerías nativas `arm64-v8a` y `armeabi-v7a` (sin x86) para achicar el APK.

## Paquetes y clases

### Núcleo (`com.radiohonda.radaruy`)

| Clase | Responsabilidad |
|---|---|
| `Drive` | Singleton. Escucha el GPS (`LocationManager`), calcula velocidad, límite de la calle (`SpeedLimitIndex`), radar próximo (`RadarIndex`), recorrido reciente; emite `Drive.State`. Cuenta usuarios (teléfono/auto) con `acquire/release`. Pitidos de radar. |
| `DriveService` | Servicio en primer plano (tipo `location`) para mantener el GPS con la pantalla apagada. |
| `MapController` | Configura un `MapLibreMap`: carga `style.json` o `style3d.json`, agrega íconos, capas de ruta, destino, negocios, radares y la flecha; sigue al auto con la cámara; detecta toques en negocios (`poiAt`). Lo usan teléfono y auto. |
| `MapFiles` | Copia los `.pmtiles` del APK al almacenamiento interno (MapLibre necesita archivos) y arma el JSON del estilo con las rutas. |
| `HudView` | Vista dibujada a mano sobre el mapa: velocímetro, cartel de límite, aviso de radar y (en el teléfono) el cartel de indicaciones y la hora de llegada. Respeta el área visible de Android Auto. |
| `Icons` | Todos los íconos se dibujan con `Canvas` (sin imágenes): negocios en baldosas, radares, flecha, destino, flechas de maniobra, íconos de botones. |
| `Ui` | Componentes de interfaz del teléfono con el estilo del mapa: hojas inferiores (`Sheet`), filas con botón **Ir**, interruptores, botones redondos, chips. |
| `MainActivity` | Pantalla del teléfono. Para pruebas por adb (solo debug): `am start -n com.radiohonda.radaruy/.MainActivity --es abrir negocios|cerca|buscar|casa|nada`, `--es vista 2d|3d`, `--ez demo true`. Es `singleTop`: los extras llegan también con la app abierta. |
| `DemoDriver` | Modo demo (solo debug, `--ez demo true` por adb): simula un auto que va de 18 de Julio al Palacio Legislativo; `Drive` ignora el GPS real mientras está activo. Se usa para grabar la demo pública sin mostrar ubicaciones reales. |
| `Prefs` | Preferencias compartidas: categorías visibles, sonido, vista 3D, destinos recientes. |
| `PoiCategory` | Las 17 categorías de negocios (clave, nombre, color) y el orden del menú "Cerca mío". |
| `NearbyIndex` | Lugares por categoría ordenados por distancia (para "Cerca mío"). |
| `SpeedLimitIndex` | Índice espacial (grilla de 0,004°) sobre `speedlimits.bin`; devuelve el límite de la calle más cercana favoreciendo la que va en el rumbo del auto. |
| `RadarIndex` | Radar más cercano delante del auto (≤ 800 m, ±35° del rumbo). |

### Rutas (`routing`)

| Clase | Responsabilidad |
|---|---|
| `RoadGraph` | Carga `graph.bin`: nodos, aristas, geometría, nombres, **giros prohibidos**. Adyacencia CSR por arcos (arista × sentido), grilla para "pegar" un punto a la calle más cercana, rumbos de entrada/salida de cada arista y costo de giro. |
| `Router` | **A\* por arcos** (cada estado es una calle recorrida en un sentido) → permite aplicar giros prohibidos y costo de giro. Arranque y llegada a mitad de cuadra. Penaliza arrancar en contra del rumbo actual. |
| `Maneuvers` | Convierte la secuencia de calles en indicaciones: giros por ángulo (solo en cruces con ≥3 calles), rotondas con número de salida, salidas de autopista, fusión de giros dobles en avenidas con cantero. |
| `Route` / `Maneuver` | Polilínea con distancias y tiempos acumulados, indicaciones con texto para pantalla y para voz. |
| `Navigator` | Navegación en curso: proyecta el GPS sobre la ruta, distancia a la próxima maniobra, hora de llegada, detecta desvíos (>45 m tres veces) y recalcula, anuncia por voz a distancia lejana y cercana. |
| `Voice` | Text-to-Speech en español (es-UY) con foco de audio `MAY_DUCK` (baja Spotify). |
| `SearchIndex` | Búsqueda sin acentos sobre `search.json`, ordenada por coincidencia y cercanía. |
| `Geo` | Distancia haversine (sin dependencias de Android, para poder probar en la JVM). |

### Android Auto (`car`)

| Clase | Responsabilidad |
|---|---|
| `RadarCarAppService` / `RadarSession` | Punto de entrada (categoría `NAVIGATION`). Acepta cualquier host (uso personal). |
| `CarMapRenderer` | Recibe la superficie del auto, crea un `VirtualDisplay` y muestra un `Presentation` con un `MapView` (modo textura) + `HudView`. Maneja área visible (pantalla dividida), arrastre, zoom y toques. |
| `MapScreen` | `NavigationTemplate`: botones (Buscar/Salir, Cerca, Negocios, centrar), zoom, 2D/3D, tarjeta de indicaciones (`RoutingInfo`) y hora de llegada (`TravelEstimate`). Avisa `navigationStarted/Ended`. |
| `SearchScreen` | `SearchTemplate` con recientes. |
| `NearbyScreen` | Menú "Cerca" y lista de lugares por cercanía. |
| `CategoriesScreen` | Interruptores de negocios visibles. |
| `PlaceScreen` | Tarjeta de un negocio tocado en el mapa con **Ir**. |

## Formatos de datos

Todos los binarios son little-endian.

### `graph.bin` (v2)

```
"RGRF"  u32 versión=2  u32 nNodos  u32 nAristas  u32 nGeom  u32 nNombres
i32[nNodos] lat×1e6      i32[nNodos] lon×1e6
i32[m] desde   i32[m] hasta   f32[m] longitud (m)
i32[m] índiceNombre   i32[m] inicioGeom   u16[m] cantGeom
u8[m] velocidad de viaje (km/h)   u8[m] límite (km/h)
u8[m] flags (1 = mano única, 2 = rotonda, 4 = rampa/enlace)   u8[m] clase de vía
i32[nGeom] lat×1e6   i32[nGeom] lon×1e6          (puntos intermedios)
nNombres × (u16 largo + UTF-8)
u32 nGiros  i32[] entrada  i32[] salida  i32[] nodo          (giros prohibidos)
u32 nSecuencias  i32[] entrada  i32[] intermedia  i32[] salida
```

Las aristas de mano única ya vienen orientadas en su sentido (`oneway=-1` se invierte al generar).
`only_*` se expande a "prohibido todo lo demás" al generar.

### `speedlimits.bin`

```
"SPDL"  u32 versión=1  u32 nTramos
por tramo: u8 límite  u8 flags (1 = estimado, 2 = mano única)  u16 nPuntos  nPuntos × (i32 lat×1e6, i32 lon×1e6)
```

### `search.json`

Lista de `[nombre, tipo (0 lugar, 1 calle, 2 negocio), subtítulo, lat, lon, rango]`.

### `buildings.pmtiles`

PMTiles v3, una sola capa vectorial `buildings` en zoom 15, polígonos con propiedad `height`.
Cada edificio se asigna al tile de su centroide (sin recortar).

## Estilos

- `style.json`: noche 2D. Fuente `base` = `pmtiles://{PMTILES}` (esquema Protomaps v4: `earth`, `landcover`, `landuse`, `water`, `roads`, `buildings`, `places`, `boundaries`), fuente `ml` = `pmtiles://{BUILDINGS}`. Glifos en `asset://fonts/{fontstack}/{range}.pbf` (nombres sin espacios: `NotoSansRegular`, `NotoSansMedium`, `NotoSansItalic`).
- `style3d.json`: lo genera `tools/make_styles.py` a partir de `style.json` (mismos colores, edificios como `fill-extrusion` con luz).
- `MapController` agrega en tiempo de ejecución: ruta (cian en 2D, rosa en 3D), recorrido, negocios, radares, destino y flecha.

## Pruebas

`app/src/test/.../RouterTest.kt` (JVM, sin emulador) carga `graph.bin` real y verifica:

- rutas conocidas (Montevideo → Punta del Este / Salto, Pocitos → Aeropuerto…) con distancias razonables;
- 300 rutas al azar en Montevideo: nunca a contramano ni por un giro prohibido;
- 150 giros prohibidos reales: la ruta los evita.
