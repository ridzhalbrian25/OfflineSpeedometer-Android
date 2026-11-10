package com.example.offlinespeedometer.model

enum class SpeedUnit(
    val label: String,
    val unitText: String,
    val distanceUnitText: String,
    val speedMultiplier: Float,     // dari m/s
    val distanceMultiplier: Double, // dari meter
    val maxGaugeValue: Int
) {
    METRIC("KM/H", "Kmh", "Km", 3.6f, 0.001, 220),
    IMPERIAL("MPH", "Mph", "Mi", 2.236936f, 0.000621371, 140),
    KNOTS("KNOTS", "Kn", "Nm", 1.943844f, 0.000539957, 120)
}

enum class NavigationTab(val title: String) {
    ANALOG("Analog"),
    DIGITAL("Digital"),
    MAP("Map")
}
