# Contexto del proyecto

## Objetivo

Tener en la pantalla del **Honda Civic 2017** (Display Audio 7", con Android Auto y Apple
CarPlay) un navegador propio para Uruguay: mapa sin conexión con estilo nocturno, radares,
límites de velocidad, velocidad actual tipo Waze, negocios filtrables y navegación giro a giro,
compartiendo la pantalla con Spotify.

## Decisiones y por qué

| Decisión | Motivo |
|---|---|
| **Android Auto, no instalar en la radio** | Honda no publica un SDK para la Display Audio. Modificarla (Android viejo) es frágil, puede inutilizar la radio y las actualizaciones de Honda lo bloquean. Android Auto es oficial y estable. |
| **Car App Library con `NavigationTemplate`** | Es la única forma permitida de dibujar un mapa propio en Android Auto y entrar en la pantalla dividida (mapa + tarjeta de Spotify) como Waze/Maps. |
| **MapLibre en un `VirtualDisplay`** | MapLibre no tiene soporte oficial para Android Auto; se dibuja el `MapView` en una pantalla virtual sobre la superficie que entrega el auto. |
| **Todo sin conexión (PMTiles en el APK)** | Funciona en ruta sin señal y sin costo de datos. |
| **Extractor PMTiles propio en Python** | El entorno no permitía ejecutar el binario `pmtiles` descargado; se reimplementó la extracción por rangos HTTP (`tools/pmtiles_extract.py`). |
| **Mapa hasta zoom 15** | En zoom 14 Protomaps junta/descarta los edificios chicos (26 vs 5.340 en la misma zona del Centro). |
| **Edificios satelitales de Overture** | Muchos barrios de Uruguay no tienen edificios en OSM. Overture combina OSM con Google Open Buildings y Microsoft; se agregan solo los que no son de OSM y solo dentro del contorno de Uruguay. |
| **Motor de rutas propio (A\* por arcos)** | Librerías como GraphHopper/Valhalla son pesadas o complicadas en Android. Con 128.000 nodos, A\* en Kotlin calcula Montevideo→Salto en ~0,1 s. Se pasó de A\* por nodos a **por arcos** para poder respetar giros prohibidos. |
| **Íconos dibujados con Canvas** | Sin archivos de imagen ni sprites; fácil de ajustar al estilo. |
| **Límites estimados con la Ley 18.191** | OSM solo tiene ~21% de tramos con `maxspeed`. Calles residenciales 45, rutas nacionales (con `ref`) 90, avenidas sin número 45. Se marcan como "aprox.". |
| **Ruta cian en 2D, rosa en 3D** | Cian según la referencia del estilo nocturno; rosa según la referencia de la vista 3D. |

## Historia (resumen)

1. Investigación: no hay SDK de Honda → app de Android Auto.
2. Mapa nocturno sin conexión + radares + límites + velocímetro + filtro de negocios.
3. Navegación giro a giro con voz, búsqueda de destinos, recálculo.
4. Menú "Cerca mío" con nuevas categorías (monumentos, discotecas, comisarías, shoppings).
5. Sentidos de calles verificados y 1.804 prohibiciones de giro aplicadas.
6. Instalación en un Redmi Note 13 Pro 5G (Xiaomi requiere "Instalar vía USB").
7. Vista 3D detrás de la flecha, tocar negocios para ir, menús nuevos.
8. Íconos en baldosas, 3D con colores de noche, mapa a zoom 15 y edificios satelitales.
9. Nueva marca: la app pasa a llamarse **UYPS — Uruguay Positioning System**, con logo y spinner propios (`design/`). El paquete sigue siendo `com.radiohonda.radaruy` para no perder datos ni instalaciones.
10. Casa y Trabajo como destinos guardados; spinner de UYPS en la carga y al calcular rutas.
11. Repo público (sin email personal en el historial). Demo pública: GIF en el README + APK en el Release, grabada con el modo demo para no mostrar la ubicación real del dueño del celular.

## Estado

- Probado en el teléfono (capturas): mapa, vistas 2D/3D, edificios, GPS, límite, menús.
- Motor de rutas probado con pruebas unitarias sobre la red vial real.
- **No probado todavía en el Civic / Android Auto** ni en un viaje real (voz, recálculo, radares en movimiento).

## Pendientes e ideas

- Probar en el auto (o con el Desktop Head Unit) el dibujo del mapa en el `VirtualDisplay`.
- MapLibre muestra el aviso `data expressions not supported` al cargar un estilo; el mapa se dibuja bien, falta identificar la capa.
- Búsqueda por dirección con número de puerta (OSM tiene pocas direcciones en Uruguay).
- Alternativas de ruta, evitar peajes.
- Los mapas pesados (`*.pmtiles`) no están en git: publicarlos como archivos de un *Release* de GitHub.
- La app copia los mapas al almacenamiento interno (duplica ~220 MB); se podría leer directo del APK si MapLibre lo permite.

## Notas del entorno de desarrollo

- Windows 11, JDK 20 (sirve para AGP 8.7), Android SDK en `%LOCALAPPDATA%\Android\Sdk`.
- En Git Bash, usar `MSYS_NO_PATHCONV=1` con `adb shell`/`adb push` para que no convierta rutas como `/sdcard/...`.
- El catálogo STAC de Overture falló con un error de certificado desde Python; `tools/download_overture.py` lee S3 directo indicando la versión.
- Xiaomi no deja simular toques por adb sin "Depuración USB (ajustes de seguridad)"; para capturas de menús usar el extra `--es abrir …` (ver ARQUITECTURA).
