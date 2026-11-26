package com.opendrawmusic

/**
 * Per-frame undo/redo history (Run 7 §3.1.5, locked by the User 2026-11-24).
 *
 * Rules implemented here:
 *  - Every frame has its OWN independent undo and redo stacks. Undo/Redo always acts
 *    on the currently selected frame only; undoing in frame 1 never changes frame 2.
 *  - One entry = the frame's stroke list before one edit (same granularity as before:
 *    one stroke / one clear = one undo step).
 *  - Switching frames only changes which stacks the buttons use; nothing is cleared
 *    or merged. A frame's history is exactly as it was left.
 *  - Adding a frame (+) is NOT an undo entry; a new frame starts with empty stacks.
 *  - Deleting a frame discards only that frame's history; other frames' histories are
 *    untouched (the parallel lists shift together with the frames, so each history
 *    stays attached to its own frame).
 *  - History lives in MEMORY ONLY and is never written to the project JSON. After
 *    reloading a project every frame starts with an empty history.
 *
 * This class is plain Kotlin (no Android dependencies) so the isolation rules are
 * locked by JVM unit tests in [FrameHistoryTest].
 */
class FrameHistory {

    /** Per-frame undo stacks; `undos[i]` belongs to frame i. */
    private val undos = mutableListOf<MutableList<List<ProjectStroke>>>()

    /** Per-frame redo stacks; `redos[i]` belongs to frame i. */
    private val redos = mutableListOf<MutableList<List<ProjectStroke>>>()

    companion object {
        /** Same cap the pre-Run-7 undo stack had. */
        const val MAX_ENTRIES = 100
    }

    private fun stacksFor(index: Int): Pair<MutableList<List<ProjectStroke>>, MutableList<List<ProjectStroke>>> {
        require(index >= 0) { "frame index must be >= 0, was $index" }
        while (undos.size <= index) {
            undos.add(mutableListOf())
            redos.add(mutableListOf())
        }
        return undos[index] to redos[index]
    }

    /** Grows the parallel lists to [frameCount] frames (new frames start empty). */
    fun ensureSize(frameCount: Int) {
        require(frameCount >= 1) { "a project always has at least 1 frame" }
        while (undos.size < frameCount) {
            undos.add(mutableListOf())
            redos.add(mutableListOf())
        }
    }

    /** Drops all histories and starts [frameCount] fresh empty ones (load / import). */
    fun reset(frameCount: Int) {
        undos.clear()
        redos.clear()
        ensureSize(frameCount)
    }

    /**
     * Records the frame's content BEFORE an edit: pushes [before] onto the frame's undo
     * stack, clears its redo stack, caps at [MAX_ENTRIES]. Called for paint, erase and
     * Clear - never for adding a frame (§3.1.5e).
     */
    fun recordEdit(frameIndex: Int, before: List<ProjectStroke>) {
        val (undo, redo) = stacksFor(frameIndex)
        undo.add(before)
        if (undo.size > MAX_ENTRIES) undo.removeAt(0)
        redo.clear()
    }

    fun canUndo(frameIndex: Int): Boolean =
        frameIndex >= 0 && frameIndex < undos.size && undos[frameIndex].isNotEmpty()

    fun canRedo(frameIndex: Int): Boolean =
        frameIndex >= 0 && frameIndex < redos.size && redos[frameIndex].isNotEmpty()

    /**
     * Undoes the frame's last edit. [current] is the frame's content now; it is pushed
     * onto the redo stack and the previous content is returned, or null if there is
     * nothing to undo for THIS frame.
     */
    fun undo(frameIndex: Int, current: List<ProjectStroke>): List<ProjectStroke>? {
        val (undo, redo) = stacksFor(frameIndex)
        if (undo.isEmpty()) return null
        val previous = undo.removeAt(undo.lastIndex)
        redo.add(current)
        return previous
    }

    /** Redoes the frame's last undone edit; null if there is nothing to redo. */
    fun redo(frameIndex: Int, current: List<ProjectStroke>): List<ProjectStroke>? {
        val (undo, redo) = stacksFor(frameIndex)
        if (redo.isEmpty()) return null
        val next = redo.removeAt(redo.lastIndex)
        undo.add(current)
        return next
    }

    /** Appends an empty history for a newly added frame (+ button, §3.1.5e). */
    fun addFrame() {
        undos.add(mutableListOf())
        redos.add(mutableListOf())
    }

    /** Inserts an empty history at [frameIndex] (menu Add/Paste before/after). */
    fun insertFrame(frameIndex: Int) {
        val pos = frameIndex.coerceIn(0, undos.size)
        undos.add(pos, mutableListOf())
        redos.add(pos, mutableListOf())
    }

    /**
     * Removes the history of the deleted frame and shifts the later ones down so every
     * history stays attached to its frame (§3.3.8c: only this frame's history is
     * discarded, the others are untouched).
     */
    fun deleteFrame(frameIndex: Int) {
        require(frameIndex in 0 until undos.size) { "no history for frame $frameIndex" }
        undos.removeAt(frameIndex)
        redos.removeAt(frameIndex)
    }

    /** Test/introspection helper: entries currently undoable for a frame. */
    fun undoSize(frameIndex: Int): Int =
        if (frameIndex in 0 until undos.size) undos[frameIndex].size else 0

    /** Test/introspection helper: entries currently redoable for a frame. */
    fun redoSize(frameIndex: Int): Int =
        if (frameIndex in 0 until redos.size) redos[frameIndex].size else 0
}
