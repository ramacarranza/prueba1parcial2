# Cambios realizados

Se conservó la base del docente: MVVM, Retrofit (ThingSpeak), SQLite (`SQLiteOpenHelper`),
sensores del teléfono (luz y acelerómetro) y la convivencia XML (encabezado, botón de
sincronización, sensores) con Compose (panel). No se cambiaron versiones de Gradle ni del
plugin de Android, ni el esquema de la base (sigue en versión 1).

Commits, en orden: estado inicial → evidencias previas → refactorizaciones → actividad 1 →
actividad 2 → tests → este documento.

## Funcionalidad 1: umbral de alarma

- **Validación:** `PlantRules.validateThreshold(texto)` (`model/Reading.kt`) acepta solo
  números de **30 a 120 °C** (con punto o coma decimal). Si el valor no es válido devuelve
  `ThresholdValidation.Invalid` con un mensaje que el diálogo muestra debajo del campo
  ("Ingresá un número…" o "El umbral debe estar entre 30 y 120 °C").
- **Persistencia:** se usa la tabla `configuration` que ya existía, con la clave
  `temperature_threshold`. `PlantDatabase.writeConfig` hace `INSERT OR REPLACE`, y
  `PlantRepository.loadThreshold` devuelve **65 °C** si no hay valor guardado (instalación
  nueva) o si el guardado es inválido. Como no cambia el esquema, no se dispara `onUpgrade`
  (que borra todas las tablas).
- **Actualización inmediata:** `PlantViewModel.saveThreshold` guarda el valor y, en la misma
  actualización de estado, recalcula `alertCount` con `PlantRules.countAlerts` sobre las
  lecturas ya cargadas. Cada tarjeta calcula NORMAL/ALERTA con `state.threshold`, así que
  todo cambia sin sincronizar. La regla es **mayor o igual** al umbral.
- **UI:** `ThresholdDialog` (Compose, `AlertDialog`) con teclado decimal; "Cancelar" cierra
  sin guardar.

## Funcionalidad 2: historial

- **Consulta:** `PlantDatabase.history(machineId, 10)` con un parámetro `WHERE machine_id = ?`,
  `ORDER BY captured_at DESC, id DESC LIMIT 10`. El `id` desempata las lecturas con la misma
  hora. Se llama desde `PlantRepository.history` en `Dispatchers.IO`.
- **Presentación:** `HistoryDialog` (Compose) muestra, por fila, **fecha/hora**
  (dd/MM/yyyy HH:mm:ss), **temperatura** (en rojo si supera el umbral actual) y **origen**
  (ThingSpeak o simulación local).
- **Estados:** "Cargando…", error de lectura o un estado vacío con texto explicativo si el
  equipo no tiene lecturas.
- **Volver sin perder la búsqueda:** el historial vive en `DashboardState.history`;
  `closeHistory()` solo lo pone en `null` y no toca `filter` ni `readings`.
- Cada sincronización inserta una lectura nueva por equipo, por lo que después de
  sincronizar dos veces aparecen al principio del historial.

## Refactorizaciones

- **Problema 1, inyección SQL en la búsqueda (seguridad).**
  *Problema:* `findLatestByName` concatenaba el texto del usuario (`LIKE '%$filter%'`). Con
  una comilla la consulta fallaba (captura 04), y además permitía inyectar SQL.
  *Corrección:* `LIKE ? ESCAPE '\'` con `selectionArgs`, escapando `%` y `_` para que se
  busquen literalmente (`PlantDatabase.kt`). La subconsulta repetida pasó a una constante.
  *Beneficio:* la entrada del usuario nunca se interpreta como SQL y la búsqueda no falla
  con ningún carácter.

- **Problema 2, regla de alarma duplicada (diseño y testabilidad).**
  *Problema:* el valor `65.0` estaba repetido en `PlantViewModel` (contador) y en
  `EquipmentCard` (NORMAL/ALERTA) en vez de usar `PlantRules`. Con un umbral configurable,
  el contador y las tarjetas podrían contradecirse.
  *Corrección:* `PlantRules.requiresAttention(reading, threshold)` y `countAlerts(...)` son
  la única regla, y la UI y el ViewModel le pasan el umbral del estado.
  *Beneficio:* un solo lugar para cambiar la regla, cubierto por tests JVM sin emulador.

