package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessVisualTest {

    // --- Part A: Continuous Playhead ---

    @Test
    fun checkA_midpointOfStepIsExactCellCenter() {
        val gridWidth = 1080f
        val cellWidth = gridWidth / 24f // 45.0 px
        val stepDuration = stepDurationMs(120) // 125.0 ms
        assertEquals(125.0, stepDuration, 0.001)

        val step = 5
        val cont = continuousPlayheadStep(stepIndex = step, elapsedMs = 62.5, stepDurationMs = stepDuration)
        val x = playheadXForStep(cont, gridWidth)
        val expectedX = (step + 0.5f) * cellWidth // 247.5 px
        assertEquals(expectedX, x, 0.01f)
    }

    @Test
    fun checkB_startAndEndOfStepAlignWithCellEdges() {
        val gridWidth = 1080f
        val cellWidth = gridWidth / 24f // 45.0 px
        val stepDuration = stepDurationMs(120) // 125.0 ms
        val step = 4

        val xStart = playheadXForStep(continuousPlayheadStep(step, 0.0, stepDuration), gridWidth)
        val xEnd = playheadXForStep(continuousPlayheadStep(step, 125.0, stepDuration), gridWidth)

        assertEquals(step * cellWidth, xStart, 0.01f)
        assertEquals((step + 1) * cellWidth, xEnd, 0.01f)
    }

    @Test
    fun checkC_samplesEvery10MsAreLinearWithEqualDelta() {
        val gridWidth = 1080f
        val cellWidth = gridWidth / 24f // 45.0 px
        val stepDuration = stepDurationMs(120) // 125.0 ms
        val samples = (0..120 step 10).map { ms ->
            playheadXForStep(continuousPlayheadStep(0, ms.toDouble(), stepDuration), gridWidth)
        }
        assertEquals(13, samples.size) // 12 intervals
        val expectedDx = (10.0 / 125.0 * cellWidth).toFloat() // 3.60 px
        for (i in 1 until samples.size) {
            val dx = samples[i] - samples[i - 1]
            assertEquals("Interval $i delta", expectedDx, dx, 0.01f)
        }
    }

    @Test
    fun checkDE_frameTransitionAndLoopWrapResetToLeftEdge() {
        val gridWidth = 1080f
        val stepDuration = stepDurationMs(120)
        // At start of frame 2 or after Loop wrap to frame 1: stepIndex = 0, elapsedMs = 0.0
        val xNewFrame = playheadXForStep(continuousPlayheadStep(0, 0.0, stepDuration), gridWidth)
        assertEquals(0f, xNewFrame, 0.001f)
    }

    // --- Part B: 200 ms Frame Crossfade ---

    @Test
    fun checkGHIJ_crossfadeAlphasAt0_100_200MsAreLinear() {
        val (out0, in0) = frameCrossfadeAlphas(0L)
        assertEquals(1.0f, out0, 0.001f)
        assertEquals(0.0f, in0, 0.001f)

        val (out100, in100) = frameCrossfadeAlphas(100L)
        assertEquals(0.5f, out100, 0.001f)
        assertEquals(0.5f, in100, 0.001f)

        val (out200, in200) = frameCrossfadeAlphas(200L)
        assertEquals(0.0f, out200, 0.001f)
        assertEquals(1.0f, in200, 0.001f)
    }

    // --- Part C: 150 ms Free-Draw Stroke Fade-In ---

    @Test
    fun checkKLM_fadeInAlphaAt0_75_150MsAndUndo() {
        assertEquals(0.0f, strokeFadeInAlpha(0L), 0.001f)
        assertEquals(0.5f, strokeFadeInAlpha(75L), 0.001f)
        assertEquals(1.0f, strokeFadeInAlpha(150L), 0.001f)

        // State grid is final since finger lift; 1 stroke = 1 undo entry
        val history = FrameHistory()
        val stroke = ProjectStroke("SF_SOFT_KEY", "BRUSH", listOf(0.1f to 0.2f), listOf(10, 34, 58))
        val before = emptyList<ProjectStroke>()
        history.recordEdit(0, before)
        val committed = listOf(stroke)
        assertEquals(3, committed.first().cells.size)

        // Undo removes the entire stroke at once
        val undone = history.undo(0, committed)
        assertTrue(undone != null && undone.isEmpty())
    }
}
