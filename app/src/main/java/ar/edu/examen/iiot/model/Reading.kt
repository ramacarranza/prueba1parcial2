package ar.edu.examen.iiot.model

data class Reading(
    val id: Long = 0,
    val machineId: String,
    val machineName: String,
    val temperature: Double,
    val humidity: Double,
    val vibration: Double,
    val timestamp: Long,
    val source: String
)

/**
 * Única fuente de verdad de la regla de alarma. La UI y el ViewModel la usan
 * en lugar de repetir el valor 65.0, así un umbral distinto se aplica en todos lados.
 */
object PlantRules {
    const val DEFAULT_TEMPERATURE_LIMIT = 65.0

    fun requiresAttention(
        reading: Reading,
        threshold: Double = DEFAULT_TEMPERATURE_LIMIT
    ): Boolean = reading.temperature >= threshold

    fun countAlerts(readings: List<Reading>, threshold: Double): Int =
        readings.count { requiresAttention(it, threshold) }
}
