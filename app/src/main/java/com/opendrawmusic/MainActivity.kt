package com.opendrawmusic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONException
import java.io.File
import java.io.IOException
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var project by mutableStateOf(MusicProject())
    private var status by mutableStateOf("Loading SoundFont")
    private var volume by mutableFloatStateOf(0.8f)
    private var isPlaying by mutableStateOf(false)
    private var loopPlayback by mutableStateOf(false)
    @Volatile private var loopPlaybackEnabled = false
    private var playColumn by mutableIntStateOf(-1)
    @Volatile private var playStepIndex = -1
    @Volatile private var playStepStartNs = 0L
    @Volatile private var playStepDurationNs = 125_000_000L
    private var playheadStepPosition by mutableFloatStateOf(-1f)
    /** Frame currently being played back; -1 when playback is stopped (strip highlight). */
    private var playbackFrame by mutableIntStateOf(-1)
    /** Set while playing to jump the sequencer to a frame immediately (§3.2.4). */
    @Volatile private var playbackJumpFrame = -1
    private var selectedRow by mutableIntStateOf(20)
    private var picker by mutableStateOf<String?>(null)
    /** Frame strip long-press menu: index of the thumbnail whose menu is open, -1 = none. */
    private var stripMenuFrame by mutableIntStateOf(-1)
    /** Frame strip clipboard for Copy / Paste (Fix 16B: holds one copied frame in memory). */
    private var copiedFrame by mutableStateOf<ProjectFrame?>(null)
    /** Frame strip delete confirmation dialog: index awaiting confirm, -1 = none. */
    private var confirmDeleteFrame by mutableIntStateOf(-1)
    /** Export format popup (Figma "Export" frame: "Choose a format"). */
    private var exportPopup by mutableStateOf(false)
    /** Tempo slider popup opened by long-pressing the BPM number in the tempo pill. */
    private var showTempoPopup by mutableStateOf(false)
    @Volatile private var livePlaybackBpm = 120
    private var showLibrary by mutableStateOf(false)
    private var showHelp by mutableStateOf(false)
    private var showSoundFonts by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    /**
     * Brush type, per the Figma Settings "Brush type" toggle (§3.5):
     * "FREE_DRAW" (default) = a thin smooth line follows the finger live, blocks appear
     * on finger lift; "BLOCK" = note blocks appear progressively cell by cell during the
     * drag. Both commit the same cells on lift, so the final notes are identical (T27).
     * UI setting only (not a per-project musical setting); in-memory like volume.
     */
    private var brushType by mutableStateOf("FREE_DRAW")
    private var libraryProjects by mutableStateOf(emptyList<MusicProject>())
    private var testTrack: AudioTrack? = null
    private var playbackTrack: AudioTrack? = null
    @Volatile private var playbackGeneration = 0
    private var playbackThread: Thread? = null
    private var pendingExport: ByteArray? = null

    /**
     * Per-frame undo/redo (Run 7 §3.1.5): every frame has its own independent stacks,
     * held in memory only - never saved to the project JSON. Undo/Redo always acts on
     * the currently selected frame.
     */
    private val frameHistory = FrameHistory()

    private val loadedSoundFonts = linkedMapOf<String, LoadedSoundFont>()
    private var coreExtraOptions: List<InstrumentOption> = emptyList()
    private var hiddenSampleNames by mutableStateOf(emptySet<String>())
    private var soundFontRevision by mutableIntStateOf(0)
    private var instrumentOptions by mutableStateOf(InstrumentCatalog.coreOptions())

    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProject(uri)
    }

    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) writeExport(uri)
    }

    private val addSoundFontLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addUserSoundFont(uri)
    }

    init {
        System.loadLibrary("opendrawmusic-native")
    }

    external fun loadCoreSoundFont(path: String): Int
    external fun loadExtraSoundFont(path: String): Int
    external fun renderTestNote(midi: Int, seconds: Float, output: ShortArray): Int
    external fun renderChord(midiNotes: IntArray, seconds: Float, sfid: Int, bank: Int, program: Int, gain: Float, output: ShortArray): Int
    external fun resetPlaybackSynth()
    external fun renderSequencerStep(
        noteOffChannels: IntArray,
        noteOffMidis: IntArray,
        noteOnChannels: IntArray,
        noteOnMidis: IntArray,
        noteOnSfids: IntArray,
        noteOnBanks: IntArray,
        noteOnPrograms: IntArray,
        frames: Int,
        gain: Float,
        output: ShortArray
    ): Int
    external fun stopSynth()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Run 7 §3.7: load the original app's verified colour/icon data (read-only copy
        // in assets/) plus the labelled PROPOSALs before any UI is composed.
        InstrumentPalette.init(this)
        val corePath = copyAsset("core.sf2", force = true)
        val coreSfid = loadCoreSoundFont(corePath)
        if (coreSfid < 0) {
            status = "Error: Core SoundFont load failed"
        } else {
            status = "Core bundle loaded, preparing instruments..."
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6750A4),
                    onPrimary = Color.White,
                    background = Color(0xFFFEF7FF),
                    surface = Color(0xFFFEF7FF)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorScreen()
                }
            }
        }
        if (coreSfid >= 0) {
            thread(name = "soundfont-init") { initializeSoundFonts() }
        }
    }

    override fun onDestroy() {
        stopPlayback()
        testTrack?.release()
        testTrack = null
        stopSynth()
        super.onDestroy()
    }

    private fun initializeSoundFonts() {
        Log.i("OpenDrawMusic", "initializeSoundFonts start")
        try {
            // Remove old bundled entries now merged into the single core bundle
            try {
                val current = SoundFontRegistry.loadEntries(this)
                val cleaned = current.filterNot {
                    it.bundledAsset?.contains("merlin_orchestra", ignoreCase = true) == true ||
                        it.fileName.contains("merlin_orchestra", ignoreCase = true) ||
                        it.bundledAsset?.contains("GeneralUser-GS", ignoreCase = true) == true ||
                        it.fileName.equals("GeneralUser-GS.sf2", ignoreCase = true)
                }
                if (cleaned.size != current.size) {
                    current.filter { it !in cleaned }.forEach { e -> try { SoundFontRegistry.fileFor(this, e).delete() } catch (_: Exception) {} }
                    SoundFontRegistry.saveEntries(this, cleaned)
                    Log.i("OpenDrawMusic", "Removed old bundled extra SoundFont entries (merged into core bundle)")
                }
            } catch (e: Exception) {
                Log.w("OpenDrawMusic", "Bundled-entry migration skipped", e)
            }

            // Parse presets from the single bundled core SoundFont (core.sf2 = merged bundle)
            val corePath = File(filesDir, "core.sf2")
            val corePresets = try { Sf2Parser.parsePresets(corePath) } catch (e: Exception) {
                Log.e("OpenDrawMusic", "Failed to parse core bundle presets", e)
                emptyList()
            }
            val curatedIds = instruments.map { it.id }.toSet()
            val curatedPairs = instruments.map { 0 to it.program }.toSet()
            val coreCurated = InstrumentCatalog.coreOptions()
            val coreExtras = corePresets
                .filterNot { (it.bank to it.program) in curatedPairs }
                .map { p ->
                    InstrumentOption(
                        id = InstrumentCatalog.customId(CORE_SOUNDFONT_ID, p.bank, p.program),
                        name = p.name,
                        group = categoryForPreset(p.bank, p.program),
                        soundFontId = CORE_SOUNDFONT_ID,
                        bank = p.bank,
                        program = p.program
                    )
                }

            // Load any user-added extra SoundFonts
            val entries = SoundFontRegistry.loadEntries(this)
            Log.i("OpenDrawMusic", "User SoundFont registry entries=${entries.size}")
            val loaded = linkedMapOf<String, LoadedSoundFont>()
            val custom = mutableListOf<InstrumentOption>()
            var loadedCount = 0
            for (entry in entries) {
                val file = SoundFontRegistry.fileFor(this, entry)
                if (!file.exists() && entry.bundledAsset != null) {
                    try {
                        assets.open(entry.bundledAsset).use { input -> file.outputStream().use { input.copyTo(it) } }
                    } catch (e: Exception) {
                        Log.e("OpenDrawMusic", "Failed to restore bundled asset ${entry.displayName}", e)
                    }
                }
                if (!file.exists()) {
                    Log.w("OpenDrawMusic", "SoundFont file missing: ${file.absolutePath}")
                    continue
                }
                val presets = try { Sf2Parser.parsePresets(file) } catch (e: Exception) {
                    Log.e("OpenDrawMusic", "Preset parse failed for ${entry.displayName}", e)
                    emptyList()
                }
                Log.i("OpenDrawMusic", "Loading extra SoundFont ${entry.displayName} presets=${presets.size} path=${file.absolutePath}")
                val sfid = loadExtraSoundFont(file.absolutePath)
                Log.i("OpenDrawMusic", "Loaded extra SoundFont ${entry.displayName} sfid=$sfid")
                if (sfid >= 0) {
                    loaded[entry.id] = LoadedSoundFont(entry, sfid, presets)
                    custom += InstrumentCatalog.customOptions(entry, presets)
                    loadedCount++
                } else {
                    Log.w("OpenDrawMusic", "Synth failed to load ${entry.displayName}")
                }
            }

            val hiddenSamples = SoundFontRegistry.hiddenSampleNames(this)
            val options = coreCurated + coreExtras + custom
            runOnUiThread {
                coreExtraOptions = coreExtras
                hiddenSampleNames = hiddenSamples
                loadedSoundFonts.clear()
                loadedSoundFonts.putAll(loaded)
                soundFontRevision++
                instrumentOptions = options
                status = "Bundle loaded: ${corePresets.size} preset(s) + $loadedCount user SoundFont(s)"
            }
            Log.i("OpenDrawMusic", "initializeSoundFonts done corePresets=${corePresets.size} coreExtras=${coreExtras.size} userLoaded=$loadedCount")
        } catch (error: Throwable) {
            Log.e("OpenDrawMusic", "initializeSoundFonts failed", error)
            runOnUiThread { status = "SoundFont init failed: ${error.message ?: error::class.java.simpleName}" }
        }
    }

    private fun resolveOption(id: String): InstrumentOption? {
        instrumentOptions.firstOrNull { it.id == id }?.let { return it }
        return resolveInstrument(id, instrumentOptions)
    }

    private fun resolveBankProgram(id: String): Pair<Int, Int>? {
        instruments.firstOrNull { it.id == id }?.let { return 0 to it.program }
        if (InstrumentCatalog.isCustomId(id)) {
            val parts = id.split(":")
            if (parts.size >= 4) {
                val bank = parts[parts.size - 2].toIntOrNull()
                val program = parts.last().toIntOrNull()
                if (bank != null && program != null) return bank to program
            }
        }
        return null
    }

    private fun sfidFor(option: InstrumentOption): Int {
        if (option.soundFontId == CORE_SOUNDFONT_ID) return -1
        return loadedSoundFonts[option.soundFontId]?.sfid ?: -1
    }

    private fun instrumentLabel(id: String): String = resolveOption(id)?.name ?: id

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EditorScreen() {
        val displayedPitch = midiForLine(selectedRow + 1, project.rootMidi, project.scale)
        // Live brush overlay (§3.5): fed by the grid gesture, cleared when the finger lifts.
        var liveCells by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var livePath by remember { mutableStateOf<List<Offset>>(emptyList()) }
        // Part C: 150 ms linear fade-in for newly committed Free-draw stroke blocks.
        var fadeInCells by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var fadeInAlpha by remember { mutableFloatStateOf(1f) }
        var fadeInGeneration by remember { mutableIntStateOf(0) }
        LaunchedEffect(fadeInGeneration) {
            if (fadeInGeneration == 0 || fadeInCells.isEmpty()) return@LaunchedEffect
            val startNs = System.nanoTime()
            while (true) {
                val finished = withFrameNanos { nowNs ->
                    val elapsedMs = ((nowNs - startNs) / 1_000_000L).coerceAtLeast(0L)
                    fadeInAlpha = strokeFadeInAlpha(elapsedMs)
                    elapsedMs >= STROKE_FADE_IN_DURATION_MS
                }
                if (finished) break
            }
            fadeInAlpha = 1f
            fadeInCells = emptySet()
        }
        // Part A: Choreographer-driven continuous playhead position from the playback clock.
        LaunchedEffect(isPlaying) {
            if (!isPlaying) {
                if (playColumn < 0) playheadStepPosition = -1f
                return@LaunchedEffect
            }
            while (isPlaying) {
                withFrameNanos { nowNs ->
                    val idx = playStepIndex
                    val durNs = playStepDurationNs
                    if (idx in 0 until project.steps && durNs > 0L) {
                        val elapsedMs = ((nowNs - playStepStartNs).coerceAtLeast(0L)) / 1_000_000.0
                        val durMs = durNs / 1_000_000.0
                        playheadStepPosition = continuousPlayheadStep(idx, elapsedMs, durMs, project.steps)
                    }
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFEF7FF))
        ) {
            val compactWidth = maxWidth < 320.dp
            val horizontalPadding = if (compactWidth) 10.dp else 12.5.dp
            val contentWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(240.dp)
            val s = (contentWidth / 422.dp).coerceIn(0.70f, 1.05f)
            val primaryButtonColors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6750A4),
                contentColor = Color.White
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 0.dp)
            ) {
            Text(
                "Untitled",
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1D1B20)
            )
            Spacer(Modifier.height(if (compactWidth) 8.dp else (21f * s).dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .width((172f * s).dp)
                        .height(56.dp)
                        .shadow(2.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x1F1C1724), spotColor = Color(0x1F1C1724))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFFFFFFF))
                        .border(1.dp, Color(0xFFEAE6ED), RoundedCornerShape(18.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { changeProject(project.copy(bpm = (project.bpm - 5).coerceAtLeast(40))) },
                        modifier = Modifier.padding(start = (12f * s).dp).size((32f * s).dp, 28.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = primaryButtonColors,
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("\u2212", fontSize = (20f * s).coerceAtLeast(16f).sp, fontWeight = FontWeight.Medium, color = Color.White) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = { showTempoPopup = true }
                            )
                            .semantics { contentDescription = "BPM ${project.bpm}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${project.bpm}",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = (26f * s).coerceAtLeast(19f).sp),
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1D1B20)
                        )
                    }
                    Button(
                        onClick = { changeProject(project.copy(bpm = (project.bpm + 5).coerceAtMost(240))) },
                        modifier = Modifier.padding(end = (12f * s).dp).size((32f * s).dp, 28.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = primaryButtonColors,
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("+", fontSize = (20f * s).coerceAtLeast(16f).sp, fontWeight = FontWeight.Medium, color = Color.White) }
                }
                Button(
                    onClick = { clearProject() },
                    modifier = Modifier.width((76f * s).dp).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = primaryButtonColors,
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Clear", maxLines = 1, softWrap = false, fontSize = (20f * s).coerceAtLeast(14f).sp, fontWeight = FontWeight.Medium, color = Color.White) }
                Button(
                    onClick = { exportPopup = true },
                    modifier = Modifier.width((128f * s).dp).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = primaryButtonColors,
                    contentPadding = PaddingValues(horizontal = (12f * s).dp, vertical = 0.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((6f * s).dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_export_folder),
                            contentDescription = null,
                            modifier = Modifier.size((22f * s).coerceAtLeast(16f).dp)
                        )
                        Text("Export", maxLines = 1, softWrap = false, fontSize = (20f * s).coerceAtLeast(14f).sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
            // Toolbar: single row on white pill (422 x 56 scaled, radius 18, gap 5).
            // Pencil/Eraser toggle, Loop pill, and generic Instrument/Key/Musical Scale labels.
            Spacer(Modifier.height(if (compactWidth) 8.dp else (21f * s).dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFFFFFF))
                    .padding(horizontal = (4f * s).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((5f * s).dp)
            ) {
                Button(
                    onClick = {
                        val nextTool = if (project.selectedTool == "BRUSH") "ERASER" else "BRUSH"
                        changeProject(project.copy(selectedTool = nextTool))
                    },
                    colors = primaryButtonColors,
                    modifier = Modifier.size((44f * s).dp, 40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Image(
                        painterResource(if (project.selectedTool == "ERASER") R.drawable.ic_eraser else R.drawable.ic_pencil),
                        contentDescription = if (project.selectedTool == "ERASER") "Eraser" else "Pencil",
                        modifier = Modifier.size((20f * s).coerceAtLeast(16f).dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                Button(
                    onClick = {
                        loopPlayback = !loopPlayback
                        loopPlaybackEnabled = loopPlayback
                    },
                    modifier = Modifier.size((56f * s).dp, 36.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (loopPlayback) Color(0xFF6750A4) else Color(0xFFE9DDF9),
                        contentColor = if (loopPlayback) Color.White else Color(0xFF6750A4)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Image(
                        painterResource(if (loopPlayback) R.drawable.ic_loop_on else R.drawable.ic_loop_off),
                        contentDescription = if (loopPlayback) "Loop on" else "Loop off",
                        modifier = Modifier.size((20f * s).coerceAtLeast(16f).dp)
                    )
                }
                DropdownToolbarButton("Instrument", Modifier.weight(111f), (16f * s).coerceAtLeast(12f).sp) { picker = "instrument" }
                DropdownToolbarButton("C", Modifier.weight(59f), (16f * s).coerceAtLeast(12f).sp) { picker = "key" }
                DropdownToolbarButton("Musical Scale", Modifier.weight(132f), (16f * s).coerceAtLeast(12f).sp) { picker = "scale" }
            }
            Spacer(Modifier.height(if (compactWidth) 8.dp else (21f * s).dp))
            Text(
                if (displayedPitch == null) "Line ${selectedRow + 1}: Unknown" else "Line ${selectedRow + 1}: ${midiNoteName(displayedPitch)}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.25.sp,
                    color = Color(0xFF1D1B20)
                ),
                modifier = Modifier.height(24.dp)
            )
            val displayedFrame = displayedFrameIndex(
                selectedFrame = project.selectedFrame,
                frameCount = project.frames.size,
                isPlaying = isPlaying,
                playbackFrame = playbackFrame
            )
            // Part B: 200 ms linear crossfade when switching frames (auto-advance or manual tap).
            var currentGridFrame by remember { mutableIntStateOf(displayedFrame) }
            var outgoingGridFrame by remember { mutableIntStateOf(-1) }
            var crossfadeOutgoingAlpha by remember { mutableFloatStateOf(0f) }
            var crossfadeIncomingAlpha by remember { mutableFloatStateOf(1f) }
            LaunchedEffect(displayedFrame) {
                if (displayedFrame == currentGridFrame) return@LaunchedEffect
                outgoingGridFrame = currentGridFrame
                currentGridFrame = displayedFrame
                crossfadeOutgoingAlpha = 1f
                crossfadeIncomingAlpha = 0f
                val startNs = System.nanoTime()
                while (true) {
                    val finished = withFrameNanos { nowNs ->
                        val elapsedMs = ((nowNs - startNs) / 1_000_000L).coerceAtLeast(0L)
                        val (outA, inA) = frameCrossfadeAlphas(elapsedMs)
                        crossfadeOutgoingAlpha = outA
                        crossfadeIncomingAlpha = inA
                        elapsedMs >= FRAME_CROSSFADE_DURATION_MS
                    }
                    if (finished) break
                }
                outgoingGridFrame = -1
                crossfadeOutgoingAlpha = 0f
                crossfadeIncomingAlpha = 1f
            }
            val displayedStrokes = project.frames.getOrElse(displayedFrame) { ProjectFrame() }.strokes
            val outgoingStrokes = if (outgoingGridFrame in project.frames.indices && crossfadeOutgoingAlpha > 0f) {
                project.frames[outgoingGridFrame].strokes
            } else {
                emptyList()
            }
            GridCanvas(
                project = project,
                strokes = displayedStrokes,
                outgoingStrokes = outgoingStrokes,
                outgoingAlpha = crossfadeOutgoingAlpha,
                incomingAlpha = crossfadeIncomingAlpha,
                fadeInCells = fadeInCells,
                fadeInAlpha = fadeInAlpha,
                frameIndex = displayedFrame,
                playheadStep = if (playheadStepPosition >= 0f) playheadStepPosition else playColumn.toFloat(),
                liveCells = liveCells,
                livePath = livePath,
                onStrokeStart = {
                    if (fadeInCells.isNotEmpty()) {
                        fadeInAlpha = 1f
                        fadeInCells = emptySet()
                    }
                },
                onLiveChange = { cells, path -> liveCells = cells; livePath = path },
                onCellsPainted = { cells, points ->
                    selectedRow = (cells.lastOrNull()?.div(project.steps) ?: selectedRow).coerceIn(0, project.rows - 1)
                    if (brushType == "FREE_DRAW" && project.selectedTool == "BRUSH" && cells.isNotEmpty()) {
                        fadeInCells = cells.toSet()
                        fadeInAlpha = 0f
                        fadeInGeneration++
                    }
                    editCells(cells, points)
                },
                onAdvanceFrame = {
                    fadeInCells = emptySet()
                    advanceFrameOnEdge()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.height(if (compactWidth) 8.dp else (21f * s).dp))
            FrameStrip(s)
            Spacer(Modifier.height(if (compactWidth) 8.dp else (21f * s).dp))
            // Figma bottom rail: 422 x 74 scaled, radius 18, bg #FFFFFF.
            // Undo (44), Redo (44), Save to library (140), Library (82), Settings (80, #E9DDF9).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFFFFFF))
                    .padding(horizontal = (4f * s).dp),
                horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { undo() },
                    enabled = frameHistory.canUndo(project.selectedFrame),
                    colors = primaryButtonColors,
                    modifier = Modifier.weight(44f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Image(painterResource(R.drawable.ic_undo), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White)) }
                Button(
                    onClick = { redo() },
                    enabled = frameHistory.canRedo(project.selectedFrame),
                    colors = primaryButtonColors,
                    modifier = Modifier.weight(44f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Image(painterResource(R.drawable.ic_redo), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White)) }
                Button(
                    onClick = { saveProject() },
                    colors = primaryButtonColors,
                    modifier = Modifier.weight(140f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = (8f * s).dp)
                ) { Text("Save to library", maxLines = 1, softWrap = false, fontSize = (18f * s).coerceAtLeast(13f).sp, fontWeight = FontWeight.Medium, color = Color.White) }
                Button(
                    onClick = { libraryProjects = savedProjects(); showLibrary = true },
                    colors = primaryButtonColors,
                    modifier = Modifier.weight(82f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = (8f * s).dp)
                ) { Text("Library", maxLines = 1, softWrap = false, fontSize = (18f * s).coerceAtLeast(13f).sp, fontWeight = FontWeight.Medium, color = Color.White) }
                Button(
                    onClick = { showSettings = true },
                    modifier = Modifier.weight(80f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE9DDF9),
                        contentColor = Color(0xFF6750A4)
                    )
                ) {
                    Image(painterResource(R.drawable.ic_settings), null, Modifier.size(22.dp))
                }
            }
        }
        picker?.let { kind ->
            when (kind) {
                "instrument" -> InstrumentPickerDialog(
                    options = instrumentOptions,
                    selected = project.selectedInstrument,
                    onDismiss = { picker = null },
                    onSelect = { id -> changeProject(project.copy(selectedInstrument = id)); picker = null }
                )
                "key" -> PickerDialog("Key", musicalKeys.map { it.first to it.second.toString() }, project.rootMidi.toString(), { picker = null }) { root -> changeProject(project.copy(rootMidi = root.toInt())); picker = null }
                "scale" -> PickerDialog("Musical Scale", musicalScales.map { it.label to it.id }, project.scale, { picker = null }) { scale -> changeProject(project.copy(scale = scale)); picker = null }
            }
        }
        if (showSettings) {
            SettingsPopup()
        }
        if (showTempoPopup) {
            TempoPopup(
                bpm = project.bpm,
                onBpmChange = { nextBpm -> changeProject(project.copy(bpm = nextBpm)) },
                onDismiss = { showTempoPopup = false }
            )
        }
        if (exportPopup) {
            ExportPopup(
                onWav = { exportPopup = false; exportWav() },
                onMidi = { exportPopup = false; exportMidi() },
                onDismiss = { exportPopup = false }
            )
        }
        if (showLibrary) {
            LibraryDialog(libraryProjects, { showLibrary = false }) { selected -> loadProject(selected); showLibrary = false; status = "Loaded ${selected.title}" }
        }
        if (showSoundFonts) {
            val loadedEntries = remember(soundFontRevision) { loadedSoundFonts.values.map { it.entry } }
            val configItems = remember(hiddenSampleNames, loadedEntries) {
                SoundFontRegistry.buildConfigItems(hiddenSampleNames, loadedEntries)
            }
            SoundFontDialog(
                items = configItems,
                onDismiss = { showSoundFonts = false },
                onDelete = { item -> deleteSoundFontConfigItem(item) }
            )
        }
        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                title = { Text("Editor notes") },
                text = {
                    Text(
                        "Grid: 24 steps by 36 pitch lines, all visible at once. Lines 5-36 use the original app's scale/key mapping (line 5 is C6 in C major pentatonic); lines 1-4 rise above it. Single bundled SoundFont: GeneralUser GS v2.0.3 (merged bundle, personal use) loaded as core.sf2 with all its presets available; the 17 core instrument IDs are mapped to the same bank/program slots in GS. Extra user SoundFonts can still be added via the SoundFonts menu (stored privately in app storage)."
                    )
                },
                confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } }
            )
        }
        }
    }

    /** Selected/unselected segment colours for the Pencil/Eraser/Shuffle toolbar. */
    @Composable
    private fun toolSegmentColors(active: Boolean) = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = if (active) Color(0xFF6750A4) else Color(0xFFECE6F0),
        contentColor = if (active) Color.White else Color(0xFF1D1B20)
    )

    @Composable
    private fun DropdownToolbarButton(
        label: String,
        modifier: Modifier = Modifier,
        fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.height(36.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6750A4),
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = fontSize),
                fontWeight = FontWeight.Medium
            )
        }
    }

    /**
     * Dropdown-style button (Figma: bg #fef7ff, border #e5e0f0, radius 12, label + caret).
     * Used for Instrument / Key / Musical Scale in the toolbar.
     */
    @Composable
    private fun DropdownButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier.height(34.dp),
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFEF7FF),
                contentColor = Color(0xFF1D1B20)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E0F0)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text("$label ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }

    /**
     * Per-instrument cell colour: each instrument's OWN stored colour from the original
     * app's color map (Run 7 §3.7, verbatim - never re-hued). The 13 bank-120 kits carry
     * the labelled PROPOSAL colour (reused from the matching bank-128 kit). Instruments
     * with no original colour (user-added SoundFont presets - outside the original data)
     * keep the pre-existing family palette. Shared by the grid and the frame-strip
     * thumbnails so they always agree.
     */
    private fun strokeColorFor(stroke: ProjectStroke): Color {
        val option = resolveOption(stroke.instrument)
        if (option != null) {
            InstrumentPalette.colorHex(option.bank, option.program)?.let { hex ->
                return Color(InstrumentPalette.parseColor(hex))
            }
        }
        return when (option?.group) {
            "Chromatic percussion" -> Color(0xFFEEA63A)
            "Guitar", "Bass" -> Color(0xFF4F9D8F)
            "Synth lead" -> Color(0xFFE56B6F)
            "Organ", "Brass" -> Color(0xFF8269C6)
            else -> if (option?.soundFontId != CORE_SOUNDFONT_ID) Color(0xFF2E8B57) else Color(0xFF5146A5)
        }
    }

    /**
     * Frame strip (§3.3): sits directly below the note grid. One thumbnail per frame in
     * order with its frame number, purple border on the highlighted frame (the PLAYING
     * frame during playback, otherwise the selected one), a purple + button after the
     * last thumbnail, and a large purple Play button at the right end. The row scrolls
     * horizontally (LazyRow) so 10+ frames never clip. Long-press (>= 500 ms, no
     * movement - a swipe just scrolls) opens the "Delete frame" menu + confirm dialog.
     * NO floating piano button (§3.3.7).
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FrameStrip(s: Float = 1f) {
        val highlight = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = isPlaying,
            playbackFrame = playbackFrame
        )
        val canDelete = project.frames.size > 1
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((8f * s).dp)
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .height(70.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(project.frames.size) { index ->
                    FrameThumbnail(
                        number = index + 1,
                        strokes = project.frames[index].strokes,
                        highlighted = index == highlight,
                        canDelete = canDelete,
                        hasCopiedFrame = copiedFrame != null,
                        menuExpanded = stripMenuFrame == index,
                        onMenuExpand = { stripMenuFrame = index },
                        onMenuDismiss = { stripMenuFrame = -1 },
                        onClick = { selectFrame(index) },
                        onAddBefore = {
                            stripMenuFrame = -1
                            addFrameAt(index)
                        },
                        onAddAfter = {
                            stripMenuFrame = -1
                            addFrameAt(index + 1)
                        },
                        onCopyClick = {
                            stripMenuFrame = -1
                            copyFrame(index)
                        },
                        onPasteBefore = {
                            stripMenuFrame = -1
                            pasteFrame(index)
                        },
                        onPasteAfter = {
                            stripMenuFrame = -1
                            pasteFrame(index + 1)
                        },
                        onDeleteClick = {
                            stripMenuFrame = -1
                            confirmDeleteFrame = index
                        }
                    )
                }
                item(key = "empty-frame-slot") {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .height(62.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFEF7FF))
                            .border(1.dp, Color(0xFFE2DCE3), RoundedCornerShape(8.dp))
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width((78f * s).coerceAtLeast(54f).dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF6750A4))
                    .semantics { contentDescription = "Add frame" }
                    .clickable { addFrame() },
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_add_layer), null, Modifier.size((32f * s).coerceAtLeast(24f).dp))
            }
            Box(
                modifier = Modifier
                    .width((70f * s).coerceAtLeast(54f).dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF6750A4))
                    .semantics { contentDescription = if (isPlaying) "Stop" else "Play" }
                    .clickable { if (isPlaying) stopPlayback() else startPlayback() },
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_play), null, Modifier.size((32f * s).coerceAtLeast(24f).dp))
            }
        }
        if (confirmDeleteFrame in project.frames.indices) {
            val target = confirmDeleteFrame
            AlertDialog(
                onDismissRequest = { confirmDeleteFrame = -1 },
                title = { Text("Delete frame ${target + 1}?") },
                text = { Text("Removes this frame and its notes. Deleting cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteFrame = -1
                        deleteFrame(target)
                    }) { Text("Delete frame") }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteFrame = -1 }) { Text("Cancel") } }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FrameThumbnail(
        number: Int,
        strokes: List<ProjectStroke>,
        highlighted: Boolean,
        canDelete: Boolean,
        hasCopiedFrame: Boolean,
        menuExpanded: Boolean,
        onMenuExpand: () -> Unit,
        onMenuDismiss: () -> Unit,
        onClick: () -> Unit,
        onAddBefore: () -> Unit,
        onAddAfter: () -> Unit,
        onCopyClick: () -> Unit,
        onPasteBefore: () -> Unit,
        onPasteAfter: () -> Unit,
        onDeleteClick: () -> Unit
    ) {
        Box {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (highlighted) 2.dp else 1.dp,
                        color = if (highlighted) Color(0xFF6750A4) else Color(0xFFE2DCE3),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(Color(0xFFFEF7FF))
                    .semantics { contentDescription = "Frame $number" }
                    .combinedClickable(
                        onClick = onClick,
                        onLongClickLabel = "Frame actions",
                        onLongClick = onMenuExpand
                    )
            ) {
                Canvas(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 5.dp)
                        .width(76.dp)
                        .height(40.161.dp)
                        .background(Color(0xFFF8F7FC))
                ) {
                    val cellW = size.width / project.steps
                    val cellH = size.height / project.rows
                    strokes.forEach { stroke ->
                        val color = strokeColorFor(stroke)
                        stroke.cells.forEach { cell ->
                            val line = cell / project.steps
                            val step = cell % project.steps
                            if (line in 0 until project.rows && step in 0 until project.steps) {
                                drawRect(
                                    color,
                                    topLeft = Offset(step * cellW, line * cellH),
                                    size = Size(cellW.coerceAtLeast(1f), cellH.coerceAtLeast(1f))
                                )
                            }
                        }
                    }
                    val verticalSpacing = size.width / 68f
                    for (line in 0..68) {
                        val x = line * verticalSpacing
                        drawLine(Color(0xFFE2E0E9), Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.45f)
                    }
                    val horizontalSpacing = size.height / 36f
                    for (line in 0..36) {
                        val y = line * horizontalSpacing
                        drawLine(Color(0xFFE2E0E9), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.617f)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 0.dp, bottom = 6.dp)
                        .size(width = 26.dp, height = 18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (highlighted) Color(0xFF6750A4) else Color(0xFFE8DEF8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$number",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (highlighted) Color.White else Color(0xFF1D1B20)
                    )
                }
            }
            if (menuExpanded) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, -170),
                    onDismissRequest = onMenuDismiss,
                    properties = PopupProperties(focusable = true)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFEF7FF),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E0F0)),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasCopiedFrame) {
                                FrameMenuAction(
                                    label = "Paste",
                                    iconRes = R.drawable.ic_paste_left,
                                    contentDesc = "Paste before",
                                    onClick = onPasteBefore
                                )
                            }
                            FrameMenuAction(
                                label = "Add",
                                iconRes = R.drawable.ic_add_left,
                                contentDesc = "Add before",
                                onClick = onAddBefore
                            )
                            FrameMenuAction(
                                label = "Copy",
                                iconRes = R.drawable.ic_copy,
                                contentDesc = "Copy frame",
                                onClick = onCopyClick
                            )
                            FrameMenuAction(
                                label = "Delete",
                                iconRes = R.drawable.ic_trash_red,
                                contentDesc = "Delete frame",
                                textColor = Color(0xFFB3261E),
                                tintIcon = false,
                                enabled = canDelete,
                                onClick = onDeleteClick
                            )
                            FrameMenuAction(
                                label = "Add",
                                iconRes = R.drawable.ic_add_right,
                                contentDesc = "Add after",
                                onClick = onAddAfter
                            )
                            if (hasCopiedFrame) {
                                FrameMenuAction(
                                    label = "Paste",
                                    iconRes = R.drawable.ic_paste_right,
                                    contentDesc = "Paste after",
                                    onClick = onPasteAfter
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FrameMenuAction(
        label: String,
        iconRes: Int,
        contentDesc: String,
        textColor: Color = Color(0xFF1D1B20),
        tintIcon: Boolean = true,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .width(50.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = contentDesc },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = if (tintIcon) ColorFilter.tint(textColor) else null
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    @Composable
    private fun GridCanvas(
        project: MusicProject,
        strokes: List<ProjectStroke>,
        outgoingStrokes: List<ProjectStroke>,
        outgoingAlpha: Float,
        incomingAlpha: Float,
        fadeInCells: Set<Int>,
        fadeInAlpha: Float,
        frameIndex: Int,
        playheadStep: Float,
        liveCells: Set<Int>,
        livePath: List<Offset>,
        onStrokeStart: () -> Unit,
        onLiveChange: (Set<Int>, List<Offset>) -> Unit,
        onCellsPainted: (Set<Int>, List<Pair<Float, Float>>) -> Unit,
        onAdvanceFrame: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val currentOnStrokeStart by rememberUpdatedState(onStrokeStart)
        val currentOnLiveChange by rememberUpdatedState(onLiveChange)
        val currentOnCellsPainted by rememberUpdatedState(onCellsPainted)
        val currentOnAdvanceFrame by rememberUpdatedState(onAdvanceFrame)
        val outgoingCellColors = remember(outgoingStrokes, instrumentOptions) {
            if (outgoingStrokes.isEmpty()) emptyMap()
            else buildMap<Int, Color> {
                outgoingStrokes.forEach { stroke ->
                    val color = strokeColorFor(stroke)
                    stroke.cells.forEach { put(it, color) }
                }
            }
        }
        val cellColors = remember(strokes, instrumentOptions, liveCells, brushType) {
            val colors = mutableMapOf<Int, Color>()
            strokes.forEach { stroke ->
                val color = strokeColorFor(stroke)
                stroke.cells.forEach { colors[it] = color }
            }
            // Live brush feedback (§3.5): cells picked up so far, in the brush colour.
            if (liveCells.isNotEmpty() && project.selectedTool == "BRUSH") {
                val live = strokeColorFor(ProjectStroke(project.selectedInstrument, "BRUSH", emptyList(), emptyList()))
                liveCells.forEach { cell -> colors[cell] = live }
            }
            colors
        }
        Canvas(
            modifier = modifier
                .semantics { contentDescription = "note grid" }
                .background(Color(0xFFF8F7FC))
                .pointerInput(project.selectedTool, project.selectedInstrument, project.steps, project.rows, brushType) {
                    awaitEachGesture {
                        val tracker = CrossFrameStrokeTracker(project.steps, project.rows)
                        val path = mutableListOf<Offset>()
                        val isBrush = project.selectedTool == "BRUSH"
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            currentOnStrokeStart()
                            fun collect(position: Offset) {
                                if (position.x < 0f || position.y < 0f || position.x >= size.width || position.y >= size.height) return
                                val normX = (position.x / size.width).coerceIn(0f, 1f)
                                val normY = (position.y / size.height).coerceIn(0f, 1f)
                                when (val result = tracker.onPoint(normX, normY, isBrush)) {
                                    CrossFrameStrokeTracker.EventResult.Ignored -> return
                                    is CrossFrameStrokeTracker.EventResult.AdvanceEdge -> {
                                        // Fix 17 v2 (Opsi B): commit old frame (incl. step 23), advance
                                        // to next/new frame, and show connection cell (step 0, line L)
                                        // while keeping gesture alive with relative ratchet xRef.
                                        path.clear()
                                        currentOnCellsPainted(result.committedCells, result.committedPoints)
                                        currentOnAdvanceFrame()
                                        if (brushType == "FREE_DRAW" && isBrush) {
                                            val connY = ((result.connectionLine + 0.5f) / project.rows.toFloat()) * size.height
                                            path.add(Offset(0f, connY.coerceIn(0f, size.height - 1f)))
                                            currentOnLiveChange(emptySet(), path.toList())
                                        } else if (brushType == "BLOCK" && isBrush) {
                                            currentOnLiveChange(tracker.cellsForLive.toSet(), emptyList())
                                        } else {
                                            currentOnLiveChange(emptySet(), emptyList())
                                        }
                                    }
                                    is CrossFrameStrokeTracker.EventResult.Draw -> {
                                        if (brushType == "FREE_DRAW" && isBrush) {
                                            val liveX = if (tracker.relativeMode) {
                                                ((normX - tracker.xRef).coerceIn(0f, 1f) * size.width)
                                            } else {
                                                position.x
                                            }
                                            path.add(Offset(
                                                liveX.coerceIn(0f, size.width - 1f),
                                                position.y.coerceIn(0f, size.height - 1f)
                                            ))
                                            currentOnLiveChange(emptySet(), path.toList())
                                        }
                                        if (brushType == "BLOCK" && isBrush) {
                                            currentOnLiveChange(tracker.cellsForLive.toSet(), emptyList())
                                        }
                                    }
                                }
                            }
                            collect(down.position)
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) collect(change.position)
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            // Finger lifted: commit any cells painted on the active frame.
                            if (tracker.paintedCells.isNotEmpty()) {
                                currentOnCellsPainted(tracker.paintedCells.toSet(), tracker.points.toList())
                            }
                        } finally {
                            currentOnLiveChange(emptySet(), emptyList())
                        }
                    }
                }
        ) {
            val cellWidth: Float = size.width / project.steps.toFloat()
            val cellHeight: Float = size.height / project.rows.toFloat()
            if (playheadStep >= 0f && playheadStep < project.steps.toFloat()) {
                val playX = playheadXForStep(playheadStep, size.width, project.steps)
                drawRect(Color(0x183A9D7A), topLeft = Offset(playX, 0f), size = Size(cellWidth, size.height))
            }
            if (outgoingAlpha > 0f && outgoingCellColors.isNotEmpty()) {
                outgoingCellColors.forEach { (cell, color) ->
                    val row = cell / project.steps
                    val step = cell % project.steps
                    if (row in 0 until project.rows && step in 0 until project.steps) {
                        drawRect(
                            color.copy(alpha = color.alpha * outgoingAlpha),
                            topLeft = Offset(step.toFloat() * cellWidth + 1f, row.toFloat() * cellHeight + 1f),
                            size = Size((cellWidth - 2f).coerceAtLeast(1f), (cellHeight - 2f).coerceAtLeast(1f))
                        )
                    }
                }
            }
            cellColors.forEach { (cell, color) ->
                val row = cell / project.steps
                val step = cell % project.steps
                if (row in 0 until project.rows && step in 0 until project.steps) {
                    val cellAlpha = if (cell in fadeInCells) incomingAlpha * fadeInAlpha else incomingAlpha
                    drawRect(
                        color.copy(alpha = color.alpha * cellAlpha),
                        topLeft = Offset(step.toFloat() * cellWidth + 1f, row.toFloat() * cellHeight + 1f),
                        size = Size((cellWidth - 2f).coerceAtLeast(1f), (cellHeight - 2f).coerceAtLeast(1f))
                    )
                }
            }
            val verticalSpacing = size.width / 68f
            for (line in 0..68) {
                val x = line * verticalSpacing
                val strong = line % 4 == 0
                drawLine(
                    if (strong) Color(0xFFD7D5E1) else Color(0xFFE2E0E9),
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = if (strong) 0.7f else 0.45f
                )
            }
            val horizontalSpacing = size.height / 36f
            for (line in 0..36) {
                val y = line * horizontalSpacing
                drawLine(
                    Color(0xFFE2E0E9),
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 0.617f
                )
            }
            // Live free-draw line (§3.5 Option A): a thin smooth line follows the finger.
            if (livePath.size == 1) {
                drawCircle(Color(0xFF5146A5), radius = 2.5f, center = livePath[0])
            } else if (livePath.size > 1) {
                for (i in 0 until livePath.lastIndex) {
                    drawLine(Color(0xFF5146A5), livePath[i], livePath[i + 1], strokeWidth = 3f, cap = StrokeCap.Round)
                }
            }
        }
    }

    @Composable
    private fun InstrumentPickerDialog(options: List<InstrumentOption>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
        var activeCategory by remember { mutableStateOf<String?>(null) }
        val categoryNames = listOf(
            "Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings",
            "Ensemble", "Brass", "Reed", "Pipe", "Synth lead", "Synth pad",
            "Synth effects", "Ethnic", "Percussive", "Sound effects", "Drum kits"
        )
        val grouped = remember(options) { options.groupBy { it.group } }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 24.dp)
                    .widthIn(max = 312.dp)
                    .fillMaxWidth()
                    .heightIn(max = 672.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFEF7FF),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E0F0)),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 7.dp, vertical = 11.dp)
                ) {
                    val currentCategory = activeCategory
                    if (currentCategory == null) {
                        Text(
                            "Instrument",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D1B20)),
                            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 5.dp)
                        )
                        categoryNames.forEach { group ->
                            val hex = InstrumentPalette.categoryColorHex(group) ?: "#6750A4"
                            val tileColor = Color(InstrumentPalette.parseColor(hex))
                            val iconRes = InstrumentPalette.categoryIconRes(group)
                            val tintColor = InstrumentPalette.glyphColorHex(hex)
                            InstrumentPickerRow(
                                label = group,
                                tileColor = tileColor,
                                iconRes = iconRes,
                                iconTint = tintColor,
                                isSelected = false,
                                onClick = { activeCategory = group }
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeCategory = null }
                                .padding(horizontal = 5.dp)
                        ) {
                            Text(
                                "‹ $currentCategory",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF1D1B20))
                            )
                        }
                        val items = grouped[currentCategory].orEmpty()
                        items.forEach { option ->
                            val hex = InstrumentPalette.colorHex(option.bank, option.program)
                                ?: InstrumentPalette.categoryColorHex(currentCategory)
                                ?: "#6750A4"
                            val tileColor = Color(InstrumentPalette.parseColor(hex))
                            val iconRes = InstrumentPalette.iconRes(option.bank, option.program)
                            val tintColor = InstrumentPalette.glyphColorHex(hex)
                            InstrumentPickerRow(
                                label = option.name,
                                tileColor = tileColor,
                                iconRes = iconRes,
                                iconTint = tintColor,
                                isSelected = option.id == selected,
                                onClick = { onSelect(option.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InstrumentPickerRow(
        label: String,
        tileColor: Color,
        iconRes: Int?,
        iconTint: Color,
        isSelected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) Color(0xFFEEEEEE) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center
            ) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(iconTint, BlendMode.SrcIn)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF332B42)
            )
            Text("›", fontSize = 18.sp, color = Color(0xFF79747E))
        }
    }

    @Composable
    private fun PickerDialog(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            if (title == "Key") {
                Surface(
                    modifier = Modifier.width(180.dp).height(408.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFEF7FF),
                    shadowElevation = 2.dp
                ) {
                    val keyPairs = listOf(
                        "C" to "C#", "D" to "D#", "E" to "F#", "F" to "G#", "G" to "A#", "A" to "B"
                    )
                    val pairValues = musicalKeys.map { it.first to it.second }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "Key",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D1B20)),
                            modifier = Modifier.height(38.dp)
                        )
                        keyPairs.forEach { (left, right) ->
                            Row(modifier = Modifier.height(58.dp)) {
                                listOf(left, right).forEach { label ->
                                    val midi = pairValues.firstOrNull { it.first == label }?.second
                                    val selectedKey = midi != null && midi.toString() == selected
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp, 58.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (selectedKey) Color(0xFFEEEEEE) else Color.Transparent)
                                            .clickable { if (midi != null) onSelect(midi.toString()) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color(0xFF29403B))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.width(232.dp).height(396.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFEF7FF),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E0F0)),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        Text(
                            title,
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D1B20)),
                            modifier = Modifier.height(36.dp).padding(horizontal = 4.dp)
                        )
                        options.forEach { (label, value) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (value == selected) Color(0xFFEEEEEE) else Color.Transparent)
                                    .clickable { onSelect(value) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF332B42))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Soundfont Configuration screen (Figma frame, 400 x 408).
     */
    @Composable
    private fun SoundFontDialog(
        items: List<SoundFontConfigItem>,
        onDismiss: () -> Unit,
        onDelete: (SoundFontConfigItem) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 400.dp).fillMaxWidth().height(408.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFFEF7FF),
                shadowElevation = 6.dp
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(painterResource(R.drawable.ic_close), contentDescription = "Close", modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text("Soundfont Configuration", style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Color(0xFF1D1B20)), modifier = Modifier.padding(end = 40.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Temporary sample library", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF625B71))
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF3EDF7))
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(painterResource(R.drawable.ic_soundfont), null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1D1B20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(".sf2 · Sample", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF625B71))
                                    }
                                    if (!item.isCore) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFFE8E8))
                                                .semantics { contentDescription = "Delete ${item.displayName}" }
                                                .clickable { onDelete(item) },
                                            contentAlignment = Alignment.Center
                                        ) { Image(painterResource(R.drawable.ic_trash_red), null, Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.width(168.dp).height(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEADDFF), contentColor = Color(0xFF4F378B)),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("Back to Settings", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Tempo slider popup (opened by long-pressing the BPM number in the tempo pill).
     * Follows Export/Settings modal style (#FEF7FF, radius 20, shadow 6.dp) and
     * Settings Volume slider style (#6750A4 active, #E8DEF8 inactive, 8x24 thumb).
     */
    @Composable
    private fun TempoPopup(bpm: Int, onBpmChange: (Int) -> Unit, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.width(320.dp).height(216.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFFEF7FF),
                shadowElevation = 6.dp
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) { Image(painterResource(R.drawable.ic_close), contentDescription = "Close", modifier = Modifier.size(20.dp)) }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Tempo",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1D1B20))
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "$bpm",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1D1B20)
                        )
                        Spacer(Modifier.height(16.dp))
                        val trackWidthDp = 272.dp
                        val activeFraction = tempoSliderFractionForBpm(bpm)
                        Box(
                            modifier = Modifier
                                .size(trackWidthDp, 24.dp)
                                .semantics { contentDescription = "Tempo slider" }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        fun update(x: Float) {
                                            onBpmChange(bpmFromTempoSliderFraction(x / size.width.toFloat()))
                                        }
                                        update(down.position.x)
                                        down.consume()
                                        do {
                                            val ev = awaitPointerEvent()
                                            ev.changes.forEach { ch ->
                                                if (ch.pressed) update(ch.position.x)
                                                ch.consume()
                                            }
                                        } while (ev.changes.any { it.pressed })
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFE8DEF8))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(trackWidthDp * activeFraction)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF6750A4))
                            )
                            Box(
                                modifier = Modifier
                                    .offset(x = (trackWidthDp * activeFraction - 4.dp).coerceIn(0.dp, trackWidthDp - 8.dp))
                                    .size(8.dp, 24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF6750A4))
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("60", fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color(0xFF625B71))
                            Text("200", fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color(0xFF625B71))
                        }
                    }
                }
            }
        }
    }

    /**
     * Export popup (Figma "Export" frame, 352 x 224): header "Export", text "Choose a format",
     * two filled purple exports - "Export to WAV" and "Export to MIDI".
     */
    @Composable
    private fun ExportPopup(onWav: () -> Unit, onMidi: () -> Unit, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 352.dp).fillMaxWidth().height(224.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFFEF7FF),
                shadowElevation = 6.dp
            ) {
                val primaryButtonColors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4), contentColor = Color.White)
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) { Image(painterResource(R.drawable.ic_close), contentDescription = "Close", modifier = Modifier.size(20.dp)) }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text("Export", style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Color(0xFF1D1B20)))
                        Spacer(Modifier.height(4.dp))
                        Text("Choose a format", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF625B71))
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onWav,
                            colors = primaryButtonColors,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) { Text("Export to WAV", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onMidi,
                            colors = primaryButtonColors,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) { Text("Export to MIDI", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                    }
                }
            }
        }
    }

    /**
     * Settings popup (Figma "Settings" frame, 320 x 456): holds Import SoundFont,
     * Soundfont Configuration, Brush type (Free draw / Block), Volume slider, and Info.
     */
    @Composable
    private fun SettingsPopup() {
        Dialog(onDismissRequest = { showSettings = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.width(320.dp).height(456.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFFEF7FF),
                shadowElevation = 6.dp
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "Settings",
                        style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Color(0xFF1D1B20)),
                        modifier = Modifier.offset(x = 24.dp, y = 22.dp)
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = 272.dp, y = 16.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { showSettings = false },
                        contentAlignment = Alignment.Center
                    ) { Image(painterResource(R.drawable.ic_close), contentDescription = "Close", modifier = Modifier.size(20.dp)) }

                    Row(
                        modifier = Modifier
                            .offset(x = 16.dp, y = 72.dp)
                            .size(288.dp, 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSettings = false; addSoundFontLauncher.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.ic_import), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Import SoundFont", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6750A4))
                    }

                    Row(
                        modifier = Modifier
                            .offset(x = 16.dp, y = 132.dp)
                            .size(288.dp, 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSettings = false; showSoundFonts = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.ic_configuration), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Soundfont Configuration", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6750A4))
                        Image(painterResource(R.drawable.ic_chevron), null, Modifier.size(16.dp))
                    }

                    Column(modifier = Modifier.offset(x = 24.dp, y = 200.dp).size(272.dp, 80.dp)) {
                        Text("Brush type", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1D1B20))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { brushType = "FREE_DRAW" },
                                modifier = Modifier.size(132.dp, 40.dp),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4)),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (brushType == "FREE_DRAW") Color(0xFF6750A4) else Color(0xFFFEF7FF),
                                    contentColor = if (brushType == "FREE_DRAW") Color.White else Color(0xFF6750A4)
                                )
                            ) { Text("Free draw", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                            Button(
                                onClick = { brushType = "BLOCK" },
                                modifier = Modifier.size(132.dp, 40.dp),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4)),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (brushType == "BLOCK") Color(0xFF6750A4) else Color(0xFFFEF7FF),
                                    contentColor = if (brushType == "BLOCK") Color.White else Color(0xFF6750A4)
                                )
                            ) { Text("Block", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        }
                    }

                    Box(modifier = Modifier.offset(x = 24.dp, y = 296.dp).size(272.dp, 72.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Volume", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1D1B20))
                            Text("${(volume * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color(0xFF1D1B20))
                        }
                        val trackWidthDp = 272.dp
                        val activeFraction = volume.coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .offset(y = 32.dp)
                                .size(272.dp, 24.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        fun update(x: Float) {
                                            val v = (x / size.width.toFloat()).coerceIn(0f, 1f)
                                            volume = v
                                            testTrack?.setVolume(v)
                                            playbackTrack?.setVolume(v)
                                        }
                                        update(down.position.x)
                                        down.consume()
                                        do {
                                            val ev = awaitPointerEvent()
                                            ev.changes.forEach { ch ->
                                                if (ch.pressed) update(ch.position.x)
                                                ch.consume()
                                            }
                                        } while (ev.changes.any { it.pressed })
                                    }
                                }
                        ) {
                            // Inactive track
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFE8DEF8))
                            )
                            // Active track
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(trackWidthDp * activeFraction)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF6750A4))
                            )
                            // Endpoint dot at right
                            Image(
                                painterResource(R.drawable.ic_volume_endpoint),
                                contentDescription = null,
                                modifier = Modifier.align(Alignment.CenterEnd).size(5.dp)
                            )
                            // Thumb 8 x 24
                            Box(
                                modifier = Modifier
                                    .offset(x = (trackWidthDp * activeFraction - 4.dp).coerceIn(0.dp, trackWidthDp - 8.dp))
                                    .size(8.dp, 24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF6750A4))
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .offset(x = 24.dp, y = 380.dp)
                            .size(272.dp, 1.dp)
                            .background(Color(0xFFE0D8E7))
                    )

                    Row(
                        modifier = Modifier
                            .offset(x = 16.dp, y = 388.dp)
                            .size(288.dp, 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSettings = false; showHelp = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.ic_info), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Info", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6750A4))
                    }
                }
            }
        }
    }

    /**
     * Project library screen (Figma frame, 398 x 750).
     */
    @Composable
    private fun LibraryDialog(savedProjects: List<MusicProject>, onDismiss: () -> Unit, onLoad: (MusicProject) -> Unit) {
        val starters = remember { starterProjects() }
        var tab by remember { mutableIntStateOf(0) } // 0 = Starter project, 1 = Saved project
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp).widthIn(max = 398.dp).fillMaxWidth().heightIn(max = 750.dp),
                shape = RoundedCornerShape(36.dp),
                color = Color(0xFFECE6F0),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text(
                        "Project library",
                        style = TextStyle(fontWeight = FontWeight.Normal, fontSize = 30.sp, color = Color(0xFF1D1B20)),
                        modifier = Modifier.height(36.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .border(1.dp, Color(0xFF79747E), RoundedCornerShape(22.dp))
                            .background(Color(0xFFFEF7FF), RoundedCornerShape(22.dp)),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        listOf("Starter project", "Saved project").forEachIndexed { index, label ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (tab == index) Color(0xFFD0BCFF) else Color.Transparent)
                                    .clickable { tab = index }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1D1B20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (tab == 0) "Generated starter projects (${starters.size})" else "Saved projects (${savedProjects.size})",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF49454F)
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (tab == 0) {
                            starters.forEach { item ->
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(45.dp).clickable { onLoad(item) },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text("${item.title} · ${item.bpm} BPM", fontSize = 18.sp, fontWeight = FontWeight.Normal, color = Color(0xFF49454F))
                                }
                            }
                        } else {
                            savedProjects.forEach { item ->
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(45.dp).clickable { onLoad(item) },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Normal, color = Color(0xFF49454F))
                                }
                            }
                            if (savedProjects.isEmpty()) {
                                Text("No saved projects yet.", fontSize = 18.sp, fontWeight = FontWeight.Normal, color = Color(0xFF49454F))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(88.dp, 48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Close", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6750A4)) }
                    }
                }
            }
        }
    }

    private fun addUserSoundFont(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: run {
                status = "Add SoundFont failed: cannot open file"
                return
            }
            val name = uri.lastPathSegment ?: "soundfont.sf2"
            val entry = SoundFontRegistry.addUserSoundFont(this, name, bytes)
            val file = SoundFontRegistry.fileFor(this, entry)
            val presets = Sf2Parser.parsePresets(file)
            val sfid = loadExtraSoundFont(file.absolutePath)
            if (sfid < 0) {
                status = "Added ${entry.displayName} but failed to load in synth"
                return
            }
            loadedSoundFonts[entry.id] = LoadedSoundFont(entry, sfid, presets)
            hiddenSampleNames = SoundFontRegistry.hiddenSampleNames(this)
            soundFontRevision++
            instrumentOptions = InstrumentCatalog.coreOptions() + coreExtraOptions + loadedSoundFonts.values.flatMap { l -> InstrumentCatalog.customOptions(l.entry, l.presets) }
            status = "Loaded ${entry.displayName} (${presets.size} presets)"
        } catch (error: Exception) {
            status = "Add SoundFont failed: ${error.message ?: "error"}"
        }
    }

    private fun deleteSoundFontConfigItem(item: SoundFontConfigItem) {
        if (item.isCore || item.displayName == "GeneralUser GS") return
        val entry = item.entry
        if (entry != null) {
            SoundFontRegistry.removeEntry(this, entry.id)
            loadedSoundFonts.remove(entry.id)
            soundFontRevision++
            instrumentOptions = InstrumentCatalog.coreOptions() + coreExtraOptions + loadedSoundFonts.values.flatMap { l -> InstrumentCatalog.customOptions(l.entry, l.presets) }
            val currentOption = resolveOption(project.selectedInstrument)
            if (currentOption == null || currentOption.soundFontId == entry.id) {
                project = project.copy(selectedInstrument = "SF_SOFT_KEY", updatedAt = System.currentTimeMillis())
            }
            status = "Removed ${item.displayName}; active SoundFont: GeneralUser GS"
        } else {
            SoundFontRegistry.hideSampleName(this, item.displayName)
            hiddenSampleNames = SoundFontRegistry.hiddenSampleNames(this)
            status = "Removed ${item.displayName}"
        }
    }

    private fun verifySoundFont(entry: SoundFontEntry) {
        val loaded = loadedSoundFonts[entry.id] ?: run {
            status = "SoundFont not loaded: ${entry.displayName}"
            return
        }
        status = "Verifying ${entry.displayName}..."
        thread(name = "verify-sf") {
            val presets = loaded.presets
            var ok = 0
            var silent = 0
            var failed = 0
            val sampleRate = 44100
            val seconds = 0.25f
            val frames = (sampleRate * seconds).toInt()
            val out = ShortArray(frames * 2)
            for (p in presets) {
                try {
                    val count = renderChord(intArrayOf(60), seconds, loaded.sfid, p.bank, p.program, 1.0f, out)
                    if (count <= 0) {
                        failed++
                    } else {
                        var peak = 0
                        for (i in 0 until count) {
                            val v = abs(out[i].toInt())
                            if (v > peak) peak = v
                        }
                        if (peak > 0) ok++ else silent++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                status = "Verify ${entry.displayName}: ${presets.size} presets, nonzero=$ok, silent=$silent, failed=$failed"
            }
        }
    }

    private fun changeProject(updated: MusicProject) {
        // Project-wide settings (BPM, key, scale, instrument, tool, ...). Per the Run 7
        // lock, Undo/Redo acts on the SELECTED FRAME's content only, so setting changes
        // are not undo entries (NOT SPECIFIED by the User; simplest consistent choice).
        if (project != updated) {
            livePlaybackBpm = updated.bpm
            project = updated.copy(updatedAt = System.currentTimeMillis())
        }
    }

    /** Applies an edit to the selected frame's content, recording one undo step for it. */
    private fun editSelectedFrame(updated: MusicProject) {
        val index = project.selectedFrame
        val before = project.selectedStrokes
        if (updated.selectedStrokes == before && updated.selectedFrame == index) {
            project = updated.copy(updatedAt = System.currentTimeMillis())
            return
        }
        frameHistory.recordEdit(index, before)
        project = updated.copy(updatedAt = System.currentTimeMillis())
    }

    private fun loadProject(loaded: MusicProject) {
        livePlaybackBpm = loaded.bpm
        project = loaded
        // History is memory-only: after loading, every frame starts empty (§3.1.5f).
        frameHistory.reset(loaded.frames.size)
    }

    private fun editCells(cells: Set<Int>, points: List<Pair<Float, Float>>) {
        if (cells.isEmpty()) return
        val before = project.selectedStrokes
        val nextStrokes = if (project.selectedTool == "ERASER") {
            before.mapNotNull { stroke ->
                val remaining = stroke.cells.filterNot(cells::contains)
                if (remaining.isEmpty()) null else stroke.copy(cells = remaining)
            }
        } else {
            before + ProjectStroke(project.selectedInstrument, "BRUSH", points, cells.toList())
        }
        if (nextStrokes == before) return
        editSelectedFrame(project.withFrameStrokes(project.selectedFrame, nextStrokes))
    }

    private fun undo() {
        val index = project.selectedFrame
        val restored = frameHistory.undo(index, project.selectedStrokes)
        if (restored == null) { status = "Undo history is empty"; return }
        project = project.withFrameStrokes(index, restored).copy(updatedAt = System.currentTimeMillis())
        status = "Undo"
    }

    private fun redo() {
        val index = project.selectedFrame
        val restored = frameHistory.redo(index, project.selectedStrokes)
        if (restored == null) { status = "Redo history is empty"; return }
        project = project.withFrameStrokes(index, restored).copy(updatedAt = System.currentTimeMillis())
        status = "Redo"
    }

    private fun clearProject() {
        // Clear affects ONLY the selected frame (§3.1.5a) and is itself one undoable
        // entry in that frame's history (§3.1.5d).
        val before = project.selectedStrokes
        if (before.isEmpty()) return
        frameHistory.recordEdit(project.selectedFrame, before)
        project = project.withFrameStrokes(project.selectedFrame, emptyList())
            .copy(updatedAt = System.currentTimeMillis())
        status = "Cleared frame ${project.selectedFrame + 1}"
    }

    /** + button (§3.3.3): adds a NEW empty frame and selects it. NOT an undo entry. */
    private fun addFrame() {
        frameHistory.addFrame()
        project = project.copy(
            frames = project.frames + ProjectFrame(),
            selectedFrame = project.frames.size,
            updatedAt = System.currentTimeMillis()
        )
        status = "Frame ${project.frames.size} added"
        // Adding during playback restarts from the same playing position with the fresh
        // frame list (NOT SPECIFIED by the User; simplest consistent behaviour).
        if (isPlaying) {
            val from = playbackFrame.coerceIn(0, project.frames.lastIndex)
            startPlayback(fromFrame = from)
        }
    }

    /** Fix 16A: auto-advance to next frame (or append new empty frame) when drawing reaches step 23. */
    private fun advanceFrameOnEdge() {
        val (nextProject, appended) = project.advanceOrAppendEmptyFrame()
        if (appended) {
            frameHistory.ensureSize(project.frames.size)
            frameHistory.addFrame()
        }
        project = nextProject.copy(updatedAt = System.currentTimeMillis())
        selectedRow = selectedRow.coerceIn(0, project.rows - 1)
        status = if (appended) {
            "Frame ${project.frames.size} added"
        } else {
            "Frame ${project.selectedFrame + 1}/${project.frames.size}"
        }
    }

    /** Fix 16B: copies all grid cells of [index] into the in-memory clipboard. */
    private fun copyFrame(index: Int) {
        if (index !in project.frames.indices) return
        copiedFrame = project.frames[index].deepCopy()
        status = "Frame copied"
        Toast.makeText(this, "Frame copied", Toast.LENGTH_SHORT).show()
    }

    /** Fix 16B: pastes the copied frame at [insertIndex] (before or after) and selects it. */
    private fun pasteFrame(insertIndex: Int) {
        val clip = copiedFrame ?: return
        frameHistory.ensureSize(project.frames.size)
        val pos = insertIndex.coerceIn(0, project.frames.size)
        frameHistory.insertFrame(pos)
        project = project.insertFrameAt(pos, clip.deepCopy()).copy(updatedAt = System.currentTimeMillis())
        selectedRow = selectedRow.coerceIn(0, project.rows - 1)
        status = "Frame pasted"
    }

    /** Fix 16B: inserts a new empty frame at [insertIndex] (before or after) and selects it. */
    private fun addFrameAt(insertIndex: Int) {
        frameHistory.ensureSize(project.frames.size)
        val pos = insertIndex.coerceIn(0, project.frames.size)
        frameHistory.insertFrame(pos)
        project = project.insertFrameAt(pos, ProjectFrame()).copy(updatedAt = System.currentTimeMillis())
        selectedRow = selectedRow.coerceIn(0, project.rows - 1)
        status = "Frame ${pos + 1} added"
    }

    /** Tap a thumbnail (§3.3.5): select that frame; while playing, jump playback to it. */
    private fun selectFrame(index: Int) {
        if (index !in project.frames.indices) return
        if (index == project.selectedFrame && !isPlaying) return
        project = project.copy(selectedFrame = index, updatedAt = System.currentTimeMillis())
        selectedRow = selectedRow.coerceIn(0, project.rows - 1)
        if (isPlaying && index != playbackFrame) {
            // §3.2.4: jump IMMEDIATELY to step 0 of the tapped frame, keep auto-advancing.
            playbackJumpFrame = index
            status = "Playing frame ${index + 1}/${project.frames.size}"
        } else if (!isPlaying) {
            status = "Frame ${index + 1}/${project.frames.size}"
        }
    }

    /**
     * Long-press "Delete frame" confirmed (§3.3.8): removes the frame, discards only its
     * history, renumbers 1..N, and moves the selection to the previous frame when the
     * deleted frame was the selected one (frame 1 if the first frame was deleted); a
     * selection before the deleted frame keeps pointing at the same frame. Not undoable.
     * The last remaining frame is never deleted.
     */
    private fun deleteFrame(index: Int) {
        if (index !in project.frames.indices || project.frames.size <= 1) return
        val nextFrames = project.frames.filterIndexed { i, _ -> i != index }
        val nextSelection = when {
            index < project.selectedFrame -> project.selectedFrame - 1
            index == project.selectedFrame -> (index - 1).coerceAtLeast(0)
            else -> project.selectedFrame
        }.coerceIn(0, nextFrames.lastIndex)
        frameHistory.deleteFrame(index)
        project = project.copy(
            frames = nextFrames,
            selectedFrame = nextSelection,
            updatedAt = System.currentTimeMillis()
        )
        status = "Frame deleted, ${nextFrames.size} frame(s) left"
        // §3.3.8d: if playback was running it continues from step 0 of the new selected frame.
        if (isPlaying) {
            startPlayback(fromFrame = project.selectedFrame)
        }
    }

    private fun saveProject() {
        val directory = File(filesDir, "projects")
        if (!directory.exists() && !directory.mkdirs()) { status = "Error: Could not create project storage"; return }
        val safeId = project.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "project-${System.currentTimeMillis()}" }
        try {
            File(directory, "$safeId.json").writeText(project.toJson())
            status = "Saved privately in app storage"
        } catch (error: IOException) {
            status = "Save failed: ${error.message ?: "storage error"}"
        }
    }

    private fun savedProjects(): List<MusicProject> {
        val directory = File(filesDir, "projects")
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            try { MusicProject.fromJson(file.readText()) } catch (_: Exception) { null }
        }
    }

    /**
     * Plays the frame sequence (§3.2): starts at [fromFrame] (default: the selected
     * frame), auto-advances frame by frame, and with Loop ON wraps to frame 1 after the
     * last frame while Loop OFF stops there ("Playback stopped"). A tap on another
     * thumbnail while playing sets [playbackJumpFrame] and the thread jumps to step 0 of
     * that frame immediately (§3.2.4).
     */
    private fun startPlayback(fromFrame: Int = project.selectedFrame) {
        stopPlayback()
        if (playbackThread?.isAlive == true) { status = "Playback is still stopping"; return }
        val snapshot = project
        livePlaybackBpm = snapshot.bpm
        loopPlaybackEnabled = loopPlayback
        val generation = ++playbackGeneration
        val sampleRate = 44100
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val initialFramesPerStep = framesPerStepForBpm(sampleRate, snapshot.bpm)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, initialFramesPerStep * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(volume)
        playbackTrack = track
        isPlaying = true
        playbackJumpFrame = -1
        var frameIndex = fromFrame.coerceIn(0, snapshot.frames.lastIndex)
        playbackFrame = frameIndex
        status = "Playing frame ${frameIndex + 1}/${snapshot.frames.size} at ${snapshot.bpm} BPM"
        track.play()
        playbackThread = thread(name = "opendrawmusic-sequencer") {
            var failure: String? = null
            resetPlaybackSynth()
            val channelMap = linkedMapOf<String, Int>()
            var heldNotes = emptySet<SequencerNoteKey>()
            try {
                var step = 0
                while (playbackGeneration == generation) {
                    val jump = playbackJumpFrame
                    if (jump >= 0) {
                        playbackJumpFrame = -1
                        frameIndex = jump.coerceIn(0, snapshot.frames.lastIndex)
                        step = 0
                        val activeBpm = livePlaybackBpm
                        runOnUiThread {
                            if (playbackGeneration == generation) {
                                playbackFrame = frameIndex
                                status = "Playing frame ${frameIndex + 1}/${snapshot.frames.size} at $activeBpm BPM"
                            }
                        }
                    }
                    if (step >= snapshot.steps) {
                        step = 0
                        frameIndex++
                        if (frameIndex >= snapshot.frames.size) {
                            // Loop applies to the WHOLE sequence (§3.2.3).
                            if (loopPlaybackEnabled) frameIndex = 0 else break
                        }
                        val shownFrame = frameIndex
                        val activeBpm = livePlaybackBpm
                        runOnUiThread {
                            if (playbackGeneration == generation) {
                                playbackFrame = shownFrame
                                status = "Playing frame ${shownFrame + 1}/${snapshot.frames.size} at $activeBpm BPM"
                            }
                        }
                    }
                    val stepFrames = framesPerStepForBpm(sampleRate, livePlaybackBpm)
                    val (pcm, nextHeld) = renderSeamlessStep(
                        snapshot = snapshot,
                        strokes = snapshot.frames[frameIndex].strokes,
                        step = step,
                        frames = stepFrames,
                        currentlyHeld = heldNotes,
                        channelMap = channelMap
                    )
                    heldNotes = nextHeld
                    var written = 0
                    while (written < pcm.size && playbackGeneration == generation) {
                        val count = track.write(pcm, written, pcm.size - written)
                        if (count <= 0) { failure = "Audio output failed ($count)"; break }
                        written += count
                    }
                    if (failure != null) break
                    val shownStep = step
                    playStepIndex = shownStep
                    playStepStartNs = System.nanoTime()
                    playStepDurationNs = stepFrames.toLong() * 1_000_000_000L / sampleRate.toLong()
                    runOnUiThread { if (playbackGeneration == generation) playColumn = shownStep }
                    step++
                }
            } catch (error: IllegalStateException) {
                failure = "Playback failed: ${error.message ?: "synthesizer error"}"
            } finally {
                resetPlaybackSynth()
                track.stop()
                track.release()
                runOnUiThread {
                    if (playbackTrack === track) {
                        playbackTrack = null
                        isPlaying = false
                        playColumn = -1
                        playStepIndex = -1
                        playheadStepPosition = -1f
                        playbackFrame = -1
                        status = failure ?: "Playback stopped"
                    }
                }
            }
        }
    }

    private fun stopPlayback() {
        if (isPlaying) {
            playbackGeneration++
            isPlaying = false
            status = "Stopping playback"
        }
        val activeThread = playbackThread
        if (activeThread != null && activeThread !== Thread.currentThread() && activeThread.isAlive) {
            activeThread.join(1000)
        }
        if (activeThread?.isAlive != true) {
            playbackThread = null
            playColumn = -1
            playStepIndex = -1
            playheadStepPosition = -1f
            playbackFrame = -1
        }
    }

    private fun channelForInstrument(instrumentId: String, channelMap: MutableMap<String, Int>): Int {
        return channelMap.getOrPut(instrumentId) { channelMap.size % 16 }
    }

    private fun renderSeamlessStep(
        snapshot: MusicProject,
        strokes: List<ProjectStroke>,
        step: Int,
        frames: Int,
        currentlyHeld: Set<SequencerNoteKey>,
        channelMap: MutableMap<String, Int>,
        gain: Float = 1f
    ): Pair<ShortArray, Set<SequencerNoteKey>> {
        val activeNow = SequencerScheduler.activeNotesAtStep(snapshot, strokes, step)
        val diff = SequencerScheduler.diffStep(currentlyHeld, activeNow)

        val offCh = IntArray(diff.noteOffs.size)
        val offMidi = IntArray(diff.noteOffs.size)
        diff.noteOffs.forEachIndexed { idx, key ->
            offCh[idx] = channelForInstrument(key.instrumentId, channelMap)
            offMidi[idx] = key.midi
        }

        val onChList = mutableListOf<Int>()
        val onMidiList = mutableListOf<Int>()
        val onSfidList = mutableListOf<Int>()
        val onBankList = mutableListOf<Int>()
        val onProgList = mutableListOf<Int>()
        diff.noteOns.forEach { key ->
            val option = resolveOption(key.instrumentId) ?: return@forEach
            onChList.add(channelForInstrument(key.instrumentId, channelMap))
            onMidiList.add(key.midi)
            onSfidList.add(sfidFor(option))
            onBankList.add(option.bank)
            onProgList.add(option.program)
        }

        val output = ShortArray(frames * 2)
        val count = renderSequencerStep(
            noteOffChannels = offCh,
            noteOffMidis = offMidi,
            noteOnChannels = onChList.toIntArray(),
            noteOnMidis = onMidiList.toIntArray(),
            noteOnSfids = onSfidList.toIntArray(),
            noteOnBanks = onBankList.toIntArray(),
            noteOnPrograms = onProgList.toIntArray(),
            frames = frames,
            gain = gain,
            output = output
        )
        if (count <= 0) throw IllegalStateException("renderSequencerStep failed")
        return output to diff.nextHeld
    }

    private fun renderStep(snapshot: MusicProject, strokes: List<ProjectStroke>, step: Int, frames: Int, sampleRate: Int, gain: Float = 1f): ShortArray {
        val output = ShortArray(frames * 2)
        val notesByInstrument = linkedMapOf<InstrumentOption, MutableList<Int>>()
        strokes.forEach { stroke ->
            val option = resolveOption(stroke.instrument) ?: return@forEach
            stroke.cells.forEach { cell ->
                if (cell % snapshot.steps == step) {
                    val lineIndex = cell / snapshot.steps
                    val note = midiForLine(lineIndex + 1, snapshot.rootMidi, snapshot.scale)
                    if (note != null) notesByInstrument.getOrPut(option) { mutableListOf() }.add(note)
                }
            }
        }
        val seconds = frames.toFloat() / sampleRate.toFloat()
        notesByInstrument.forEach { (option, notes) ->
            val rendered = ShortArray(frames * 2)
            val sfid = sfidFor(option)
            val count = renderChord(notes.distinct().toIntArray(), seconds, sfid, option.bank, option.program, gain, rendered)
            if (count <= 0) throw IllegalStateException("render failed for ${option.name}")
            for (i in 0 until minOf(count, output.size)) {
                output[i] = (output[i].toInt() + rendered[i].toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return output
    }

    private fun playTestNote() {
        stopPlayback()
        if (playbackThread?.isAlive == true) { status = "Playback is still stopping"; return }
        val option = resolveOption(project.selectedInstrument) ?: resolveOption("SF_SOFT_KEY")
        status = "Rendering test note"
        thread(name = "opendrawmusic-test-note") {
            val samples = ShortArray(44100 * 2)
            val sfid = option?.let { sfidFor(it) } ?: -1
            val bank = option?.bank ?: 0
            val program = option?.program ?: 4
            val count = renderChord(intArrayOf(60), 1.0f, sfid, bank, program, 1.0f, samples)
            if (count <= 0) { runOnUiThread { status = "Error: test note render failed" }; return@thread }
            val track = createAudioTrack(count * 2)
            track.setVolume(volume)
            testTrack = track
            track.play()
            val written = track.write(samples, 0, count)
            track.stop()
            track.release()
            testTrack = null
            runOnUiThread { status = if (written == count) "Test note complete" else "Test note audio write failed ($written)" }
        }
    }

    private fun exportWav() {
        stopPlayback()
        if (playbackThread?.isAlive == true) { status = "Playback is still stopping"; return }
        val snapshot = project
        val exportVolume = volume
        status = "Rendering WAV"
        thread(name = "opendrawmusic-wav-export") {
            val sampleRate = 44100
            val framesPerStep = framesPerStepForBpm(sampleRate, snapshot.bpm)
            // Full-song export (§3.2.5): every frame in order, single pass, Loop ignored.
            val totalSteps = snapshot.steps * snapshot.frames.size
            val pcm = ShortArray(framesPerStep * totalSteps * 2)
            try {
                resetPlaybackSynth()
                val channelMap = linkedMapOf<String, Int>()
                var heldNotes = emptySet<SequencerNoteKey>()
                snapshot.frames.forEachIndexed { frameIndex, frame ->
                    for (step in 0 until snapshot.steps) {
                        val (rendered, nextHeld) = renderSeamlessStep(
                            snapshot = snapshot,
                            strokes = frame.strokes,
                            step = step,
                            frames = framesPerStep,
                            currentlyHeld = heldNotes,
                            channelMap = channelMap,
                            gain = exportVolume
                        )
                        heldNotes = nextHeld
                        rendered.copyInto(pcm, (frameIndex * snapshot.steps + step) * framesPerStep * 2)
                    }
                }
            } catch (error: IllegalStateException) {
                runOnUiThread { status = "WAV export failed: ${error.message ?: "synthesizer error"}" }
                return@thread
            } finally {
                resetPlaybackSynth()
            }
            val wav = WavExporter.export(pcm, sampleRate)
            runOnUiThread {
                pendingExport = wav
                status = "Choose a location for the WAV file ($totalSteps steps, ${snapshot.frames.size} frames)"
                createExport.launch("${snapshot.title.sanitizedFileName()}.wav")
            }
        }
    }

    private fun exportMidi() {
        try {
            val bytes = MidiExporter.export(project) { id -> resolveBankProgram(id) }
            pendingExport = bytes
            status = "Choose a location for the MIDI file (${project.frames.size} frames, ${project.steps * project.frames.size} steps)"
            createExport.launch("${project.title.sanitizedFileName()}.mid")
        } catch (error: IllegalArgumentException) {
            status = "MIDI export failed: ${error.message ?: "unsupported project"}"
        }
    }

    private fun writeExport(uri: Uri) {
        val bytes = pendingExport ?: run { status = "Export failed: no pending output"; return }
        try {
            val output = contentResolver.openOutputStream(uri) ?: run { status = "Export failed: destination could not be opened"; return }
            output.use { it.write(bytes) }
            status = "Export complete (${bytes.size} bytes)"
        } catch (error: IOException) {
            status = "Export failed: ${error.message ?: "I/O error"}"
        } finally {
            pendingExport = null
        }
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun importProject(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: run { status = "Import failed: file could not be opened"; return }
            val imported = MusicProject.fromJson(json)
            project = imported.copy(id = "import-${System.currentTimeMillis()}")
            frameHistory.reset(project.frames.size)
            status = "Imported ${project.title}"
        } catch (error: IOException) {
            status = "Import failed: ${error.message ?: "I/O error"}"
        } catch (error: JSONException) {
            status = "Import failed: invalid project JSON (${error.message ?: "parse error"})"
        }
    }

    private fun copyAsset(name: String, force: Boolean = false): String {
        val target = File(filesDir, name)
        target.parentFile?.mkdirs()
        if (force || !target.exists()) {
            assets.open(name).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
        return target.absolutePath
    }

    private fun String.sanitizedFileName(): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifBlank { "project" }
}
