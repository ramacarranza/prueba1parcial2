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
    const val MIN_THRESHOLD = 30.0
    const val MAX_THRESHOLD = 120.0

    /** Valida el texto ingresado por el usuario; acepta coma o punto decimal. */
    fun validateThreshold(input: String): ThresholdValidation {
        val value = input.trim().replace(',', '.').toDoubleOrNull()
            ?: return ThresholdValidation.Invalid("Ingresá un número (por ejemplo 70)")
        if (value < MIN_THRESHOLD || value > MAX_THRESHOLD) {
            return ThresholdValidation.Invalid(
                "El umbral debe estar entre ${MIN_THRESHOLD.toInt()} y ${MAX_THRESHOLD.toInt()} °C"
            )
        }
        return ThresholdValidation.Valid(value)
    }

    fun isValidThreshold(value: Double): Boolean = value in MIN_THRESHOLD..MAX_THRESHOLD

    fun requiresAttention(
        reading: Reading,
        threshold: Double = DEFAULT_TEMPERATURE_LIMIT
    ): Boolean = reading.temperature >= threshold

    fun countAlerts(readings: List<Reading>, threshold: Double): Int =
        readings.count { requiresAttention(it, threshold) }
}

sealed interface ThresholdValidation {
    data class Valid(val value: Double) : ThresholdValidation
    data class Invalid(val message: String) : ThresholdValidation
}
