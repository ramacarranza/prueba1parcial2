package ar.edu.examen.iiot.data

import android.content.Context
import ar.edu.examen.iiot.model.Reading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Toda operación de disco o red corre en [ioDispatcher] (por defecto Dispatchers.IO),
 * así el hilo principal nunca espera a SQLite. Solo se guarda el applicationContext
 * para no retener una Activity destruida.
 */
class PlantRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val database = PlantDatabase(context.applicationContext)
    private val api = Retrofit.Builder()
        .baseUrl("https://api.thingspeak.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ThingSpeakApi::class.java)

    var lastSyncMessage: String = "Datos locales"
        private set

    suspend fun localReadings(): List<Reading> = withContext(ioDispatcher) {
        val rows = database.latest()
        if (rows.isNotEmpty()) return@withContext rows
        database.insertAll(sampleReadings(null, null, false))
        database.latest()
    }

    suspend fun search(query: String): List<Reading> = withContext(ioDispatcher) {
        if (query.isBlank()) database.latest() else database.findLatestByName(query)
    }

    suspend fun sync(): List<Reading> = withContext(ioDispatcher) {
        var remoteTemperature: Double? = null
        var remoteHumidity: Double? = null
        var remoteOk = false

        try {
            val feed = api.getLatest().feeds.lastOrNull()
            remoteTemperature = feed?.temperature?.toDoubleOrNull()
                ?.takeIf { it in -20.0..150.0 }
            remoteHumidity = feed?.humidity?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }
            remoteOk = remoteTemperature != null
            lastSyncMessage = if (remoteOk) {
                "Sincronizado con ThingSpeak (canal público de demostración)"
            } else {
                "Sin datos remotos válidos: usando datos simulados"
            }
        } catch (exception: Exception) {
            lastSyncMessage = "Sin conexión a API: usando datos simulados"
        }

        database.insertAll(sampleReadings(remoteTemperature, remoteHumidity, remoteOk))
        database.latest()
    }

    private fun sampleReadings(
        remoteTemperature: Double?,
        remoteHumidity: Double?,
        remoteOk: Boolean
    ): List<Reading> {
        val now = System.currentTimeMillis()
        val variation = ((now / 60_000) % 4).toDouble()
        return listOf(
            Reading(
                machineId = "CMP-01",
                machineName = "Compresor A",
                temperature = remoteTemperature ?: 56.2 + variation,
                humidity = remoteHumidity ?: 42.0,
                vibration = 1.6,
                timestamp = now,
                source = if (remoteOk) "ThingSpeak (demo externo)" else "Simulación local"
            ),
            Reading(
                machineId = "PMP-02",
                machineName = "Bomba B",
                temperature = 47.3 + variation / 2,
                humidity = 36.0,
                vibration = 0.8,
                timestamp = now,
                source = "Simulación local"
            ),
            Reading(
                machineId = "OVN-03",
                machineName = "Horno C",
                temperature = 69.5 + variation,
                humidity = 24.0,
                vibration = 0.3,
                timestamp = now,
                source = "Simulación local"
            )
        )
    }
}
