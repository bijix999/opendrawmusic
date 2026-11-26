package com.opendrawmusic

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.math.roundToInt

const val TEMPO_SLIDER_MIN_BPM = 60
const val TEMPO_SLIDER_MAX_BPM = 200

/** Normalized 0..1 track position for [bpm] on the 60..200 BPM tempo slider. */
fun tempoSliderFractionForBpm(bpm: Int): Float =
    ((bpm - TEMPO_SLIDER_MIN_BPM).toFloat() / (TEMPO_SLIDER_MAX_BPM - TEMPO_SLIDER_MIN_BPM).toFloat())
        .coerceIn(0f, 1f)

/** Converts a normalized 0..1 track position to an integer BPM in 60..200 (step 1 BPM). */
fun bpmFromTempoSliderFraction(fraction: Float): Int =
    (TEMPO_SLIDER_MIN_BPM + (fraction.coerceIn(0f, 1f) * (TEMPO_SLIDER_MAX_BPM - TEMPO_SLIDER_MIN_BPM)).roundToInt())
        .coerceIn(TEMPO_SLIDER_MIN_BPM, TEMPO_SLIDER_MAX_BPM)

/** Step duration in milliseconds: 60 / BPM / 4 * 1000. */
fun stepDurationMs(bpm: Int): Double = 60_000.0 / bpm.toDouble() / 4.0

/** Audio frames per sequencer step at [sampleRate] Hz for [bpm]. */
fun framesPerStepForBpm(sampleRate: Int, bpm: Int): Int =
    (sampleRate * 60.0f / bpm.toFloat() / 4.0f).toInt().coerceAtLeast(1)

const val FRAME_CROSSFADE_DURATION_MS = 200L
const val STROKE_FADE_IN_DURATION_MS = 150L

/**
 * Computes continuous playhead position in step units [0f, maxSteps] from the current
 * [stepIndex], [elapsedMs] since step start, and [stepDurationMs]. Returns -1f when inactive.
 */
fun continuousPlayheadStep(
    stepIndex: Int,
    elapsedMs: Double,
    stepDurationMs: Double,
    maxSteps: Int = 24
): Float {
    if (stepIndex < 0 || stepIndex >= maxSteps || stepDurationMs <= 0.0) return -1f
    val fraction = (elapsedMs / stepDurationMs).coerceIn(0.0, 1.0).toFloat()
    return (stepIndex.toFloat() + fraction).coerceIn(0f, maxSteps.toFloat())
}

/** Pixel x coordinate for [continuousStep] on a grid of width [gridWidth]. */
fun playheadXForStep(continuousStep: Float, gridWidth: Float, maxSteps: Int = 24): Float {
    if (continuousStep < 0f || maxSteps <= 0) return -1f
    return continuousStep * (gridWidth / maxSteps.toFloat())
}

/** Linear 200 ms frame crossfade alphas: (outgoingAlpha, incomingAlpha). */
fun frameCrossfadeAlphas(elapsedMs: Long, durationMs: Long = FRAME_CROSSFADE_DURATION_MS): Pair<Float, Float> {
    if (durationMs <= 0L) return 0f to 1f
    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    return (1f - progress) to progress
}

