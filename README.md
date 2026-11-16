# Rutas

<img src="assets/images/icon.png" width="96" alt="Icono de Rutas">

**Cómo llegar en Bogotá, con tu bus en vivo.** App nativa de Android (Kotlin + Jetpack Compose) con la estética y las animaciones de [Mochy Music](https://github.com/realidkroo/Mochy-Music), funciones tipo Moovit y los datos abiertos de TransMilenio y SITP.

## Descargar (Android)

**[⬇️ Descargar la última versión](https://github.com/Saerling/Rutas/releases/latest)**: baja `Rutas-0.2.X.apk` y ábrelo para instalar.

Cada cambio en `main` compila un APK nuevo. La app avisa sola cuando hay versión nueva (o en *Ajustes → Buscar actualizaciones*), la descarga **dentro de la app** y la instala encima sin perder tus guardados.

## Qué hace

| Función | |
| --- | --- |
| Paraderos y estaciones cerca, en el mapa y en lista | ✅ |
| Próximos buses por paradero, en vivo o según horario, con filtro por ruta | ✅ |
| Recorrido de cada ruta con sus paradas y buses en vía | ✅ |
| **Planeador A → B** con transbordos, caminata, salir más tarde o llegar a una hora, accesible, menos transbordos | ✅ |
| Pasaje aproximado de cada opción (tarifa única 2026: $3.550) | ✅ |
| **Viaje en vivo**: cuenta las paradas y avisa «bájate en la próxima» | ✅ |
| Casa, trabajo, lugares recientes, paraderos y rutas guardados | ✅ |
| **Mapa de Bogotá sin conexión** (Ajustes → Mapa sin conexión) | ✅ |
| Alertas de servicio de TransMilenio | ✅ |
| **Personalización** de Mochy: tema y negro puro, acento, cristal, fondo, esquinas, velocidad de animación, animación de inicio | ✅ |
| Actualizaciones descargadas e instaladas desde la app | ✅ |

## Cómo está hecha

- `app-android/`: la app de Android en Kotlin y Jetpack Compose. La capa de diseño (`ui/mochy`, cristal, movimiento, Personalización, hoja de actualización) viene de Mochy Music a través de Shiori. Las capturas de cada pantalla salen en el CI (artefacto «capturas»).
- `src/`: el servidor (rutas `/api/*` de Expo Router, desplegado en Vercel) que lee directo los datos abiertos de TransMilenio: el GTFS oficial (índice diario armado por `scripts/red.py` y publicado en la versión `red`) y sus feeds de GTFS Realtime (`gtfs.transmilenio.gov.co`). Las llegadas en vivo se calculan con la posición de cada bus y los tiempos del horario. El planeador usa [Transitous](https://transitous.org). No necesita llaves ni créditos.

```bash
cd app-android && gradle :app:assembleRelease        # APK
cd app-android && gradle :app:testDebugUnitTest      # capturas en app/build/capturas
```

## Créditos y licencia

Basada en [Paradero](https://github.com/croma-labs/paradero) de Croma (su licencia MIT está en `LICENSE-PARADERO-MIT`) y en el diseño de Mochy Music (GPL-3.0). Ver [CREDITOS.md](CREDITOS.md). Rutas se distribuye bajo **GPL-3.0**.

Datos de TransMilenio S.A. Mapas © [OpenStreetMap](https://www.openstreetmap.org/copyright) y [CARTO](https://carto.com/attributions). Rutas planeadas con [Transitous](https://transitous.org/sources/).
