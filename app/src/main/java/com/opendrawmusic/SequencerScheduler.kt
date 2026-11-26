package com.opendrawmusic

/**
 * Pure-Kotlin step scheduler for seamless FluidSynth playback and WAV export.
 *
 * Tracks which `(instrumentKey, midi)` pairs are currently sounding across consecutive
 * steps, frame transitions, and loop wraps:
 * - Same instrument + same pitch on consecutive steps: kept held (no noteOff, no re-attack).
 * - Note ending on the next step: receives noteOff at the start of the next step so
 *   FluidSynth's natural release envelope rings out instead of cutting to digital silence.
 * - Different pitch on the next step: previous note receives noteOff, new note receives noteOn
 *   so each distinct pitch produces a separate onset.
 */
data class SequencerNoteKey(
    val instrumentId: String,
    val midi: Int
)

data class SequencerStepDiff(
    val noteOffs: List<SequencerNoteKey>,
    val noteOns: List<SequencerNoteKey>,
    val nextHeld: Set<SequencerNoteKey>
)

object SequencerScheduler {
    /**
     * Collects all active `(instrumentId, midi)` pairs at [step] within [strokes].
     */
    fun activeNotesAtStep(
        snapshot: MusicProject,
        strokes: List<ProjectStroke>,
        step: Int
    ): Set<SequencerNoteKey> {
        val active = linkedSetOf<SequencerNoteKey>()
        strokes.forEach { stroke ->
            stroke.cells.forEach { cell ->
                if (cell % snapshot.steps == step) {
                    val lineIndex = cell / snapshot.steps
                    val midi = midiForLine(lineIndex + 1, snapshot.rootMidi, snapshot.scale)
                    if (midi != null) {
                        active.add(SequencerNoteKey(stroke.instrument, midi))
                    }
                }
            }
        }
        return active
    }

    /**
     * Computes which notes to turn off and on when advancing to [activeNow] from [currentlyHeld].
     */
    fun diffStep(
        currentlyHeld: Set<SequencerNoteKey>,
        activeNow: Set<SequencerNoteKey>
    ): SequencerStepDiff {
        val noteOffs = currentlyHeld.filter { it !in activeNow }
        val noteOns = activeNow.filter { it !in currentlyHeld }
        return SequencerStepDiff(
            noteOffs = noteOffs,
            noteOns = noteOns,
            nextHeld = activeNow
        )
    }
}
