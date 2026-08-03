package br.unioeste.pulselab.wear.data.provider

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import br.unioeste.pulselab.wear.model.SensorBundle
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The set `S` of the evaluation: every sample the provider handed to the
 * transport, recorded on the watch itself.
 *
 * This is half the instrument, not bookkeeping. cap6-avaliacao.tex §6.3.1
 * requires the emitted series to be recorded rather than inferred, and
 * SENSORS.md §4.3 is why: the real provider's series loses ~18 % of every
 * minute in a regular pattern, so `|S| = duração × cadência` is false even
 * for it. Without a recorded `S` there is no loss rate, only "what arrived".
 */
interface EmissionLog {
    /**
     * @param emittedAt epoch ms at which the batch containing this sample was
     *   handed to the collector — distinct from [SensorBundle.timestamp], which
     *   is the sample's own (simulated) acquisition instant. They differ by up
     *   to one batch length, which is exactly the screen-off burst behaviour
     *   being reproduced (SENSORS.md §4.1).
     */
    fun record(bundle: SensorBundle, emittedAt: Long)

    /**
     * Called at each batch boundary. Separate from [record] so the flush
     * granularity is the batch, not the sample — flushing per sample would put
     * a 66-way write burst inside the screen-off flush the instrument exists to
     * observe (SENSORS.md §4.1).
     */
    fun flush()

    /** Flushes and releases the sink. Idempotent. */
    fun close()
}

/**
 * One JSON object per line: all payload fields plus `emittedAt`. NDJSON
 * because the file is appended to for the whole run and has to stay parseable
 * if the run is cut short — a truncated JSON array is not, a truncated NDJSON
 * file loses only its last line.
 *
 * Writes are flushed per batch, not per line: a batch is the transport's unit
 * of work, and flushing per sample would put a 66-way fsync burst inside the
 * screen-off flush the instrument is meant to observe.
 */
class NdjsonEmissionLog(private val file: File) : EmissionLog {

    private val gson = Gson()
    private var writer: BufferedWriter? = BufferedWriter(FileWriter(file, /* append = */ true))
    private var pendingWrites = 0

    /** Where the file lives, for the operator to pull after a run. */
    val path: String get() = file.absolutePath

    override fun record(bundle: SensorBundle, emittedAt: Long) {
        val sink = writer ?: return
        val line = gson.toJsonTree(bundle).asJsonObject.apply {
            addProperty("emittedAt", emittedAt)
        }
        sink.write(gson.toJson(line))
        sink.newLine()
        pendingWrites++
    }

    override fun flush() {
        if (pendingWrites == 0) return
        writer?.flush()
        pendingWrites = 0
    }

    override fun close() {
        writer?.runCatching { flush(); close() }
        writer = null
    }
}

/**
 * Creates a run-scoped NDJSON log under the app's external files dir, so it can
 * be pulled with `adb pull` without root:
 * `/sdcard/Android/data/br.unioeste.pulselab/files/eval/`.
 *
 * One file per run, named by start instant, because cap6-avaliacao.tex §6.5
 * reports metrics per execution rather than aggregated — appending every run to
 * one file would make separating them a parsing problem instead of a
 * filesystem one.
 */
fun createRunEmissionLog(context: Context, providerId: String): NdjsonEmissionLog {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "eval")
    dir.mkdirs()
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "emitted-$providerId-$stamp.ndjson")
    Log.i("EmissionLog", "Recording emitted samples to ${file.absolutePath}")
    return NdjsonEmissionLog(file)
}
