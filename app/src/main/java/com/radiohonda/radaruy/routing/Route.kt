package com.radiohonda.radaruy.routing

/** Una indicación de la ruta ("Doblá a la derecha en Av. Italia"). */
data class Maneuver(
    val type: Type,
    /** Índice del punto de la ruta donde ocurre. */
    val pointIndex: Int,
    /** Metros desde el inicio de la ruta. */
    val distAlong: Double,
    /** Calle a la que se entra. */
    val road: String,
    /** Número de salida en rotondas. */
    val exit: Int = 0,
) {
    enum class Type {
        DEPART, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT,
        UTURN, ROUNDABOUT, RAMP_LEFT, RAMP_RIGHT, ARRIVE
    }

    private val onto get() = if (road.isNotBlank()) road else ""

    /** Texto para la pantalla. */
    fun text(): String = when (type) {
        Type.DEPART -> if (onto.isNotEmpty()) "Salí por $onto" else "Comenzá el recorrido"
        Type.STRAIGHT -> "Seguí por $onto"
        Type.SLIGHT_LEFT -> "Girá levemente a la izquierda" + en()
        Type.SLIGHT_RIGHT -> "Girá levemente a la derecha" + en()
        Type.LEFT -> "Doblá a la izquierda" + en()
        Type.RIGHT -> "Doblá a la derecha" + en()
        Type.SHARP_LEFT -> "Doblá completamente a la izquierda" + en()
        Type.SHARP_RIGHT -> "Doblá completamente a la derecha" + en()
        Type.UTURN -> "Hacé un retorno" + en()
        Type.ROUNDABOUT -> "En la rotonda, tomá la ${exit}.ª salida" + hacia()
        Type.RAMP_LEFT -> "Tomá la salida a la izquierda" + hacia()
        Type.RAMP_RIGHT -> "Tomá la salida a la derecha" + hacia()
        Type.ARRIVE -> "Llegaste a destino"
    }

    /** Texto para la voz (sin abreviaturas). */
    fun speech(): String = when (type) {
        Type.ROUNDABOUT -> "En la rotonda, tomá la ${ordinal(exit)} salida" + hacia()
        else -> text()
    }.replace("Av. ", "Avenida ").replace("Bv. ", "Bulevar ").replace("Gral. ", "General ")
        .replace("Dr. ", "Doctor ").replace("Ing. ", "Ingeniero ").replace("Cno. ", "Camino ")

    private fun en() = if (onto.isNotEmpty()) " en $onto" else ""
    private fun hacia() = if (onto.isNotEmpty()) " hacia $onto" else ""

    companion object {
        fun ordinal(n: Int) = when (n) {
            1 -> "primera"; 2 -> "segunda"; 3 -> "tercera"; 4 -> "cuarta"; 5 -> "quinta"
            6 -> "sexta"; 7 -> "séptima"; 8 -> "octava"; else -> "$n.ª"
        }
    }
}

/** Ruta calculada: polilínea con distancias y tiempos acumulados, más las indicaciones. */
class Route(
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** Metros acumulados en cada punto. */
    val cumDist: DoubleArray,
    /** Segundos acumulados en cada punto. */
    val cumTime: DoubleArray,
    /** Límite de velocidad del tramo que empieza en cada punto. */
    val limits: IntArray,
    val maneuvers: List<Maneuver>,
    val destName: String,
    val destLat: Double,
    val destLon: Double,
    /** Calles recorridas (aristas del grafo) y en qué sentido, en orden. */
    val legEdges: IntArray = IntArray(0),
    val legForward: BooleanArray = BooleanArray(0),
) {
    val size get() = lat.size
    val totalDistance get() = cumDist[size - 1]
    val totalTime get() = cumTime[size - 1]

    /** Tiempo acumulado a [along] metros del inicio. */
    fun timeAt(along: Double, fromIndex: Int): Double {
        var i = fromIndex.coerceIn(0, size - 2)
        while (i < size - 2 && cumDist[i + 1] < along) i++
        val d = cumDist[i + 1] - cumDist[i]
        val t = if (d > 0) ((along - cumDist[i]) / d).coerceIn(0.0, 1.0) else 0.0
        return cumTime[i] + t * (cumTime[i + 1] - cumTime[i])
    }
}
