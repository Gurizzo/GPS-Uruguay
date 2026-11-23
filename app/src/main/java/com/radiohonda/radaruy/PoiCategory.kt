package com.radiohonda.radaruy

/** Tipos de negocio que se pueden mostrar. La [key] coincide con "cat" en pois.geojson. */
enum class PoiCategory(val key: String, val label: String, val color: Int) {
    FUEL("fuel", "Estaciones de servicio", 0xFFFFB74D.toInt()),
    CHARGING("charging", "Carga eléctrica", 0xFF4DD0E1.toInt()),
    RESTAURANT("restaurant", "Restaurantes", 0xFFFF8A65.toInt()),
    FAST_FOOD("fast_food", "Comida rápida", 0xFFFFD54F.toInt()),
    CAFE("cafe", "Cafeterías y panaderías", 0xFFBCAAA4.toInt()),
    BAR("bar", "Bares", 0xFFCE93D8.toInt()),
    SUPERMARKET("supermarket", "Supermercados y almacenes", 0xFF81C784.toInt()),
    PHARMACY("pharmacy", "Farmacias", 0xFF66BB6A.toInt()),
    BANK("bank", "Bancos y cajeros", 0xFF64B5F6.toInt()),
    PARKING("parking", "Estacionamientos", 0xFF7986CB.toInt()),
    HEALTH("health", "Hospitales y emergencias", 0xFFE57373.toInt()),
    HOTEL("hotel", "Hoteles", 0xFF9575CD.toInt()),
    CAR_REPAIR("car_repair", "Talleres y gomerías", 0xFF90A4AE.toInt()),
    MONUMENT("monument", "Monumentos y turismo", 0xFFFFE082.toInt()),
    NIGHTCLUB("nightclub", "Discotecas", 0xFFF06292.toInt()),
    POLICE("police", "Comisarías", 0xFF4FC3F7.toInt()),
    MALL("mall", "Shoppings", 0xFFAED581.toInt());

    companion object {
        /** Orden del menú "Cerca mío": primero los más pedidos. */
        val nearbyOrder: List<PoiCategory> = listOf(
            PHARMACY, MONUMENT, NIGHTCLUB, BANK, FUEL, HEALTH, POLICE, MALL, RESTAURANT,
        ).let { first -> first + entries.filter { it !in first } }
    }
}
