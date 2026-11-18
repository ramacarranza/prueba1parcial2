package ar.edu.examen.iiot

import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading
import ar.edu.examen.iiot.model.ThresholdValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantRulesTest {
    private fun sample(temp: Double) = Reading(
        machineId = "TEST-01",
        machineName = "Equipo de prueba",
        temperature = temp,
        humidity = 50.0,
        vibration = 1.0,
        timestamp = 1L,
        source = "Test"
    )

    // --- Reglas originales del docente (umbral por defecto 65 °C) ---

    @Test fun belowThresholdIsNormal() {
        assertFalse(PlantRules.requiresAttention(sample(64.9)))
    }

    @Test fun thresholdIsAlert() {
        assertTrue(PlantRules.requiresAttention(sample(65.0)))
    }

    // --- Umbral configurable: "mayor o igual" con un valor distinto de 65 ---

    @Test fun customThresholdUsesGreaterOrEqual() {
        assertTrue(PlantRules.requiresAttention(sample(80.0), threshold = 80.0))
        assertFalse(PlantRules.requiresAttention(sample(79.9), threshold = 80.0))
    }

    @Test fun alertCountIsRecalculatedWithNewThreshold() {
        // Mismas lecturas que la pantalla inicial: Bomba 48.8, Compresor 59.2, Horno 72.5
        val readings = listOf(sample(48.8), sample(59.2), sample(72.5))
        assertEquals(1, PlantRules.countAlerts(readings, 65.0))
        assertEquals(2, PlantRules.countAlerts(readings, 50.0))
        assertEquals(0, PlantRules.countAlerts(readings, 100.0))
    }

    // --- Validación del umbral ingresado (30 a 120 °C) ---

    @Test fun limitsOfRangeAreValid() {
        assertEquals(ThresholdValidation.Valid(30.0), PlantRules.validateThreshold("30"))
        assertEquals(ThresholdValidation.Valid(120.0), PlantRules.validateThreshold("120"))
    }

    @Test fun valuesOutsideRangeAreRejected() {
        assertTrue(PlantRules.validateThreshold("29.9") is ThresholdValidation.Invalid)
        assertTrue(PlantRules.validateThreshold("120.1") is ThresholdValidation.Invalid)
        assertTrue(PlantRules.validateThreshold("-40") is ThresholdValidation.Invalid)
    }

    @Test fun nonNumericInputIsRejectedWithMessage() {
        val result = PlantRules.validateThreshold("abc")
        assertTrue(result is ThresholdValidation.Invalid)
        assertTrue((result as ThresholdValidation.Invalid).message.isNotBlank())
        assertTrue(PlantRules.validateThreshold("") is ThresholdValidation.Invalid)
    }

    @Test fun decimalCommaAndSpacesAreAccepted() {
        assertEquals(ThresholdValidation.Valid(70.5), PlantRules.validateThreshold(" 70,5 "))
    }
}
