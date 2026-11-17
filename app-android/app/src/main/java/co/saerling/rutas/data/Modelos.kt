package co.saerling.rutas.data

import kotlinx.serialization.Serializable

// Lo que responde el servidor de Rutas (Vercel): los mismos tipos que src/transit/types.ts y src/transit/plan.ts.

@Serializable data class Feed(val asOf: String = "", val live: Boolean = false)

@Serializable data class RouteRef(
    val id: String, val feedCode: String = "", val code: String, val aliases: List<String> = emptyList(),
    val name: String = "", val system: String = "other", val variant: String? = null,
)

@Serializable data class Stop(
    val id: String, val name: String, val kind: String = "stop", val lat: Double, val lon: Double,
    val accessible: Boolean = false, val distanceM: Double? = null, val routes: List<RouteRef> = emptyList(),
)

@Serializable data class Arrival(
    val routeId: String? = null, val code: String, val aliases: List<String> = emptyList(), val name: String = "",
    val system: String = "other", val minutes: Int? = null, val at: String? = null, val basis: String = "scheduled",
    val delaySeconds: Int? = null, val vehicle: String? = null, val plate: String? = null,
)

@Serializable data class Alert(
    val id: String, val effect: String = "", val title: String = "", val detail: String = "",
    val routes: List<String> = emptyList(), val until: String? = null,
)

@Serializable data class Bus(
    val id: String, val lat: Double, val lon: Double, val nextStop: String? = null, val plate: String? = null,
    val reportedAt: String? = null, val code: String? = null, val system: String? = null,
    val bearing: Double? = null, val label: String? = null, val toward: String? = null,
    /** Solo en /api/vehicles: la ruta, la velocidad medida y dónde estaba en el reporte anterior [lon, lat]. */
    val routeId: String? = null, val kmh: Double? = null, val from: List<Double>? = null,
)

/** Cómo va el tráfico en una zona (velocidad de los buses SITP; los troncales van por su carril). */
@Serializable data class CeldaTrafico(val lat: Double, val lon: Double, val kmh: Int, val n: Int = 0, val level: String = "fluido", val near: String = "")
@Serializable data class TraficoZona(val kmh: Int? = null, val n: Int = 0, val level: String? = null, val slow: List<CeldaTrafico> = emptyList())
@Serializable data class Vehiculos(val feed: Feed = Feed(), val buses: List<Bus> = emptyList(), val traffic: TraficoZona = TraficoZona())
@Serializable data class Trafico(val feed: Feed = Feed(), val cells: List<CeldaTrafico> = emptyList(), val kmh: Int? = null, val n: Int = 0)

@Serializable data class StopDetail(val feed: Feed, val stop: Stop, val arrivals: List<Arrival> = emptyList(), val incoming: List<Bus> = emptyList(), val alerts: List<Alert> = emptyList())

@Serializable data class RouteInfo(
    val id: String, val code: String, val name: String = "", val system: String = "other", val aliases: List<String> = emptyList(),
    val firstDeparture: String? = null, val lastDeparture: String? = null,
)

@Serializable data class RouteStop(val id: String, val name: String, val lat: Double, val lon: Double)

@Serializable data class RouteDetail(
    val feed: Feed, val route: RouteInfo, val path: List<List<Double>> = emptyList(), val stops: List<RouteStop> = emptyList(),
    val buses: List<Bus> = emptyList(), val alerts: List<Alert> = emptyList(),
    val variants: List<Variante> = emptyList(),
)

/** Un sentido de la ruta: hacia dónde va, sus paradas, su trazo y las salidas de hoy. */
@Serializable data class Variante(val toward: String, val stops: List<RouteStop> = emptyList(), val path: List<List<Double>> = emptyList(), val departures: List<String> = emptyList())

@Serializable data class Novedades(val alerts: List<Alert> = emptyList())

@Serializable data class Nearby(val stops: List<Stop> = emptyList())
@Serializable data class SearchResult(val stops: List<Stop> = emptyList(), val routes: List<RouteRef> = emptyList())
@Serializable data class Health(val feed: Feed)

// Planeador

@Serializable data class Place(val id: String, val name: String, val area: String? = null, val kind: String = "place", val lat: Double, val lon: Double)
@Serializable data class LegStop(val name: String, val lat: Double, val lon: Double, val at: String? = null)
@Serializable data class Step(val street: String = "", val metres: Int = 0, val turn: String = "CONTINUE")

@Serializable data class Leg(
    val mode: String, val from: LegStop, val to: LegStop, val start: String, val end: String, val live: Boolean = false,
    val minutes: Int = 1, val metres: Double? = null, val code: String? = null, val system: String? = null,
    val headsign: String? = null, val stops: List<LegStop> = emptyList(), val path: List<List<Double>> = emptyList(),
    val steps: List<Step> = emptyList(),
)

@Serializable data class Itinerary(
    val id: String, val start: String, val end: String, val minutes: Int, val transfers: Int, val walkMetres: Int,
    val live: Boolean = false, val legs: List<Leg>,
)

@Serializable data class PlanResult(val itineraries: List<Itinerary> = emptyList(), val walk: Itinerary? = null, val next: String? = null)

/**
 * TransMilenio publica las rutas SITP en pareja con un solo código (DK211 = D211 y K211). Se muestra la que lleva el
 * letrero del bus (su destino); si no se sabe, las dos, como en Cerca.
 */
fun codigoVisible(code: String?, destino: String? = null): String {
    val c = code?.trim()?.uppercase() ?: return "?"
    val m = Regex("^([A-Z])([A-Z])(\\d{2,4}[A-Z]?)$").find(c) ?: return c
    val (a, b, n) = m.destructured
    val d = destino?.trim()?.uppercase()
    return when (d) { "$a$n" -> "$a$n"; "$b$n" -> "$b$n"; else -> "$b$n·$a$n" }
}

/** El destino que se escribe tras «hacia», sin repetir el propio código. */
fun destinoVisible(l: Leg): String? {
    val d = l.headsign?.trim().orEmpty()
    if (d.isEmpty()) return null
    val code = l.code?.trim()?.uppercase().orEmpty()
    return if (d.uppercase() == code || d.uppercase() == codigoVisible(code, d)) null else d
}

/** Pasos a pie más legibles: une los seguidos por la misma calle y quita los de pocos metros. */
fun Leg.pasosLimpios(): List<Step> {
    val out = mutableListOf<Step>()
    for (s in steps) {
        val prev = out.lastOrNull()
        if (prev != null && (s.turn == "CONTINUE" || s.turn == prev.turn) && s.street == prev.street) out[out.lastIndex] = prev.copy(metres = prev.metres + s.metres)
        else out += s
    }
    return out.filter { it.metres >= 25 || it.turn != "CONTINUE" }
}

/** Tarifa única del SITP en 2026 (troncal, zonal y TransMiCable). */
const val PASAJE_COP = 3550

/** Pasaje aproximado: uno por cada vez que se paga al subir. Los troncales seguidos dentro de la estación cuentan una vez; los alimentadores no se pagan. */
fun Itinerary.pasaje(): Int {
    var entradas = 0
    var enEstacion = false
    for (l in legs) {
        if (l.mode != "transit") { if (enEstacion && (l.metres ?: 0.0) > 150) enEstacion = false; continue }
        val troncal = l.system == "troncal" || l.system == "dual"
        if (l.system == "alimentador") continue
        if (troncal && enEstacion) continue
        entradas++
        enEstacion = troncal
    }
    return entradas * PASAJE_COP
}
