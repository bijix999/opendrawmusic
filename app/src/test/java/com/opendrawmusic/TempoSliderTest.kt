package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoSliderTest {

    @Test
    fun sliderRangeAndStep1Bpm() {
        assertEquals(60, bpmFromTempoSliderFraction(0f))
        assertEquals(200, bpmFromTempoSliderFraction(1f))
        assertEquals(60, bpmFromTempoSliderFraction(-0.2f))
        assertEquals(200, bpmFromTempoSliderFraction(1.2f))

        // Every integer BPM in 60..200 round-trips with 1-BPM precision
        for (bpm in 60..200) {
            val f = tempoSliderFractionForBpm(bpm)
            assertEquals("BPM $bpm round-trip", bpm, bpmFromTempoSliderFraction(f))
        }

        // Small step on slider changes by 1 BPM, not 5
        val f144 = tempoSliderFractionForBpm(144)
        val f145 = tempoSliderFractionForBpm(145)
        assertEquals(144, bpmFromTempoSliderFraction(f144))
        assertEquals(145, bpmFromTempoSliderFraction(f145))
    }

    @Test
    fun thumbFractionMatchesBpm() {
        // BPM 90 -> (90 - 60) / (200 - 60) = 30 / 140 = 0.2142857 (21.4%)
        assertEquals(30f / 140f, tempoSliderFractionForBpm(90), 0.0001f)
        // BPM 120 -> (120 - 60) / (200 - 60) = 60 / 140 = 0.4285714 (42.9%)
        assertEquals(60f / 140f, tempoSliderFractionForBpm(120), 0.0001f)
        // BPM 144 -> (144 - 60) / (200 - 60) = 84 / 140 = 0.60 (60.0%)
        assertEquals(0.60f, tempoSliderFractionForBpm(144), 0.0001f)
    }

    @Test
    fun stepDurationAndFramesAtKeyTempos() {
        assertEquals(250.0, stepDurationMs(60), 0.01)
        assertEquals(125.0, stepDurationMs(120), 0.01)
        assertEquals(104.1667, stepDurationMs(144), 0.01)
        assertEquals(75.0, stepDurationMs(200), 0.01)

        // At 44100 Hz: 44100 * 60 / 144 / 4 = 4593.75 -> 4593 frames/step
        assertEquals(4593, framesPerStepForBpm(44100, 144))
        assertEquals(11025, framesPerStepForBpm(44100, 60))
        assertEquals(5512, framesPerStepForBpm(44100, 120))
        assertEquals(3307, framesPerStepForBpm(44100, 200))
    }

    @Test
    fun projectJsonRoundTripPreservesNonMultipleOf5Bpm() {
        val original = MusicProject(title = "Tempo 144", bpm = 144)
        val restored = MusicProject.fromJson(original.toJson())
        assertEquals(144, restored.bpm)
    }

    @Test
    fun midiAndWavExportUseBpm144() {
        val stroke = ProjectStroke(
            instrument = "SF_SOFT_KEY",
            tool = "BRUSH",
            points = listOf(0.1f to 0.5f),
            cells = listOf(4 * 24 + 0)
        )
        val project144 = MusicProject(bpm = 144, frames = listOf(ProjectFrame(listOf(stroke))))
        val midi = MidiExporter.export(project144)
        val expectedUsPerQuarter = (60_000_000.0 / 144).toInt() // 416666
        val b0 = ((expectedUsPerQuarter shr 16) and 0xFF).toByte()
        val b1 = ((expectedUsPerQuarter shr 8) and 0xFF).toByte()
        val b2 = (expectedUsPerQuarter and 0xFF).toByte()
        // Search for FF 51 03 <b0> <b1> <b2>
        val found = (0..midi.size - 6).any { i ->
            midi[i] == 0xFF.toByte() && midi[i + 1] == 0x51.toByte() && midi[i + 2] == 0x03.toByte() &&
                midi[i + 3] == b0 && midi[i + 4] == b1 && midi[i + 5] == b2
        }
        assertTrue("MIDI tempo meta event for 144 BPM ($expectedUsPerQuarter us/quarter)", found)

        // WAV export duration for 1 frame (24 steps) at 144 BPM, 44100 Hz stereo 16-bit
        val framesPerStep = framesPerStepForBpm(44100, 144)
        val totalFrames = framesPerStep * 24
        val wav = WavExporter.export(ShortArray(totalFrames * 2), 44100)
        // WAV header is 44 bytes; PCM data is totalFrames * 4 bytes (stereo 16-bit)
        assertEquals(44 + totalFrames * 4, wav.size)
    }
}
