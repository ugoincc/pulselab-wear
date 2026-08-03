package br.unioeste.pulselab.wear.data.provider

import br.unioeste.pulselab.wear.model.SENSOR_SCHEMA_VERSION
import br.unioeste.pulselab.wear.model.SensorBundle
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay provider is the measuring instrument of Objetivo 5, so these tests
 * check the two properties the metrics of cap6-avaliacao.tex §6.2 rest on:
 * the series is reproducible (otherwise content divergence is unmeasurable) and
 * the sequence has no holes at the source (otherwise loss measured downstream
 * is indistinguishable from the instrument skipping).
 */
class ReplayProviderTest {

    /**
     * Content divergence (cap6-avaliacao.tex §6.2) is defined as a received
     * field differing from the emitted one. That definition is only usable if
     * "the emitted one" is knowable from the seed alone.
     */
    @Test
    fun `same seed produces the same series field by field`() {
        val a = ReplaySeries(seed = 42L)
        val b = ReplaySeries(seed = 42L)

        repeat(500) { i ->
            assertEquals("sample $i diverged", a.next(), b.next())
        }
    }

    /**
     * Guards the other side of the claim: if any seed gave the same series, the
     * determinism above would be vacuous.
     */
    @Test
    fun `different seeds produce different series`() {
        val a = ReplaySeries(seed = 42L)
        val b = ReplaySeries(seed = 43L)

        val first = List(50) { a.next() }
        val second = List(50) { b.next() }

        assertNotEquals(first, second)
    }

    /** All values stay inside the ranges the phone-side index expects. */
    @Test
    fun `series stays within physiological bounds`() {
        val series = ReplaySeries(seed = 7L)

        repeat(2_000) {
            val s = series.next()
            assertTrue("bpm=${s.bpm}", s.bpm in 45..180)
            assertTrue("hrv=${s.hrv}", s.hrv in 5..200)
            assertTrue("spo2=${s.spo2}", s.spo2 in 90..100)
            assertTrue("skinTemp=${s.skinTemp}", s.skinTemp in 31.0..38.0)
            assertTrue("baevskySI=${s.baevskySI}", s.baevskySI in 20.0..900.0)
            assertTrue("edaTonic=${s.edaTonic}", s.edaTonic in 0.5..25.0)
            assertTrue("edaScrPerMin=${s.edaScrPerMin}", s.edaScrPerMin in 0..12)
            assertTrue("spo2AgeMs=${s.spo2AgeMs}", s.spo2AgeMs in 0 until 5 * 60_000L)
            assertTrue("skinTempAgeMs=${s.skinTempAgeMs}", s.skinTempAgeMs in 0 until 2_000L)
        }
    }

    /**
     * ESPECIFICACAO §5: `sequenceNumber` is monotonic per sample emitted, not
     * per batch. A gap at the source would be indistinguishable, downstream,
     * from a sample lost in transport — which is the metric being measured.
     */
    @Test
    fun `sequenceNumber has no gaps at the source`() = runBlocking {
        val emitted = collectSamples(
            ReplayProvider(seed = 1L, emitIntervalMs = 0L, batchSize = 7),
            batches = 20
        )

        assertEquals(140, emitted.size)
        emitted.forEachIndexed { i, sample ->
            assertEquals("sequenceNumber at index $i", i.toLong(), sample.sequenceNumber)
        }
    }

    /**
     * ESPECIFICACAO §5 again: the counter is scoped to a connection, and for
     * this provider a collection of the flow *is* the connection.
     */
    @Test
    fun `sequenceNumber restarts at zero on each collection`() = runBlocking {
        val provider = ReplayProvider(seed = 1L, emitIntervalMs = 0L, batchSize = 3)

        val first = collectSamples(provider, batches = 4)
        val second = collectSamples(provider, batches = 4)

        assertEquals(0L, first.first().sequenceNumber)
        assertEquals(0L, second.first().sequenceNumber)
        assertEquals(
            first.map { it.sequenceNumber },
            second.map { it.sequenceNumber }
        )
    }

