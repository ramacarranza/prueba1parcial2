package ar.edu.examen.iiot.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ar.edu.examen.iiot.model.Reading

class PlantDatabase(context: Context) :
    SQLiteOpenHelper(context, "planta_iiot.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                machine_id TEXT NOT NULL,
                machine_name TEXT NOT NULL,
                temperature REAL NOT NULL,
                humidity REAL NOT NULL,
                vibration REAL NOT NULL,
                captured_at INTEGER NOT NULL,
                source TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE TABLE configuration (config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS readings")
        db.execSQL("DROP TABLE IF EXISTS configuration")
        onCreate(db)
    }

    /** Inserta un lote completo o nada: evita sincronizaciones guardadas a medias. */
    fun insertAll(readings: List<Reading>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            readings.forEach { db.insertOrThrow("readings", null, it.toValues()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Reading.toValues(): ContentValues {
        val reading = this
        return ContentValues().apply {
            put("machine_id", reading.machineId)
            put("machine_name", reading.machineName)
            put("temperature", reading.temperature)
            put("humidity", reading.humidity)
            put("vibration", reading.vibration)
            put("captured_at", reading.timestamp)
            put("source", reading.source)
        }
    }

    fun latest(): List<Reading> =
        readableDatabase.rawQuery("$LATEST_PER_MACHINE ORDER BY r.machine_name", null)
            .use(::readAll)

    /**
     * El texto del usuario viaja como parámetro (?), nunca concatenado al SQL:
     * una comilla ya no rompe la consulta ni permite inyectar SQL.
     * Se escapan % y _ para que se busquen literalmente.
     */
    fun findLatestByName(filter: String): List<Reading> {
        val sql = "$LATEST_PER_MACHINE WHERE r.machine_name LIKE ? ESCAPE '\\' " +
            "ORDER BY r.machine_name"
        val pattern = "%" + escapeLike(filter) + "%"
        return readableDatabase.rawQuery(sql, arrayOf(pattern)).use(::readAll)
    }

    /** Últimas [limit] lecturas de un equipo, de la más reciente a la más antigua. */
    fun history(machineId: String, limit: Int): List<Reading> =
        readableDatabase.rawQuery(
            """SELECT id, machine_id, machine_name, temperature, humidity, vibration,
                      captured_at, source
               FROM readings
               WHERE machine_id = ?
               ORDER BY captured_at DESC, id DESC
               LIMIT ?""",
            arrayOf(machineId, limit.toString())
        ).use(::readAll)

    fun readConfig(key: String): String? =
        readableDatabase.rawQuery(
            "SELECT config_value FROM configuration WHERE config_key = ?",
            arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** INSERT OR REPLACE sobre la clave primaria: crea o actualiza el valor. */
    fun writeConfig(key: String, value: String) {
        val values = ContentValues().apply {
            put("config_key", key)
            put("config_value", value)
        }
        writableDatabase.insertWithOnConflict(
            "configuration", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val LATEST_PER_MACHINE = """SELECT r.id, r.machine_id, r.machine_name,
            r.temperature, r.humidity, r.vibration, r.captured_at, r.source
            FROM readings r
            INNER JOIN (
                SELECT machine_id, MAX(id) AS latest_id
                FROM readings GROUP BY machine_id
            ) x ON r.id = x.latest_id"""
    }

    private fun readAll(cursor: Cursor): List<Reading> {
        val items = mutableListOf<Reading>()
        while (cursor.moveToNext()) {
            items += Reading(
                id = cursor.getLong(0),
                machineId = cursor.getString(1),
                machineName = cursor.getString(2),
                temperature = cursor.getDouble(3),
                humidity = cursor.getDouble(4),
                vibration = cursor.getDouble(5),
                timestamp = cursor.getLong(6),
                source = cursor.getString(7)
            )
        }
        return items
    }
}
