package ar.edu.examen.iiot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ar.edu.examen.iiot.data.PlantRepository
import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading
import ar.edu.examen.iiot.model.ThresholdValidation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardState(
    val readings: List<Reading> = emptyList(),
    val filter: String = "",
    val loading: Boolean = false,
    val syncMessage: String = "Lecturas locales",
    val notification: String? = null,
    val threshold: Double = PlantRules.DEFAULT_TEMPERATURE_LIMIT,
    val alertCount: Int = 0,
    val thresholdDialogOpen: Boolean = false,
    val thresholdError: String? = null,
    val history: HistoryState? = null
)

/** Historial abierto de un equipo; null en DashboardState significa cerrado. */
data class HistoryState(
    val machineId: String,
    val machineName: String,
    val loading: Boolean = true,
    val readings: List<Reading> = emptyList(),
    val error: String? = null
)

class PlantViewModel(private val repository: PlantRepository) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** Búsqueda en curso: se cancela si el usuario sigue escribiendo. */
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                // Primero el umbral guardado, para que el primer cálculo de alertas ya lo use.
                val threshold = repository.loadThreshold()
                _state.update { it.copy(threshold = threshold) }
                setReadings(repository.localReadings())
            } catch (exception: Exception) {
                _state.update { it.copy(notification = "No se pudieron leer los datos locales") }
            }
        }
    }

    private fun setReadings(items: List<Reading>) {
        _state.update { current ->
            current.copy(
                readings = items,
                alertCount = PlantRules.countAlerts(items, current.threshold)
            )
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, notification = null) }
            try {
                repository.sync()
                val items = repository.search(_state.value.filter)
                setReadings(items)
                _state.update { it.copy(syncMessage = repository.lastSyncMessage) }
            } catch (exception: Exception) {
                _state.update { it.copy(syncMessage = "No se pudo actualizar la telemetría") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun filter(text: String) {
        _state.update { it.copy(filter = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                setReadings(repository.search(text))
            } catch (exception: Exception) {
                _state.update {
                    it.copy(notification = "La búsqueda no se pudo completar")
                }
            }
        }
    }

    fun openThresholdDialog() {
        _state.update { it.copy(thresholdDialogOpen = true, thresholdError = null) }
    }

    fun closeThresholdDialog() {
        _state.update { it.copy(thresholdDialogOpen = false, thresholdError = null) }
    }

    fun saveThreshold(input: String) {
        when (val result = PlantRules.validateThreshold(input)) {
            is ThresholdValidation.Invalid ->
                _state.update { it.copy(thresholdError = result.message) }

            is ThresholdValidation.Valid -> viewModelScope.launch {
                try {
                    repository.saveThreshold(result.value)
                    // Recalcula NORMAL/ALERTA y el contador con las lecturas ya cargadas: sin sincronizar.
                    _state.update {
                        it.copy(
                            threshold = result.value,
                            alertCount = PlantRules.countAlerts(it.readings, result.value),
                            thresholdDialogOpen = false,
                            thresholdError = null
                        )
                    }
                } catch (exception: Exception) {
                    _state.update { it.copy(thresholdError = "No se pudo guardar el umbral") }
                }
            }
        }
    }

    fun showHistory(machineId: String) {
        val name = _state.value.readings.firstOrNull { it.machineId == machineId }
            ?.machineName ?: machineId
        _state.update { it.copy(history = HistoryState(machineId, name)) }
        viewModelScope.launch {
            val loaded = try {
                HistoryState(machineId, name, loading = false, readings = repository.history(machineId))
            } catch (exception: Exception) {
                HistoryState(machineId, name, loading = false, error = "No se pudo leer el historial")
            }
            // Solo se aplica si el usuario no cerró o cambió de equipo mientras cargaba.
            _state.update { if (it.history?.machineId == machineId) it.copy(history = loaded) else it }
        }
    }

    /** Cierra el historial; filter y readings no se tocan, así la búsqueda se conserva. */
    fun closeHistory() {
        _state.update { it.copy(history = null) }
    }

    fun dismissNotification() {
        _state.update { it.copy(notification = null) }
    }

    class Factory(private val repository: PlantRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlantViewModel::class.java))
            return PlantViewModel(repository) as T
        }
    }
}
