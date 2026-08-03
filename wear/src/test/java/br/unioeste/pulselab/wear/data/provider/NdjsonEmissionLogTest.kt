package br.unioeste.pulselab.wear.data.provider

import com.google.gson.JsonParser
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The emission log is the set `S` of cap6-avaliacao.tex §6.2. If a sample
 * reaches the transport without reaching this file, the loss rate is wrong in
 * the direction that flatters the system — so what is checked here is that
 * every emitted sample is on disk, complete, one per line.
 */
class NdjsonEmissionLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `records one parseable line per sample with all payload fields`() {
        val file = folder.newFile("emitted.ndjson")
        val log = NdjsonEmissionLog(file)

        log.record(sampleBundle(sequenceNumber = 0L), emittedAt = 1_700_000_000_100L)
        log.record(sampleBundle(sequenceNumber = 1L), emittedAt = 1_700_000_000_100L)
        log.close()

        val lines = file.readLines()
        assertEquals(2, lines.size)

        val first = JsonParser.parseString(lines[0]).asJsonObject
        listOf(
            "bpm", "hrv", "spo2", "skinTemp", "baevskySI", "edaTonic", "edaScrPerMin",
            "motionFlag", "timestamp", "schemaVersion", "sequenceNumber",
            "spo2MeasuredAt", "skinTempMeasuredAt", "providerId", "emittedAt"
        ).forEach { field ->
            assertTrue("missing $field", first.has(field))
        }
        assertEquals(0L, first["sequenceNumber"].asLong)
        assertEquals(1_700_000_000_100L, first["emittedAt"].asLong)
        assertEquals(1L, JsonParser.parseString(lines[1]).asJsonObject["sequenceNumber"].asLong)
    }

    /** Idempotent close, per the [EmissionLog] contract. */
    @Test
    fun `close is idempotent`() {
        val file = folder.newFile("closed-twice.ndjson")
        val log = NdjsonEmissionLog(file)
        log.record(sampleBundle(0L), emittedAt = 1L)
        log.close()
        log.close()

        assertEquals(1, file.readLines().size)
    }

    /**
     * End to end through the provider: `|S|` read off the file must equal the
     * number of samples emitted, and the sequence must be dense. Inferring
     * `|S|` from duration × cadence instead is the assumption SENSORS.md §4.3
     * showed to be false even for the real provider.
     */
    @Test
    fun `provider records every emitted sample`() = runBlocking {
        val file = folder.newFile("run.ndjson")
        val provider = ReplayProvider(
            seed = 21L,
            emitIntervalMs = 0L,
            batchSize = 5,
            logFactory = { NdjsonEmissionLog(file) }
        )

        val emitted = provider.readingFlow().take(12).toList().flatten()

        val recorded = readSequenceNumbers(file)
        assertEquals(emitted.size, recorded.size)
        assertEquals(emitted.map { it.sequenceNumber }, recorded)
        assertEquals((0L until 60L).toList(), recorded)
    }

    private fun readSequenceNumbers(file: File): List<Long> =
        file.readLines().map { JsonParser.parseString(it).asJsonObject["sequenceNumber"].asLong }

    private fun sampleBundle(sequenceNumber: Long) = SensorBundle(
        bpm = 72,
        hrv = 44,
        spo2 = 97,
        skinTemp = 34.5,
        baevskySI = 91.0,
        edaTonic = 4.2,
        edaScrPerMin = 3,
        motionFlag = false,
        timestamp = 1_700_000_000_000L,
        sequenceNumber = sequenceNumber,
        spo2MeasuredAt = 1_699_999_800_000L,
        skinTempMeasuredAt = 1_699_999_999_000L,
        providerId = REPLAY_PROVIDER_ID
    )
}
