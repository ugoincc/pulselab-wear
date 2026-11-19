package br.unioeste.pulselab.wear.data.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGateTest {

    @Test
    fun `no samples means no motion`() {
        // An unsupported or silent accelerometer must not flag every reading as
        // artefact-laden — that would discard the whole session.
        val gate = MotionGate()
        assertNull(gate.peakToPeak())
        assertFalse(gate.isMoving())
    }

    @Test
    fun `a still wrist is not moving`() {
        val gate = MotionGate()
        // Constant orientation under gravity: magnitude never changes.
        repeat(10) { gate.add(x = 0, y = 0, z = 4096, atMs = it * 100L) }
        assertEquals0(gate.peakToPeak()!!)
        assertFalse(gate.isMoving())
    }

    @Test
    fun `rotating the wrist without accelerating is not motion`() {
        val gate = MotionGate()
        // Gravity redistributed across axes, same magnitude — the reason the gate
        // uses vector magnitude rather than any single axis.
        gate.add(x = 4096, y = 0, z = 0, atMs = 0L)
        gate.add(x = 0, y = 4096, z = 0, atMs = 100L)
        gate.add(x = 0, y = 0, z = 4096, atMs = 200L)
        assertEquals0(gate.peakToPeak()!!)
        assertFalse(gate.isMoving())
    }

    @Test
    fun `a shake exceeds the threshold`() {
        val gate = MotionGate()
        gate.add(x = 0, y = 0, z = 4096, atMs = 0L)
        gate.add(x = 0, y = 0, z = 9000, atMs = 100L)
        assertTrue(gate.peakToPeak()!! > MotionGate.DEFAULT_THRESHOLD)
        assertTrue(gate.isMoving())
    }

    @Test
    fun `motion outside the window stops counting`() {
        val gate = MotionGate(windowMs = 1_000L)
        gate.add(x = 0, y = 0, z = 9000, atMs = 0L)   // the spike
        gate.add(x = 0, y = 0, z = 4096, atMs = 1_500L) // evicts it (cutoff = 500)
        assertFalse("stale motion should not keep flagging", gate.isMoving())
    }

    private fun assertEquals0(value: Double) =
        assertTrue("expected ~0 peak-to-peak, was $value", value < 1e-6)
}