- **Problema 3, SQLite en el hilo principal (rendimiento).**
  *Problema:* `init` del ViewModel, `filter()` (en cada tecla) y los `insert` de `sync()`
  leían y escribían la base en el hilo de UI, con riesgo de trabas o ANR al crecer la tabla.
  *Corrección:* las funciones del repositorio son `suspend` con `withContext(Dispatchers.IO)`.
  El ViewModel las lanza en `viewModelScope` y cancela la búsqueda anterior si el usuario
  sigue escribiendo.
  *Beneficio:* la UI nunca espera al disco y no se aplican resultados de búsquedas viejas.

- **Extras menores:**
  - El repositorio se crea con `applicationContext`. Antes recibía la Activity y, como el
    ViewModel sobrevive a la rotación, retenía una Activity destruida (fuga de memoria).
  - `insertAll` guarda cada sincronización dentro de una transacción, así no quedan lotes a
    medias si falla un insert.

## Evidencia de validación

### Prueba automatizada

`app/src/test/java/ar/edu/examen/iiot/PlantRulesTest.kt`, con **8 tests y 0 fallos**:

- Las 2 pruebas originales (64.9 es NORMAL; 65.0 es ALERTA).
- El umbral personalizado usa mayor o igual (80.0 es ALERTA; 79.9 no).
- `countAlerts` con las lecturas de la pantalla inicial (48.8 / 59.2 / 72.5): da 1, 2 y 0
  alertas con umbrales de 65, 50 y 100. Es la lógica que actualiza el contador al guardar.
- Validación: 30 y 120 son válidos; 29.9, 120.1, -40, "abc" y "" se rechazan con mensaje;
  " 70,5 " se acepta como 70.5.

Cómo correrlos (Gradle 8.11.1 con JDK 17 o 21):

```
gradlew testDebugUnitTest
```

### Prueba manual (emulador Pixel 8, API 37)

| # | Pasos | Resultado esperado | Antes | Después |
|---|---|---|---|---|
| 1 | Abrir la app | Panel con 3 equipos, Umbral 65 °C, Alertas: 1 (Horno C) | [01](evidencias/01-estado-inicial.png) | — |
| 2 | Tocar **Umbral**, escribir `25`, Guardar | Mensaje "El umbral debe estar entre 30 y 120 °C"; no se guarda | [02](evidencias/02-antes-umbral-pendiente.png) ("función pendiente") | [06](evidencias/06-despues-umbral-invalido.png) |
| 3 | Escribir `80`, Guardar | Botón "Umbral: 80 °C", Horno C pasa a NORMAL, Alertas: 0, sin sincronizar | — | [07](evidencias/07-despues-umbral-80.png) |
| 4 | Cerrar la app desde Recientes y volver a abrirla | Sigue "Umbral: 80 °C" (persistido en SQLite) | — | [08](evidencias/08-despues-umbral-persistente.png) |
| 5 | Buscar `Horno`, Sincronizar dos veces, **Ver historial** | Lecturas con fecha/hora, °C y origen, de la más reciente a la más antigua (máximo 10). Observado: 5 lecturas de Horno C; las dos de arriba (12:02:09 y 12:03:45) son las dos sincronizaciones del paso. Como la API no respondió ("Sin conexión a API"), el origen es "Simulación local" | [03](evidencias/03-antes-historial-pendiente.png) ("función pendiente") | [09](evidencias/09-despues-historial.png) |
| 6 | Cerrar el historial | Vuelve al panel con la búsqueda `Horno` intacta | — | [10](evidencias/10-despues-historial-cerrado.png) |
| 7 | Borrar la búsqueda y escribir `'` | "No hay equipos para mostrar.", sin error | [04](evidencias/04-antes-busqueda-comilla.png) ("La búsqueda no se pudo completar") | [11](evidencias/11-despues-busqueda-comilla.png) |
| 8 | Modo avión (✈) y **Sincronizar** | "Sin conexión a API: usando datos simulados"; la app no se cierra y la búsqueda con `'` sigue sin error | [05](evidencias/05-antes-sync-sin-red.png) | [12](evidencias/12-despues-sync-sin-red.png) |

Las horas del historial son las del reloj del emulador, que no coincide con la hora de Windows
que aparece en la barra de tareas de las capturas.

Nota: el estado vacío del historial es defensivo. En el uso normal no se ve, porque la app
carga una lectura simulada por equipo en el primer arranque.