    /**
     * Same seed ⇒ same payload values on two separate collections. Timestamps
     * are excluded on purpose: they are wall-clock instants, which is why the
     * emission log records them rather than the harness re-deriving them.
     */
    @Test
    fun `two collections with the same seed emit identical payload values`() = runBlocking {
        val first = collectSamples(ReplayProvider(seed = 99L, emitIntervalMs = 0L), batches = 60)
        val second = collectSamples(ReplayProvider(seed = 99L, emitIntervalMs = 0L), batches = 60)

        assertEquals(
            first.map { it.withoutInstants() },
            second.map { it.withoutInstants() }
        )
    }

    /**
     * The burst shape of SENSORS.md §4.1: samples delivered together still
     * carry acquisition instants one cadence apart, because on device the
     * sensor keeps sampling and only delivery is withheld.
     */
    @Test
    fun `batch delivers many samples with distinct advancing timestamps`() = runBlocking {
        var clock = 1_000L
        val provider = ReplayProvider(
            seed = 5L,
            emitIntervalMs = 0L,
            batchSize = ReplayProvider.SCREEN_OFF_BURST_SIZE,
            now = { clock += 1_000L; clock }
        )

        val batch = provider.readingFlow().take(1).toList().single()

        assertEquals(ReplayProvider.SCREEN_OFF_BURST_SIZE, batch.size)
        batch.zipWithNext().forEach { (a, b) -> assertTrue(b.timestamp > a.timestamp) }
    }

    /** Every field of the contract is populated — that is what §6.3.1 asks for. */
    @Test
    fun `every payload field is populated`() = runBlocking {
        val sample = collectSamples(ReplayProvider(seed = 3L, emitIntervalMs = 0L), batches = 1).single()

        assertNotNull(sample.hrv)
        assertNotNull(sample.spo2)
        assertNotNull(sample.skinTemp)
        assertNotNull(sample.baevskySI)
        assertNotNull(sample.edaTonic)
        assertNotNull(sample.edaScrPerMin)
        assertNotNull(sample.spo2MeasuredAt)
        assertNotNull(sample.skinTempMeasuredAt)
        assertEquals(REPLAY_PROVIDER_ID, sample.providerId)
        assertEquals(SENSOR_SCHEMA_VERSION, sample.schemaVersion)
    }

    /**
     * Capabilities are all true, EDA included. No hardware in the study
     * supplies EDA (ESPECIFICACAO §3), and declaring it here is what makes the
     * unfilled corner of the contract get exercised end to end.
     */
    @Test
    fun `declares every capability including eda`() = runBlocking {
        val capabilities = ReplayProvider().capabilities()

        assertEquals(
            CapabilitySet(
                providesHrv = true,
                providesBaevskySI = true,
                providesSpo2 = true,
                providesSkinTemp = true,
                providesEda = true,
                providesMotionFlag = true
            ),
            capabilities
        )
    }

    /**
     * The carry-forward instants trail the acquisition instant, reproducing the
     * age the real provider's SpO2 and skin-temperature values have when they
     * ride along on an HR emission (ESPECIFICACAO §3/§4).
     */
    @Test
    fun `carry-forward instants precede the acquisition instant`() = runBlocking {
        val samples = collectSamples(ReplayProvider(seed = 11L, emitIntervalMs = 0L), batches = 200)

        assertTrue(samples.all { it.spo2MeasuredAt!! <= it.timestamp })
        assertTrue(samples.all { it.skinTempMeasuredAt!! <= it.timestamp })
        // Not all equal to the timestamp — otherwise the age is not exercised.
        assertFalse(samples.all { it.spo2MeasuredAt == it.timestamp })
    }

    private suspend fun collectSamples(provider: ReplayProvider, batches: Int): List<SensorBundle> =
        provider.readingFlow().take(batches).toList().flatten()

    /** Drops the wall-clock fields, which are not part of the deterministic series. */
    private fun SensorBundle.withoutInstants() =
        copy(timestamp = 0L, spo2MeasuredAt = null, skinTempMeasuredAt = null)
}
