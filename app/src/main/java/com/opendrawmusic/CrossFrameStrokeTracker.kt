package com.opendrawmusic

import kotlin.math.floor

/**
 * Tracks a single continuous brush gesture across automatic frame advances (Fix 17 v2, Opsi B).
 *
 * When a brush stroke reaches the right edge (`step == steps - 1`, i.e., step 23):
 *  1. The current frame's cells (including step 23 at line L) are committed as one undo entry.
 *  2. The editor advances to the next frame (or appends a new empty frame).
 *  3. Connection cell `(step = 0, line = L)` is immediately recorded on the new frame.
 *  4. Relative mapping (ratchet) begins with `xRef = normX`:
 *     - Moving left (`normX < xRef`) shifts `xRef = normX` and writes no cells.
 *     - Moving right (`normX >= xRef`) computes `relStep = floor((normX - xRef) * steps)`
 *       clamped to `0..steps-1`. For `relStep >= 1`, `(relStep, row)` is recorded.
 *       Step 0 is never overwritten (stays at line L).
 *     - Reaching `relStep == steps - 1` chains into the next frame with new L and new xRef.
 */
class CrossFrameStrokeTracker(
    private val steps: Int,
    private val rows: Int
) {
    sealed class EventResult {
        data object Ignored : EventResult()
        data class Draw(val step: Int, val row: Int, val addedCells: List<Int>) : EventResult()
        data class AdvanceEdge(
            val committedCells: Set<Int>,
            val committedPoints: List<Pair<Float, Float>>,
            val connectionLine: Int,
            val connectionCell: Int
        ) : EventResult()
    }

    val paintedCells = linkedSetOf<Int>()
    val cellsForLive = linkedSetOf<Int>()
    val points = mutableListOf<Pair<Float, Float>>()

    var relativeMode: Boolean = false
        private set
    var xRef: Float = 0f
        private set
    var lastConnectionLine: Int = 0
        private set

    fun onPoint(normX: Float, normY: Float, isBrush: Boolean): EventResult {
        val clampedX = normX.coerceIn(0f, 1f)
        val clampedY = normY.coerceIn(0f, 1f)
        val row = (clampedY * rows).toInt().coerceIn(0, rows - 1)

        val step: Int
        if (relativeMode) {
            if (clampedX < xRef) {
                xRef = clampedX
                return EventResult.Ignored
            }
            val relStep = floor(((clampedX - xRef) * steps).toDouble() + 1e-5).toInt().coerceIn(0, steps - 1)
            if (relStep == 0) {
                return EventResult.Ignored
            }
            step = relStep
        } else {
            step = (clampedX * steps).toInt().coerceIn(0, steps - 1)
        }

        val cell = row * steps + step
        val added = if (paintedCells.add(cell)) listOf(cell) else emptyList()
        cellsForLive.add(cell)
        points.add(clampedX to clampedY)

        if (isBrush && step == steps - 1) {
            val commitCells = paintedCells.toSet()
            val commitPoints = points.toList()
            paintedCells.clear()
            cellsForLive.clear()
            points.clear()

            // Immediately park on step 0 of the new frame at line L (seamless connection).
            lastConnectionLine = row
            val connCell = row * steps
            paintedCells.add(connCell)
            cellsForLive.add(connCell)
            points.add(0f to clampedY)
            relativeMode = true
            xRef = clampedX

            return EventResult.AdvanceEdge(
                committedCells = commitCells,
                committedPoints = commitPoints,
                connectionLine = row,
                connectionCell = connCell
            )
        }

        return EventResult.Draw(step = step, row = row, addedCells = added)
    }
}