/** Linear 150 ms fade-in alpha [0f, 1f] for a committed Free-draw stroke. */
fun strokeFadeInAlpha(elapsedMs: Long, durationMs: Long = STROKE_FADE_IN_DURATION_MS): Float {
    if (durationMs <= 0L) return 1f
    return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

data class ProjectStroke(
    val instrument: String,
    val tool: String,
    val points: List<Pair<Float, Float>>,
    val cells: List<Int>
)

/**
 * One frame of the song. A frame is its own 24 x 36 grid of cells; different frames
 * hold different notes. Frames play sequentially (Run 7 §3.1).
 */
data class ProjectFrame(
    val strokes: List<ProjectStroke> = emptyList()
) {
    fun deepCopy(): ProjectFrame = ProjectFrame(
        strokes = strokes.map { it.copy(points = it.points.toList(), cells = it.cells.toList()) }
    )
}

data class MusicProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val steps: Int = 24,
    val rows: Int = PITCH_LINES,
    val bpm: Int = 120,
    val rootMidi: Int = 48,
    val scale: String = "PENTATONIC",
    val bass: Boolean = false,
    val drum: Boolean = false,
    val arpeggio: Boolean = false,
    val showFilledCells: Boolean = false,
    val selectedInstrument: String = "SF_SOFT_KEY",
    val selectedTool: String = "BRUSH",
    /** One entry per frame, in play order. Never empty (a project always has >= 1 frame). */
    val frames: List<ProjectFrame> = listOf(ProjectFrame()),
    /** Index of the frame currently shown in the editor. Saved in the project JSON. */
    val selectedFrame: Int = 0
) {
    /** Strokes of the frame the editor is showing. */
    val selectedStrokes: List<ProjectStroke>
        get() = frames.getOrElse(selectedFrame) { ProjectFrame() }.strokes

    fun withFrameStrokes(index: Int, strokes: List<ProjectStroke>): MusicProject {
        if (index !in frames.indices || frames[index].strokes == strokes) return this
        val next = frames.toMutableList()
        next[index] = ProjectFrame(strokes)
        return copy(frames = next)
    }

    /**
     * Advances to the next frame, or appends a new empty frame at the end when already
     * on the last frame (Fix 16A: drawing reaches step 23).
     */
    fun advanceOrAppendEmptyFrame(): Pair<MusicProject, Boolean> {
        return if (selectedFrame < frames.lastIndex) {
            copy(selectedFrame = selectedFrame + 1) to false
        } else {
            copy(frames = frames + ProjectFrame(), selectedFrame = frames.size) to true
        }
    }

    /**
     * Inserts [frame] at [insertIndex] and selects it (Fix 16B: menu Add/Paste before/after).
     */
    fun insertFrameAt(insertIndex: Int, frame: ProjectFrame): MusicProject {
        val pos = insertIndex.coerceIn(0, frames.size)
        val next = frames.toMutableList()
        next.add(pos, frame)
        return copy(frames = next, selectedFrame = pos)
    }

    fun toJson(): String {
        val beats = JSONObject()
            .put("bass", bass)
            .put("drum", drum)
            .put("arpeggio", arpeggio)
        val frameArray = JSONArray()
        frames.forEach { frame ->
            frameArray.put(JSONObject().put("strokes", strokesToJson(frame.strokes)))
        }
        return JSONObject()
            .put("version", 2)
            .put("id", id)
            .put("title", title)
            .put("createdAt", createdAt)
            .put("updatedAt", System.currentTimeMillis())
            .put("steps", steps)
            .put("rows", rows)
            .put("bpm", bpm.toDouble())
            .put("rootMidi", rootMidi)
            .put("scale", scale)
            .put("beats", beats)
            .put("showFilledCells", showFilledCells)
            .put("selectedInstrument", selectedInstrument)
            .put("selectedTool", selectedTool)
            .put("frames", frameArray)
            .put("selectedFrame", selectedFrame)
            .toString(2)
    }

    private fun strokesToJson(strokes: List<ProjectStroke>): JSONArray {
        val strokeArray = JSONArray()
        strokes.forEach { stroke ->
            val pointArray = JSONArray()
            stroke.points.forEach { (x, y) ->
                pointArray.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
            }
            val cellsArray = JSONArray()
            stroke.cells.forEach(cellsArray::put)
            strokeArray.put(
                JSONObject()
                    .put("instrument", stroke.instrument)
                    .put("tool", stroke.tool)
                    .put("points", pointArray)
                    .put("cells", cellsArray)
            )
        }
        return strokeArray
    }

    companion object {
        /**
         * Parses a project JSON.
         *
         * Format 2 (Run 7) stores `frames`: one entry per frame, each holding that frame's
         * strokes/cells (cell = (line - 1) * steps + step over the 36 pitch lines) plus a
         * `selectedFrame` index. A legacy single-grid file (top-level `strokes`, 24 x 32)
         * loads as exactly ONE frame whose old rows 0-31 map to pitch lines 5-36 - the
         * cells move up by 4 lines ([LEGACY_LINE_OFFSET] cells), nothing is lost and
         * nothing is re-pitched (§3.1.3).
         */
        fun fromJson(json: String): MusicProject {
            val root = JSONObject(json)
            val beats = root.optJSONObject("beats") ?: JSONObject()
            val frameArray = root.optJSONArray("frames")
            val frames: List<ProjectFrame>
            if (frameArray != null && frameArray.length() > 0) {
                frames = buildList {
                    for (i in 0 until frameArray.length()) {
                        val entry = frameArray.optJSONObject(i) ?: continue
                        add(ProjectFrame(strokesFromJson(entry.optJSONArray("strokes"), legacy = false)))
                    }
                }.ifEmpty { listOf(ProjectFrame()) }
            } else {
                frames = listOf(ProjectFrame(strokesFromJson(root.optJSONArray("strokes"), legacy = true)))
            }
            val selectedFrame = root.optInt("selectedFrame", 0).coerceIn(0, frames.lastIndex)
            return MusicProject(
                id = root.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = root.optString("title").ifBlank { "Imported project" },
                createdAt = root.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = root.optLong("updatedAt", System.currentTimeMillis()),
                steps = root.optInt("steps", 24).coerceIn(1, 24),
                rows = PITCH_LINES,
                bpm = root.optDouble("bpm", 120.0).toInt().coerceIn(40, 240),
                rootMidi = root.optInt("rootMidi", 48).coerceIn(0, 108),
                scale = root.optString("scale", "PENTATONIC")
                    .takeIf { id -> musicalScales.any { it.id == id } } ?: "PENTATONIC",
                bass = beats.optBoolean("bass", false),
                drum = beats.optBoolean("drum", false),
                arpeggio = beats.optBoolean("arpeggio", false),
                showFilledCells = root.optBoolean("showFilledCells", false),
                selectedInstrument = root.optString("selectedInstrument", "SF_SOFT_KEY").let { id ->
                    when {
                        instruments.any { it.id == id } -> id
                        InstrumentCatalog.isCustomId(id) || id.startsWith("sf:") -> id
                        id.isNotBlank() -> id
                        else -> "SF_SOFT_KEY"
                    }
                },
                selectedTool = root.optString("selectedTool", "BRUSH")
                    .takeIf { it == "BRUSH" || it == "ERASER" } ?: "BRUSH",
                frames = frames,
                selectedFrame = selectedFrame
            )
        }

        private fun strokesFromJson(array: JSONArray?, legacy: Boolean): List<ProjectStroke> {
            val strokeArray = array ?: JSONArray()
            val cellBound = if (legacy) LEGACY_CELL_BOUND else PITCH_CELL_BOUND
            return buildList {
                for (i in 0 until strokeArray.length()) {
                    val stroke = strokeArray.optJSONObject(i) ?: continue
                    val pointArray = stroke.optJSONArray("points") ?: JSONArray()
                    val points = buildList {
                        for (pointIndex in 0 until pointArray.length()) {
                            val point = pointArray.optJSONArray(pointIndex) ?: continue
                            if (point.length() >= 2) {
                                add(point.optDouble(0).toFloat() to point.optDouble(1).toFloat())
                            }
                        }
                    }
                    val cellArray = stroke.optJSONArray("cells") ?: JSONArray()
                    val cells = buildList {
                        for (cellIndex in 0 until cellArray.length()) {
                            val raw = cellArray.optInt(cellIndex, -1)
                            if (raw < 0 || raw >= cellBound) continue
                            // Legacy row r (0 = top) becomes pitch line r + 5 => cell + 96.
                            add(if (legacy) raw + LEGACY_LINE_OFFSET else raw)
                        }
                    }
                    if (cells.isNotEmpty()) {
                        add(
                            ProjectStroke(
                                instrument = stroke.optString("instrument", "SF_SOFT_KEY"),
                                tool = stroke.optString("tool", "BRUSH"),
                                points = points,
                                cells = cells.distinct()
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Pitch lines of the grid (Run 7 §3.4): 36 lines, line 1 = top = highest pitch. */
const val PITCH_LINES = 36

/** The original app's row count; its formula still governs lines 5-36. */
const val LEGACY_ROWS = 32

/** Cell bound of one 24 x 36 frame. */
const val PITCH_CELL_BOUND = 24 * PITCH_LINES

/**
 * Returns the frame index that both the grid and the frame strip should display:
 * during playback (`isPlaying && playbackFrame >= 0`) it follows [playbackFrame],
 * otherwise it follows [selectedFrame].
 */
fun displayedFrameIndex(
    selectedFrame: Int,
    frameCount: Int,
    isPlaying: Boolean,
    playbackFrame: Int
): Int {
    if (frameCount <= 0) return 0
    val raw = if (isPlaying && playbackFrame >= 0) playbackFrame else selectedFrame
    return raw.coerceIn(0, frameCount - 1)
}

/** Cell bound of a legacy 24 x 32 grid. */
const val LEGACY_CELL_BOUND = 24 * LEGACY_ROWS

/** Legacy cell (old row 0-31) -> 36-line cell: old row r lives on line r + 5. */
const val LEGACY_LINE_OFFSET = 4 * 24

data class CoreInstrument(
    val id: String,
    val name: String,
    val group: String,
    val program: Int
)

val instruments = listOf(
    CoreInstrument("SF_SOFT_KEY", "Soft Key", "Piano", 4),
    CoreInstrument("SF_MARIMBA", "Marimba", "Chromatic percussion", 12),
    CoreInstrument("SF_CHIME", "Chime", "Chromatic percussion", 10),
    CoreInstrument("SF_CELESTE", "Celeste", "Chromatic percussion", 8),
    CoreInstrument("SF_GLOCKENSPIEL", "Glockenspiel", "Chromatic percussion", 9),
    CoreInstrument("SF_VIBRAPHONE", "Vibraphone", "Chromatic percussion", 11),
    CoreInstrument("SF_XYLOPHONE", "Xylophone", "Chromatic percussion", 13),
    CoreInstrument("SF_PLUCK", "Pluck", "Guitar", 24),
    CoreInstrument("SF_BASS", "Bass", "Bass", 38),
    CoreInstrument("SF_FINGER_BASS", "Finger Bass", "Bass", 33),
    CoreInstrument("SF_8_BIT", "8 bit", "Synth lead", 80),
    CoreInstrument("SF_SAW_LEAD", "Saw Lead", "Synth lead", 81),
    CoreInstrument("SF_TONEWHEEL_ORGAN", "Tonewheel Organ", "Organ", 16),
    CoreInstrument("SF_PERCUSSIVE_ORGAN", "Percussive Organ", "Organ", 17),
    CoreInstrument("SF_ACCORDION", "Accordion", "Organ", 21),
    CoreInstrument("SF_HARMONICA", "Harmonica", "Organ", 22),
    CoreInstrument("SF_TRUMPET", "Trumpet", "Brass", 56)
)

data class MusicalScale(val id: String, val label: String, val intervals: List<Int>)

val musicalScales = listOf(
    MusicalScale("PENTATONIC", "Major pentatonic", listOf(0, 2, 4, 7, 9)),
    MusicalScale("MINOR_PENTATONIC", "Minor pentatonic", listOf(0, 3, 5, 7, 10)),
    MusicalScale("MAJOR", "Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    MusicalScale("MINOR", "Minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    MusicalScale("HARMONIC_MINOR", "Harmonic minor", listOf(0, 2, 3, 5, 7, 8, 11)),
    MusicalScale("DORIAN", "Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
    MusicalScale("PHRYGIAN", "Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
    MusicalScale("LYDIAN", "Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
    MusicalScale("MIXOLYDIAN", "Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
    MusicalScale("BLUES", "Blues", listOf(0, 3, 5, 6, 7, 10)),
    MusicalScale("CHROMATIC", "Chromatic", (0..11).toList())
)

val musicalKeys = listOf(
    "C" to 48,
    "C#" to 49,
    "D" to 50,
    "D#" to 51,
    "E" to 52,
    "F" to 53,
    "F#" to 54,
    "G" to 55,
    "G#" to 56,
    "A" to 57,
    "A#" to 58,
    "B" to 59
)

/**
 * Original app's row -> MIDI mapping, unchanged from Run 1-6 (T15 golden tables).
 * `row` is the 0-based screen row of the legacy 32-row grid (row 0 = top).
 */
fun midiForRow(row: Int, rootMidi: Int, scaleId: String, rows: Int = LEGACY_ROWS): Int? {
    val scale = musicalScales.firstOrNull { it.id == scaleId } ?: return null
    val rowCount = rows.coerceAtLeast(1)
    val screenRow = row.coerceIn(0, rowCount - 1)
    val mapperRow = rowCount - 1 - screenRow
    val scaleLength = scale.intervals.size
    val span = 3 * scaleLength
    val degree = when {
        rowCount == 1 -> 0
        span >= rowCount -> mapperRow * span / (rowCount - 1)
        else -> mapperRow * (span + 1) / rowCount
    }
    val midi = rootMidi + 12 * (degree / scaleLength) + scale.intervals[degree % scaleLength]
    return midi.coerceIn(0, 127)
}

/**
 * Run 7's 36-line mapping (§3.4). `line` is 1-based: line 1 = top = highest pitch.
 *
 * Lines 5-36 are the legacy rows 0-31 through the untouched original formula, so every
 * old pitch is preserved (line 5 = old row 0 = C6 at default). Lines 1-4 continue
 * upward from line 5's scale degree (degree span + 1 .. span + 4): at the default
 * C pentatonic that is D6/E6/G6/A6 with line 1 = A6 on top.
 *
 * Computed by `run7_line_table.py` (§3.4.4); locked by [LinePitchTableTest].
 */
fun midiForLine(line: Int, rootMidi: Int, scaleId: String): Int? {
    val scale = musicalScales.firstOrNull { it.id == scaleId } ?: return null
    val l = line.coerceIn(1, PITCH_LINES)
    if (l >= 5) return midiForRow(l - 5, rootMidi, scaleId, LEGACY_ROWS)
    val scaleLength = scale.intervals.size
    val span = 3 * scaleLength
    val degree = span + (5 - l)
    val midi = rootMidi + 12 * (degree / scaleLength) + scale.intervals[degree % scaleLength]
    return midi.coerceIn(0, 127)
}

fun midiNoteName(midi: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return "${names[midi.mod(12)]}${midi / 12 - 1}"
}

fun starterProjects(): List<MusicProject> = (1..78).map { index ->
    val instrument = instruments[(index - 1) % instruments.size]
    val bpm = when (index % 3) {
        0 -> 108
        1 -> 120
        else -> 95
    }
    val cells = (0 until 8).map { beat ->
        val step = (beat * 3 + index) % 24
        val row = 10 + ((beat * 2 + index) % 9)
        // Legacy row -> pitch line (same pitches as the pre-Run-7 generator).
        (row + 4) * 24 + step
    }
    val points = cells.map { cell ->
        ((cell % 24).toFloat() + 0.5f) / 24.0f to ((cell / 24).toFloat() + 0.5f) / 36.0f
    }
    MusicProject(
        id = "starter-${index.toString().padStart(2, '0')}",
        title = "Starter ${index.toString().padStart(2, '0')}",
        bpm = bpm,
        selectedInstrument = instrument.id,
        frames = listOf(ProjectFrame(listOf(ProjectStroke(instrument.id, "BRUSH", points, cells))))
    )
}

object MidiExporter {
    /** Ticks per quarter note written into the MIDI header. */
    const val TICKS_PER_QUARTER = 96

    /** Ticks per grid step: four steps to the quarter note. */
    const val TICKS_PER_STEP = TICKS_PER_QUARTER / 4

    /**
     * Note length in ticks: one step.
     *
     * A drawn note occupies exactly one step, so it is released inside that step (one tick
     * before the next step begins) rather than sustained across later steps.
     */
    const val NOTE_TICKS = TICKS_PER_STEP - 1

    private data class Event(val tick: Int, val priority: Int, val bytes: ByteArray)

    private fun resolveMidiInstrument(id: String, resolver: ((String) -> Pair<Int, Int>?)? = null): Pair<Int, Int>? {
        resolver?.invoke(id)?.let { return it.first.coerceIn(0, 127) to it.second.coerceIn(0, 127) }
        instruments.firstOrNull { it.id == id }?.let { return 0 to it.program }
        if (InstrumentCatalog.isCustomId(id)) {
            val parts = id.split(":")
            if (parts.size >= 4) {
                val bank = parts[parts.size - 2].toIntOrNull() ?: return null
                val program = parts.last().toIntOrNull() ?: return null
                return bank.coerceIn(0, 127) to program.coerceIn(0, 127)
            }
        }
        return null
    }

    /**
     * Exports the WHOLE song: every frame in order (§3.2.5). Frame f starts at
     * step f * steps, so frame count N gives N * 24 steps of ticks. The Loop setting
     * is ignored (single pass).
     */
    fun export(project: MusicProject, resolver: ((String) -> Pair<Int, Int>?)? = null): ByteArray {
        val events = mutableListOf<Event>()
        val usedIds = project.frames.flatMap { frame -> frame.strokes.map { it.instrument } }
            .distinct()
            .filter { resolveMidiInstrument(it, resolver) != null }
        require(usedIds.size <= 16) { "MIDI supports at most 16 simultaneous instrument channels" }
        val channels = usedIds.mapIndexed { index, id -> id to index }.toMap()
        val microsecondsPerQuarter = (60_000_000.0 / project.bpm).toInt()
        events += Event(
            0,
            -2,
            byteArrayOf(
                0xFF.toByte(), 0x51, 0x03,
                (microsecondsPerQuarter shr 16).toByte(),
                (microsecondsPerQuarter shr 8).toByte(),
                microsecondsPerQuarter.toByte()
            )
        )
        usedIds.forEach { id ->
            val channel = channels.getValue(id)
            val (bank, program) = resolveMidiInstrument(id, resolver)!!
            if (bank != 0) {
                events += Event(0, -1, byteArrayOf((0xB0 or channel).toByte(), 0x00, bank.coerceIn(0, 127).toByte()))
                events += Event(0, -1, byteArrayOf((0xB0 or channel).toByte(), 0x20, 0x00))
            }
            events += Event(0, -1, byteArrayOf((0xC0 or channel).toByte(), program.coerceIn(0, 127).toByte()))
        }
        val ticksPerStep = TICKS_PER_STEP
        val noteTicks = NOTE_TICKS
        project.frames.forEachIndexed { frameIndex, frame ->
            val stepOffset = frameIndex * project.steps
            frame.strokes.forEach { stroke ->
                if (resolveMidiInstrument(stroke.instrument, resolver) == null) return@forEach
                val channel = channels[stroke.instrument] ?: return@forEach
                for (cell in stroke.cells) {
                    val lineIndex = cell / project.steps
                    val step = cell % project.steps
                    val note = midiForLine(lineIndex + 1, project.rootMidi, project.scale) ?: continue
                    val startTick = (stepOffset + step) * ticksPerStep
                    events += Event(startTick, 2, byteArrayOf((0x90 or channel).toByte(), note.toByte(), 100))
                    events += Event(startTick + noteTicks, 0, byteArrayOf((0x80 or channel).toByte(), note.toByte(), 0))
                }
            }
        }
        events.sortWith(compareBy<Event> { it.tick }.thenBy { it.priority })
        val track = ByteArrayOutputStream()
        val data = DataOutputStream(track)
        var previousTick = 0
        events.forEach { event ->
            writeVariableLength(data, event.tick - previousTick)
            data.write(event.bytes)
            previousTick = event.tick
        }
        writeVariableLength(data, 0)
        data.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))

        val trackBytes = track.toByteArray()
        val output = ByteArrayOutputStream()
        val midi = DataOutputStream(output)
        midi.writeBytes("MThd")
        midi.writeInt(6)
        midi.writeShort(0)
        midi.writeShort(1)
        midi.writeShort(TICKS_PER_QUARTER)
        midi.writeBytes("MTrk")
        midi.writeInt(trackBytes.size)
        midi.write(trackBytes)
        return output.toByteArray()
    }

    private fun writeVariableLength(output: DataOutputStream, value: Int) {
        var remaining = value
        var buffer = remaining and 0x7F
        while (true) {
            remaining = remaining ushr 7
            if (remaining == 0) break
            buffer = (buffer shl 8) or ((remaining and 0x7F) or 0x80)
        }
        while (true) {
            output.writeByte(buffer and 0xFF)
            if ((buffer and 0x80) == 0) break
            buffer = buffer ushr 8
        }
    }
}

object WavExporter {
    fun export(samples: ShortArray, sampleRate: Int = 44100, channels: Int = 2): ByteArray {
        val dataSize = samples.size * 2
        val output = ByteArrayOutputStream(44 + dataSize)
        val data = DataOutputStream(output)
        data.writeBytes("RIFF")
        writeLittleEndianInt(data, 36 + dataSize)
        data.writeBytes("WAVE")
        data.writeBytes("fmt ")
        writeLittleEndianInt(data, 16)
        writeLittleEndianShort(data, 1)
        writeLittleEndianShort(data, channels)
        writeLittleEndianInt(data, sampleRate)
        writeLittleEndianInt(data, sampleRate * channels * 2)
        writeLittleEndianShort(data, channels * 2)
        writeLittleEndianShort(data, 16)
        data.writeBytes("data")
        writeLittleEndianInt(data, dataSize)
        samples.forEach { writeLittleEndianShort(data, it.toInt()) }
        return output.toByteArray()
    }

    private fun writeLittleEndianInt(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xFF)
        output.writeByte(value ushr 8 and 0xFF)
        output.writeByte(value ushr 16 and 0xFF)
        output.writeByte(value ushr 24 and 0xFF)
    }

    private fun writeLittleEndianShort(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xFF)
        output.writeByte(value ushr 8 and 0xFF)
    }
}
