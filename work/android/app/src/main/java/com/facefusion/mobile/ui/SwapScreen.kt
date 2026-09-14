package com.facefusion.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.facefusion.mobile.displayThumb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.facefusion.mobile.FaceDetectorCard
import com.facefusion.mobile.FaceMaskerCard
import com.facefusion.mobile.FaceSwapperCard
import com.facefusion.mobile.BatchItem
import com.facefusion.mobile.BatchState
import com.facefusion.mobile.ModelDownload
import com.facefusion.mobile.OptionSegments
import com.facefusion.mobile.OptionSlider
import com.facefusion.mobile.OptionSteps
import com.facefusion.mobile.SwapOptions
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.facefusion.mobile.R
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Face

/**
 * Everything the two preview panes need to draw themselves.
 *
 * @Immutable: the fields are all `val` and are treated as never-mutated in place -- a new
 * fact arrives as a NEW instance, which is how the Activity already builds this. Without
 * the annotation Compose cannot know that, because `Bitmap` and `FloatArray` are unstable
 * types, and an unstable argument can never be skipped: every pass of the Activity's scope
 * (a log line, a progress tick, a preview frame) re-ran the whole screen -- scroll state,
 * every card, both panes. Kept from #3, which is where the measurement was done.
 */
@Immutable
data class PreviewUi(
    val original: Bitmap? = null,
    val swapped: Bitmap? = null,
    val timeLabel: String = "",
    /** Whether the pipeline is loaded. Until it is, the swapped pane cannot draw anything. */
    val warm: Boolean = false,
    val busy: Boolean = false,
    /** "No face detected", or an error. Shown in place of the image. */
    val note: String? = null,
    /**
     * What the detector found in [original], five floats per face, in the ORIGINAL's own
     * pixel coordinates. Null when the overlay is off or nothing has been asked yet.
     */
    val faceBoxes: FloatArray? = null,
    /** The face picked as the reference, if any -- drawn solid while the rest dim. */
    val referenceBox: FloatArray? = null,
)

/**
 * Which trim handle the user is dragging.
 *
 * The previews follow the handle under the finger. Before this, both panes always showed
 * the START frame, so dragging the end handle appeared to do nothing at all -- the
 * "slider doesn't move the preview" report was this, not a stale pane.
 */
enum class TrimEdge { Start, End }

/**
 * Progress of an actual swap run.
 *
 * @Immutable for the same reason as [PreviewUi], and it matters more here: this one is
 * rebuilt on every progress tick. Paired with the 10 Hz cap in `MainActivity`, which is
 * the other half -- the annotation lets Compose skip the screen, the cap stops asking it
 * to 25 times a second.
 */
@Immutable
data class RunUi(
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val progress: Float = 0f,
    val framesDone: Int = 0,
    val framesTotal: Int = 0,
    val elapsedS: Double = 0.0,
    /** True while a batch is between START and its rows being reset for the next one. */
    val canCancel: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapScreen(
    sourceThumb: Bitmap?,
    /** Every source face, in native slot order. Drawn by the shared [SourceRow]. */
    sourceThumbs: List<Bitmap> = emptyList(),
    activeSource: Int = 0,
    onSelectSource: (Int) -> Unit = {},
    /**
     * ASSIGN PER PERSON, the Swap screen's own. Live decides by tracking a person through
     * a sequence of frames; there is no sequence here, so a tap stores the person's
     * IDENTITY and native matches it on every frame of the run.
     */
    assignMode: Boolean = false,
    /** The people detected in the frame on screen, in the same order as `faceBoxes`. */
    personThumbs: List<Bitmap> = emptyList(),
    selectedPerson: Int = -1,
    /** person index -> source slot, or -1 for "keeps their own face". */
    personAssignments: Map<Int, Int> = emptyMap(),
    keepOriginalBrush: Boolean = false,
    onKeepOriginal: () -> Unit = {},
    onToggleAssignMode: () -> Unit = {},
    onSelectPerson: (Int) -> Unit = {},
    onClearAssignments: () -> Unit = {},
    hasSource: Boolean,
    hasTarget: Boolean,
    /**
     * The target is a STILL.
     *
     * There is nothing to run: the swapped pane already holds the finished image, at full
     * resolution and through the same pipeline a run would use. So the Swap button is not
     * drawn at all -- pressing it would spend seconds reloading the models to produce a
     * second copy of the picture already on screen.
     */
    imageTarget: Boolean,
    durationMs: Long,
    trimStartMs: Float,
    trimEndMs: Float,
    onTrimChange: (Float, Float, TrimEdge) -> Unit,
    /** width / height of the target. Below 1 the panes go side by side. */
    targetAspect: Float,
    /** The target video's own rate, and the cap on what can be chosen. */
    inputFps: Int,
    /** The target's own pixel size, upright. The cap on what output sizes are offered. */
    targetW: Int,
    targetH: Int,
    fmt: (Float) -> String,
    preview: PreviewUi,
    run: RunUi,
    status: String,
    /**
     * Whether [status] describes a FAILURE, decided by the Activity rather than re-derived
     * here.
     *
     * This used to be `status.startsWith("Failed")` -- a test on a string that is shown to
     * the user. Translating the status would have silently removed the bug-report button in
     * every language but English, which is precisely the language whose users are least
     * likely to need it.
     */
    statusIsError: Boolean,
    log: String,
    opts: SwapOptions,
    onOptsChange: (SwapOptions) -> Unit,
    hasInswapper: Boolean,
    hasEnhancer: Boolean,
    hasLipSyncer: Boolean,
    /**
     * A processor whose model is not on the device was tapped.
     *
     * Two arguments: the chip's LABEL, which is localized and only ever shown, and the
     * MODEL name the downloader knows it by ("gpen", "edtalk"). ⚠ It used to pass the
     * label alone, which left the prompt's Continue with nothing to ask for but "the
     * missing set" -- and the missing set excludes exactly these two models by name.
     */
    onRequestModel: (label: String, model: String) -> Unit,
    /** Whether the detector's boxes are drawn over the ORIGINAL pane. */
    showFaceBoxes: Boolean,
    /** Turn the face overlay on or off. Detection runs only while it is on. */
    onToggleFaceBoxes: () -> Unit,
    /**
     * A face in the ORIGINAL pane was tapped, in that image's own pixel coordinates:
     * upstream's `face_selector_mode = reference`. Tapping the chosen one again clears it.
     */
    onPickFace: (Float, Float) -> Unit,
    /**
     * The run queue, item one being the VISIBLE target -- roadmap 14.
     *
     * Size 1 is the ordinary single-clip screen and draws no queue at all: one source,
     * many targets is a mode you enter by picking several files, not by finding a switch.
     */
    batch: List<BatchItem>,
    /** Drop a queued clip. Only offered while it is still waiting. */
    onRemoveFromBatch: (Int) -> Unit,
    /** Clear the WHOLE batch queue -- the trash at the card's bottom-right corner. */
    onClearBatch: () -> Unit,
    /** Add more clips to the queue, leaving the visible target alone. */
    onAddToBatch: () -> Unit,
    /**
     * Play the target through the pipeline, live -- see [LivePlayerOverlay].
     *
     * Always passed, reachable only on dev: the button below is the ONE place the
     * feature is switched on, and `MainActivity.startPlayer` checks the same flag
     * again rather than trusting that a button nobody drew cannot be pressed.
     */
    onLivePlay: () -> Unit,
    /** Show a finished batch clip in the output pane, by its index in [batch]. */
    onOpenBatchOutput: (Int) -> Unit,
    /**
     * The output on screen is already in the gallery, put there by the batch's auto-save.
     *
     * Hides the Save button rather than disabling it: a greyed control still asks the user
     * to work out why, and the answer -- "because it is already saved" -- is better said by
     * the label that replaces it.
     */
    outputAutoSaved: Boolean,
    /** Copy every finished batch clip straight to the gallery. */
    batchAutoSave: Boolean,
    onBatchAutoSave: (Boolean) -> Unit,
    openCard: String,
    onToggleCard: (String) -> Unit,
    /** There is something to save: a finished video, or a swapped still on the pane. */
    hasOutput: Boolean,
    /** The finished video, for the output pane. Null when the target was a still. */
    outputFile: File?,
    /**
     * The OUTPUT's own width/height (rotation-corrected), 0 until known. The result pane
     * sizes itself from these when the target pane has no frame to read -- a batch run
     * standing on an empty pane would otherwise fall back to a landscape 16:9 box and
     * letterbox a portrait clip into an unreadable sliver: the "cannot tell portrait from
     * landscape" report.
     */
    outputW: Int,
    outputH: Int,
    /** True when the run was cancelled, so the output is only as long as it got. */
    outputPartial: Boolean,
    onSaveFrame: (Int) -> Unit,
    saved: Boolean,
    savedPath: String?,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    /** The lip syncer's driving audio -- see [onPickVoice]'s doc, and `VideoSwapper.voicePath`. */
    hasVoice: Boolean,
    voiceName: String?,
    /** The loaded voice's full length, ms. 0 until a file is loaded. */
    voiceDurationMs: Long,
    /**
     * The part of the voice that drives the lips, ms -- the audio equivalent of
     * [trimStartMs]/[trimEndMs] on the target. Only this range is decoded for the mouth
     * and copied into the output's audio track.
     */
    voiceTrimStartMs: Float,
    voiceTrimEndMs: Float,
    onVoiceTrimChange: (Float, Float) -> Unit,
    /** Playback of the loaded voice: the playhead position and whether it is running. */
    voicePosMs: Float,
    voicePlaying: Boolean,
    onVoicePlayPause: () -> Unit,
    onVoiceSeek: (Float) -> Unit,
    /**
     * Pick the file that DRIVES the mouth -- deliberately not the target. Only shown once
     * Lip Sync is on, because syncing a clip to the audio it already has has nothing to
     * fix: this is upstream's actual use for the feature (dubbing a different voice onto
     * the target), not a way to verify the target's own performance.
     */
    onPickVoice: () -> Unit,
    onClearVoice: () -> Unit,
    /** Microphone capture of the driving voice, in-app. */
    recordingVoice: Boolean,
    onToggleRecordVoice: () -> Unit,
    /**
     * Save the frame currently shown in the SWAPPED pane, as an image.
     *
     * Distinct from [onSaveFrame], which takes a position and reads it out of the
     * FINISHED video. This one needs no argument because the frame is already on
     * screen, and it works before any run has happened.
     */
    onSavePreviewFrame: () -> Unit,
    onClearSource: () -> Unit,
    /** Shoot the source face with the camera. Stills only -- a source is an identity. */
    onCaptureSource: () -> Unit,
    /** Take a still / record a clip with the system camera, as the target. */
    onCapturePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onClearTarget: () -> Unit,
    /** Delete the rendered file. Confirms first -- see the dialog at the end of this file. */
    onDeleteOutput: () -> Unit,
    onSwap: () -> Unit,
    onCancel: () -> Unit,
    modelsMissing: Boolean,
    onDownload: () -> Unit,
    onShareLog: () -> Unit,
    onSave: () -> Unit,
    /** One tap saves every finished clip the gallery does not already have. */
    onSaveAll: () -> Unit = {},
    /** True while the save-all loop is writing -- gates the save-all entry. */
    savingAll: Boolean = false,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = !run.busy && !run.preparing

    // The result pane sizes itself from the TARGET's own dimensions (60%, see below), so
    // it needs the screen width to clamp against; `screenH` still feeds the fallback
    // height and the video output pane.
    val screenH = LocalConfiguration.current.screenHeightDp
    val screenW = LocalConfiguration.current.screenWidthDp
    // Side-by-side panes are half as wide, so they can afford to be taller: the pair costs
    // ONE pane's height instead of two, which is the whole reason portrait gets this layout.
    //
    // ⚠ Budgets are TIGHT on purpose. Every dp the panes take is a dp the Swap button is
    // pushed below the fold; the report "the button vanished after picking a target" was
    // this arithmetic, not a rendering bug. The processor and trim cards no longer stand on
    // the page at all (the stages moved under the target tile's gear), so the panes are the
    // only large blocks on a fresh target -- and even so their combined height has to leave
    // room for the button.
    val paneHeight = if (targetAspect < 1f) (screenH - 480).coerceIn(140, 300).dp
                     else ((screenH - 560) / 2).coerceIn(100, 240).dp

    // ONE instance for every pane, which is what makes them zoom together (item 4).
    val zoom = remember { ZoomState() }

    // Which processor's settings sheet is open: "swapper", "enhancer", "lipsync", or null.
    //
    // Local and saveable rather than hoisted into MainActivity like [openCard], because it
    // is transient view state with no bearing on a run -- MainActivity holds what the swap
    // needs to know, and which sheet is showing is not that. rememberSaveable so a rotation
    // does not close it mid-adjustment.
    var settingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteOutput by rememberSaveable { mutableStateOf(false) }
    var sourcePickerExpanded by rememberSaveable { mutableStateOf(false) }
    var inputRowW by remember { mutableIntStateOf(0) }
    // Output settings live on ONE foldable card, default CLOSED: "Clip" (the trim) is the
    // first item, followed by output size and frame rate -- per-run tuning that should not
    // push the Swap button off the first screen.
    var trimExpanded by rememberSaveable { mutableStateOf(false) }
    // Trim-scrub focus: while a finger presses the clip RangeSlider (tap or drag),
    // the output-settings dropdown fades its chrome and every row except the slider,
    // which is redrawn as a bare 3 dp line at 50% -- the preview under the card is
    // what the range choice is judged against, and the card used to cover it.
    // Driven three ways on purpose: onValueChange/onValueChangeFinished are the
    // guaranteed signals (any real tap or drag runs through them); the interaction
    // source is only a backstop for press-and-hold without movement, whose emission
    // is an implementation detail of the thumb rendering.
    var trimScrubbing by remember { mutableStateOf(false) }
    val trimSliderIx = remember { MutableInteractionSource() }
    LaunchedEffect(trimSliderIx) {
        trimSliderIx.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> trimScrubbing = true
                is PressInteraction.Release,
                is PressInteraction.Cancel -> trimScrubbing = false
            }
        }
    }
    // The voice playback and clip/trim controls fold under their own card, below the
    // processors, same default CLOSED: the voice only matters once Lip Sync is on and a
    // clip is loaded, and a standing playback row pushed the inputs further down.
    var voiceSettingsMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The settings gear's absolute window position and height, measured on layout: the
    // popup is a sibling anchored to the gear's Box, but the gear is not where the source
    // tile's arrow is -- it sits at the far right of a weighted row, so a hard-coded
    // dx/dy cannot place the sheet. Measuring sidesteps that.
    var voiceGearPos by remember { mutableStateOf(Offset.Zero) }
    var voiceGearH by remember { mutableIntStateOf(0) }
    // The target tile's settings gear, same measured-anchor treatment: the popup holds the
    // three pipeline stages, so they sit beside the input they act on.
    var targetGearPos by remember { mutableStateOf(Offset.Zero) }
    var targetGearH by remember { mutableIntStateOf(0) }
    var targetSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    // The source tile's expand arrow, same measured-anchor treatment: it moved from the
    // tile's left to its right, so the old "arrow hugs the row's left edge" dx is gone.
    var sourceArrowPos by remember { mutableStateOf(Offset.Zero) }
    var sourceArrowH by remember { mutableIntStateOf(0) }
    // The log panel folds under its caption. Default CLOSED -- it is a debug readout,
    // and a standing 170 dp panel below the buttons made the page longer than it needed
    // to be on every screen, not just while something was running.
    var logExpanded by rememberSaveable { mutableStateOf(false) }
    // The batch queue folds into a card opened from the swapped pane's label row,
    // default CLOSED. The card itself only exists while open -- its header moved up.
    var batchMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The finished output (video player + save/share/delete) folds under its own card,
    // default CLOSED, above the log.
    var outputResultExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------------------------------------------------------------- inputs
        //
        // SOURCE and TARGET share one 72 dp row (VOICE joins it as a third equal slot
        // while Lip Sync is on), and the inputs are always on screen -- an empty slot is
        // a call to action, and neither input may push the other off the first screen.
        // The source is a face that never changes during a run, so it stays a thumbnail
        // for its whole life; the target tile doubles as the ORIGINAL half of the
        // before/after -- its frame shows the source frame of the swap -- so there is no
        // separate full-width "original" pane below any more.
        //
        // ONE card holds the three inputs and the arrow between source and target: same
        // surface as every other group on the page, but no outline of its own -- the
        // frame is drawn on each tile instead, so the tiles read as the framed elements.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { inputRowW = it.size.width },
            verticalAlignment = Alignment.Top,
            // Source, the arrow and target keep their 8 dp gaps and start from the card's
            // left edge; the voice tile (when Lip Sync is on) carries a weight and so
            // takes whatever width is left over, right to the card's edge.
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                FaceTile(
                    label = stringResource(R.string.swap_source_face),
                    bitmap = sourceThumb,
                    placeholder = stringResource(R.string.swap_source_pick),
                    onClick = if (idle) onPickSource else null,
                    actionIcon = if (hasSource) null else Icons.Default.Add,
                    actions = {
                        // Shoot a face instead of finding one. Stills only: a source is an
                        // identity, and there is no video form of that.
                        //
                        // The camera stays through a run -- vanishing mid-swap made the
                        // tile jump -- dimmed to 31% (a 69% opacity drop) and deaf to taps
                        // until the run ends.
                        IconButton(onCaptureSource, Modifier.size(24.dp), enabled = idle) {
                            HintIcon(stringResource(R.string.swap_capture_source)) {
                                Icon(painterResource(R.drawable.ic_photo_camera),
                                     stringResource(R.string.swap_capture_source), Modifier.size(16.dp),
                                     tint = MaterialTheme.colorScheme.onSurfaceVariant
                                         .copy(alpha = if (idle) 1f else 0.31f))
                            }
                        }
                    },
                    bottomActions = {
                        // Removing the source is not destructive -- it drops a reference to a photo
                        // the user still has -- so unlike the output it does not confirm.
                        //
                        // Same as the camera above: visible through a run, 31%, inert.
                        if (hasSource) {
                            IconButton(onClearSource, Modifier.size(24.dp), enabled = idle) {
                                HintIcon(stringResource(R.string.swap_remove_source)) {
                                    Icon(Icons.Default.Delete,
                                         stringResource(R.string.swap_remove_source),
                                         Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.onSurfaceVariant
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                        }
                    },
                    footer = if (hasSource) {
                        {
                            Box {
                                // Not a collapse chevron: this opens a picker (the source
                                // faces, plus the per-person switch), so the affordance is
                                // the list glyph that says "a set of choices lives behind
                                // this", and it does not flip when open.
                                HintIcon(stringResource(R.string.swap_switch_source)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        stringResource(R.string.swap_switch_source),
                                        // 24 dp box as before, but 1 dp of inset instead of 4:
                                        // the glyph displays 6 dp taller (16 -> 22 dp), so the
                                        // thin list lines read the same height as the solid
                                        // camera/trash glyphs beside them. The box -- and so
                                        // the row -- does not change size.
                                        Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .onGloballyPositioned {
                                                sourceArrowPos = it.positionInWindow()
                                                sourceArrowH = it.size.height
                                            }
                                            .clickable { sourcePickerExpanded = !sourcePickerExpanded }
                                            .padding(1.dp),
                                        // onSurfaceVariant, like the strip's other icons: this
                                        // glyph no longer sits on the photo (where white
                                        // showed), it sits on the card's own surface.
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (sourcePickerExpanded && sourceArrowH > 0) {
                                    val density = LocalDensity.current
                                    val sheetW = with(density) {
                                        inputRowW.toDp().takeIf { inputRowW > 0 } ?: Dp.Unspecified
                                    }
                                    val sheetShape = RoundedCornerShape(20.dp)
                                    val childShape = RoundedCornerShape(16.dp)
                                    val childBorder = Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        childShape,
                                    )
                                    // Same measured placement as the voice gear's sheet:
                                    // screen-centred horizontally, its top just under the
                                    // tile. A hard-coded dx/dy cannot work any more -- the
                                    // arrow no longer sits at the row's left edge.
                                    val dx = ((with(density) { screenW.dp.toPx() } - inputRowW) / 2f -
                                              sourceArrowPos.x).roundToInt()
                                    val dy = sourceArrowH + with(density) { 3.dp.roundToPx() }
                                    Popup(
                                        alignment = Alignment.TopStart,
                                        offset = IntOffset(dx, dy),
                                        onDismissRequest = { sourcePickerExpanded = false },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Column(
                                            Modifier
                                                .width(sheetW)
                                                .shadow(12.dp, sheetShape)
                                                .clip(sheetShape)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                    sheetShape,
                                                )
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(childShape)
                                                    .then(childBorder)
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                            ) {
                                                SourceRow(
                                                    thumbs = sourceThumbs,
                                                    active = activeSource,
                                                    keepOriginalBrush = keepOriginalBrush,
                                                    onSelect = onSelectSource,
                                                    onKeepOriginal = if (assignMode) onKeepOriginal else null,
                                                    enabled = idle,
                                                    showLabels = false,
                                                    tileSize = 60.dp,
                                                )
                                            }
                                            if (!imageTarget && hasTarget && sourceThumbs.isNotEmpty()) {
                                                Column(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clip(childShape)
                                                        .then(childBorder)
                                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Column(Modifier.weight(1f)) {
                                                            Text(
                                                                stringResource(R.string.live_assign_title),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                            )
                                                            Text(
                                                                stringResource(
                                                                    if (assignMode) R.string.swap_assign_on
                                                                    else R.string.swap_assign_off),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                        if (assignMode && personAssignments.isNotEmpty()) {
                                                            TextButton(
                                                                onClick = onClearAssignments,
                                                                enabled = idle,
                                                            ) {
                                                                Text(stringResource(R.string.live_assign_clear))
                                                            }
                                                        }
                                                        Switch(
                                                            checked = assignMode,
                                                            onCheckedChange = { onToggleAssignMode() },
                                                            enabled = idle,
                                                            modifier = Modifier.alpha(
                                                                if (assignMode) 0.69f else 1f),
                                                        )
                                                    }
                                                    if (assignMode) {
                                                        Text(
                                                            stringResource(
                                                                if (personThumbs.isEmpty())
                                                                    R.string.swap_assign_no_people
                                                                else R.string.swap_assign_hint),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Row(
                                                            Modifier
                                                                .fillMaxWidth()
                                                                .horizontalScroll(rememberScrollState()),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        ) {
                                                            personThumbs.forEachIndexed { index, thumb ->
                                                                val label = stringResource(
                                                                    R.string.swap_assign_person,
                                                                    index + 1,
                                                                )
                                                                val personShape = RoundedCornerShape(12.dp)
                                                                val selected = index == selectedPerson
                                                                Box(
                                                                    Modifier.clickable(enabled = idle) {
                                                                        onSelectPerson(index)
                                                                    },
                                                                ) {
                                                                    Image(
                                                                        thumb.asImageBitmap(),
                                                                        contentDescription = label,
                                                                        modifier = Modifier
                                                                            .size(60.dp)
                                                                            .clip(personShape)
                                                                            .border(
                                                                                BorderStroke(
                                                                                    if (selected) 2.dp else 1.dp,
                                                                                    if (selected) MaterialTheme.colorScheme.primary
                                                                                    else MaterialTheme.colorScheme.outlineVariant,
                                                                                ),
                                                                                personShape),
                                                                        contentScale = ContentScale.Crop,
                                                                    )
                                                                    personAssignments[index]?.let { slot ->
                                                                        Text(
                                                                            if (slot < 0)
                                                                                stringResource(R.string.swap_assign_badge_keep)
                                                                            else (slot + 1).toString(),
                                                                            color = Color.White,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier
                                                                                .align(Alignment.BottomEnd)
                                                                                .padding(
                                                                                    horizontal = 4.dp,
                                                                                    vertical = 2.dp),
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else null,
                )
                // ⇒ marks the direction of the swap: the source face BECOMES the target.
                // Boxed to the CONTENT square's height and centred in it, so the arrow
                // lines up with the two tiles' pictures -- not with the whole tile, whose
                // icon strip hangs below the picture.
                Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "⇒",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // The tile is a 72 dp square -- it never needs more than a couple of
                // hundred pixels of the original. Drawing the full 4096-edge frame (a
                // 64 MB ARGB bitmap) into that square on every scroll pass is half the
                // "page scrolls janky after a swap" report. Downscale for display;
                // `preview.original` itself keeps the full resolution for the pipeline
                // and the output-size math below.
                val originalDisplay = remember(preview.original) {
                    preview.original?.displayThumb(256)?.first
                }
                FaceTile(
                    // The tile names what it holds: TARGET while asking for one, ORIGINAL once
                    // it is showing the source frame. The timestamp is dropped here -- the
                    // tile's badge is a 9 sp plate, and a clock string does not survive that.
                    label = stringResource(if (hasTarget) R.string.swap_pane_original
                                           else R.string.swap_pane_target),
                    bitmap = originalDisplay,
                    placeholder = stringResource(when {
                        run.preparing -> R.string.swap_reading_video
                        hasTarget -> R.string.swap_seeking
                        else -> R.string.swap_add_target
                    }),
                    // No weight: the tile wraps to its content (the 72 dp square while
                    // empty, the 72 dp frame once filled) instead of stretching across
                    // the row -- the voice tile takes the leftover width.
                    // The tile IS the picker. A separate full-width button said the same thing
                    // twice and cost a row of height the wordmark needed.
                    onClick = if (idle) onPickTarget else null,
                    actionIcon = if (hasTarget) null else Icons.Default.Add,
                    // The still camera keeps the top corner with the other tile actions; the
                    // video camera goes back to the bottom-right corner it started in.
                    actions = {
                        if (!hasTarget) {
                            // CAMERA, beside the gallery pick, shown while the tile is EMPTY -- which
                            // is when someone deciding what to swap needs it. Two buttons because a
                            // still and a clip take different routes through the system camera, and
                            // one button that then asks which is a tap for a question the icons answer.
                            IconButton(onCapturePhoto, enabled = idle, modifier = Modifier.size(24.dp)) {
                                HintIcon(stringResource(R.string.swap_capture_photo)) {
                                    Icon(painterResource(R.drawable.ic_photo_camera),
                                         stringResource(R.string.swap_capture_photo), Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.onSurfaceVariant
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                        }
                    },
                    bottomActions = {
                        // VIDEO CAMERA, back on its original seat in the bottom-right corner.
                        if (!hasTarget) {
                            IconButton(onCaptureVideo, enabled = idle, modifier = Modifier.size(24.dp)) {
                                HintIcon(stringResource(R.string.swap_capture_video)) {
                                    Icon(painterResource(R.drawable.ic_videocam),
                                         stringResource(R.string.swap_capture_video), Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.onSurfaceVariant
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                        }
                        // FACES, the upstream pane's own switch, moved into the tile's
                        // icon column: the detector's boxes are drawn over THIS tile's
                        // frame, so the switch lives beside them. Icon goes red while on.
                        if (hasTarget) {
                            IconButton(onToggleFaceBoxes, enabled = idle,
                                       modifier = Modifier.size(24.dp)) {
                                HintIcon(stringResource(R.string.swap_show_faces)) {
                                    Icon(Icons.Default.Face,
                                         stringResource(R.string.swap_show_faces),
                                         Modifier.size(16.dp),
                                         tint = (if (showFaceBoxes) FfRed
                                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                            // TRASH, on the same bottom edge as the other tiles' delete.
                            IconButton(onClearTarget, enabled = idle, modifier = Modifier.size(24.dp)) {
                                HintIcon(stringResource(R.string.swap_remove_target)) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.swap_remove_target),
                                         Modifier.size(16.dp),
                                         tint = MaterialTheme.colorScheme.onSurfaceVariant
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                        }
                    },
                    // The detector's boxes, already gated by MainActivity on the overlay
                    // switch, drawn over the tile's own frame; a tap on a face picks it
                    // as the reference (a miss still opens the target picker).
                    faceBoxes = preview.faceBoxes,
                    referenceBox = preview.referenceBox,
                    onPickFace = if (showFaceBoxes && !assignMode && idle) onPickFace else null,
                    // The stage chips used to stand on their own card above this row. They
                    // live behind this gear now: the stages act on the TARGET, so they sit
                    // beside the input they configure (and a fresh screen loses ~160 dp).
                    // Leading: the gear opens the strip, which leaves the trash at its far
                    // right -- the same layout the source tile uses, settings before delete.
                    footer = {
                        Box {
                            HintIcon(
                                stringResource(R.string.swap_processors),
                                Modifier.onGloballyPositioned {
                                    targetGearPos = it.positionInWindow()
                                    targetGearH = it.size.height
                                },
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    stringResource(R.string.swap_processors),
                                    // 24 dp box, same as the strip's action buttons.
                                    Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { targetSettingsExpanded = true }
                                        .padding(4.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (targetSettingsExpanded && targetGearH > 0) {
                                val density = LocalDensity.current
                                val sheetW = with(density) {
                                    inputRowW.toDp().takeIf { inputRowW > 0 } ?: Dp.Unspecified
                                }
                                val sheetShape = RoundedCornerShape(20.dp)
                                // Same measured placement as the other two gear sheets:
                                // screen-centred horizontally, its top just under the tile.
                                val dx = ((with(density) { screenW.dp.toPx() } - inputRowW) / 2f -
                                          targetGearPos.x).roundToInt()
                                val dy = targetGearH + with(density) { 3.dp.roundToPx() }
                                Popup(
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(dx, dy),
                                    onDismissRequest = { targetSettingsExpanded = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        Modifier
                                            .width(sheetW)
                                            .shadow(12.dp, sheetShape)
                                            .clip(sheetShape)
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                sheetShape,
                                            )
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        ProcessorsPanel(
                                            opts = opts,
                                            onOptsChange = onOptsChange,
                                            hasEnhancer = hasEnhancer,
                                            hasLipSyncer = hasLipSyncer,
                                            hasTarget = hasTarget,
                                            durationMs = durationMs,
                                            idle = idle,
                                            onRequestModel = onRequestModel,
                                            onOpenSettings = { targetSettingsExpanded = false
                                                               settingsFor = it },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                if (opts.lipSync) FaceTile(
                    label = stringResource(R.string.swap_pane_voice),
                    bitmap = null,
                    placeholder = if (hasVoice) (voiceName ?: stringResource(R.string.swap_voice_picked))
                                  else stringResource(R.string.swap_voice_add),
                    // The one tile that stretches: it takes the row's leftover width, so
                    // its surface reaches the card's right edge, and its content fills
                    // that width -- a long voice name needs the room.
                    modifier = Modifier.weight(1f),
                    stretch = true,
                    // The tile IS the picker, except while a clip is loaded or the mic is
                    // live -- the record button owns the interaction then, and the whole-tile
                    // tap must not fire mid-capture.
                    onClick = if (idle && !recordingVoice) onPickVoice else null,
                    actionIcon = if (hasVoice) null else Icons.Default.Add,
                    actions = {
                        // RECORD. The lip syncer needs a voice that is not the target's own
                        // audio, and the microphone is the one source every user has -- no file
                        // to go find first.
                        if (idle) {
                            IconButton(onToggleRecordVoice, modifier = Modifier.size(24.dp)) {
                                HintIcon(stringResource(if (recordingVoice) R.string.swap_voice_stop
                                                       else R.string.swap_voice_record)) {
                                    Icon(painterResource(if (recordingVoice) R.drawable.ic_stop
                                                         else R.drawable.ic_mic),
                                         stringResource(if (recordingVoice) R.string.swap_voice_stop
                                                        else R.string.swap_voice_record),
                                         Modifier.size(16.dp),
                                         tint = (if (recordingVoice) MaterialTheme.colorScheme.error
                                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                                             .copy(alpha = if (idle) 1f else 0.31f))
                                }
                            }
                        }
                    },
                    bottomActions = {
                        if (hasVoice && idle) {
                            IconButton(onClearVoice, modifier = Modifier.size(24.dp).alpha(if (idle) 1f else 0.31f)) {
                                HintIcon(stringResource(R.string.swap_remove_voice)) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.swap_remove_voice),
                                         Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    // 语音设置入口：叠加在瓦片左下角、内容上层（同「选择源人脸」
                    // 箭头的 footer 座位）。16 dp 净显示、标准图标色；有语音才存在——
                    // 空瓦片没有可设置的东西，整个图标隐藏而非变灰。
                    // 弹层也声明在这里：Popup 锚定其声明所在的布局节点，只有放在
                    // 齿轮旁边才能出现在齿轮正下方（放在页面级会锚到页顶）。
                    footer = if (opts.lipSync && hasVoice) {
                        {
                            Box {
                                HintIcon(
                                    stringResource(R.string.swap_voice_settings),
                                    Modifier.onGloballyPositioned {
                                        voiceGearPos = it.positionInWindow()
                                        voiceGearH = it.size.height
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        stringResource(R.string.swap_voice_settings),
                                        // 24 dp box, same as the strip's action buttons.
                                        Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { voiceSettingsMenuExpanded = true }
                                            .padding(4.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (voiceSettingsMenuExpanded && voiceGearH > 0) {
                                    // The same sheet the source tile's arrow raises -- same
                                    // width, shape and contents. Only the placement differs,
                                    // because this gear is not where that arrow is: the arrow
                                    // opens the FIRST tile of the row (its box hugs the row's
                                    // left edge), the gear sits at the far right of a
                                    // weighted row. So instead of the arrow's hard-coded
                                    // dx/dy, measure the gear and solve for the two rules
                                    // that must hold: the sheet is centred on the SCREEN,
                                    // and it hangs just under the tile the gear belongs to.
                                    val density = LocalDensity.current
                                    val sheetW = with(density) {
                                        inputRowW.toDp().takeIf { inputRowW > 0 } ?: Dp.Unspecified
                                    }
                                    val sheetShape = RoundedCornerShape(20.dp)
                                    // TopStart anchors the sheet's LEFT edge to the
                                    // gear's left edge, so dx is "screen-centre minus
                                    // sheet-centre, measured from the gear".
                                    val dx = ((with(density) { screenW.dp.toPx() } - inputRowW) / 2f -
                                              voiceGearPos.x).roundToInt()
                                    // The offset is already relative to the anchor, and
                                    // the anchor's top IS the gear's top, so dropping by
                                    // the gear's own height lands the sheet's top edge on
                                    // the tile's bottom edge; 3 dp clears it.
                                    val dy = voiceGearH + with(density) { 3.dp.roundToPx() }
                                    Popup(
                                        alignment = Alignment.TopStart,
                                        offset = IntOffset(dx, dy),
                                        onDismissRequest = { voiceSettingsMenuExpanded = false },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Column(
                                            Modifier
                                                .width(sheetW)
                                                .shadow(12.dp, sheetShape)
                                                .clip(sheetShape)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                                        sheetShape)
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            // The playback and trim sliders read too high-contrast against the theme,
                                            // so every part (thumb, active and inactive track) has its opacity cut by
                                            // 31%: 69% alpha keeps the same hue with a much softer contrast -- the
                                            // same treatment the output and trim sliders already use.
                                            val voiceSliderColors = SliderDefaults.colors(
                                                thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.69f),
                                                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.69f),
                                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.69f),
                                            )
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(onVoicePlayPause, enabled = idle, modifier = Modifier.size(36.dp)) {
                                                    // 31% dim when idle, like every other disabled icon on this
                                                    // screen; the drawn pause bars share it.
                                                    val voiceTint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = if (idle) 1f else 0.31f)
                                                    HintIcon(stringResource(if (voicePlaying) R.string.out_pause
                                                                            else R.string.swap_voice_play)) {
                                                        if (voicePlaying) {
                                                            // Two bars, drawn rather than an icon: the icons artifact this app
                                                            // carries (material3's transitive icons-core) has PlayArrow but no
                                                            // Pause, and extended-icons is a heavy addition for one glyph.
                                                            val pauseTint = voiceTint
                                                            Canvas(Modifier.size(16.dp)) {
                                                                val bar = 4.dp.toPx()
                                                                val gap = 3.dp.toPx()
                                                                val top = 0.dp.toPx()
                                                                val bottom = size.height
                                                                drawRoundRect(
                                                                    color = pauseTint,
                                                                    topLeft = Offset(0f, top),
                                                                    size = Size(bar, bottom - top),
                                                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                                                )
                                                                drawRoundRect(
                                                                    color = pauseTint,
                                                                    topLeft = Offset(bar + gap, top),
                                                                    size = Size(bar, bottom - top),
                                                                    cornerRadius = CornerRadius(1.dp.toPx()),
                                                                )
                                                            }
                                                        } else {
                                                            Icon(Icons.Default.PlayArrow,
                                                                 stringResource(R.string.swap_voice_play),
                                                                 Modifier.size(20.dp),
                                                                 tint = voiceTint)
                                                        }
                                                    }
                                                }
                                                Slider(
                                                    value = voicePosMs.coerceIn(0f, voiceDurationMs.toFloat()),
                                                    onValueChange = onVoiceSeek,
                                                    valueRange = 0f..voiceDurationMs.toFloat(),
                                                    enabled = idle,
                                                    modifier = Modifier.weight(1f),
                                                    colors = voiceSliderColors,
                                                )
                                                Text("${fmt(voicePosMs)} / ${fmt(voiceDurationMs.toFloat())}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     fontFamily = FontFamily.Monospace,
                                                     modifier = Modifier.padding(start = 8.dp))
                                            }
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Caption(stringResource(R.string.swap_voice_trim), Modifier.weight(1f))
                                                Text("${fmt(voiceTrimStartMs)} – ${fmt(voiceTrimEndMs)}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     fontFamily = FontFamily.Monospace)
                                            }
                                            RangeSlider(
                                                value = voiceTrimStartMs..voiceTrimEndMs,
                                                onValueChange = { r ->
                                                    // The same minimum span as the video trim, for the same reason: the
                                                    // mouth needs a mel window, the encoder needs a frame.
                                                    onVoiceTrimChange(r.start, maxOf(r.endInclusive, r.start + 333f))
                                                },
                                                valueRange = 0f..voiceDurationMs.toFloat(),
                                                enabled = idle,
                                                colors = voiceSliderColors,
                                            )
                                            Text(stringResource(R.string.swap_voice_trim_hint),
                                                 style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    } else null,
                )
            }
        }

        // ---------------------------------------------------------------- result
        //
        // The result of the swap gets a full-width pane of its own, sized from the TARGET.
        // The pane fills the available width; its height follows the target's aspect ratio
        // capped so a tall target never eats the screen.
        // 方向三级回退：输出文件自身（runBatch/onOpenBatchOutput 从输出缩略图记录，同比例）
        // → 目标窗格第一帧 → targetAspect。批量跑在空窗格上时目标帧不存在，旧实现直接
        // 落到 16:9 的 paneHeight，竖屏输出被压成横向小条，读不出方向。
        val tW = outputW.takeIf { it > 0 } ?: preview.original?.width ?: 0
        val tH = outputH.takeIf { it > 0 } ?: preview.original?.height ?: 0
        // 横向结果用 pair 窗格的宽度（upstream 的 before/after pair 按整列排，screenW-32）
        // —— upstream 的"换脸结果"看起来更大，差的就是这 32dp。竖向保持更窄的 screenW-64：
        // 竖向的高度上限已经把盒子压窄，宽再放开会把 Swap 按钮挤出首屏。
        //
        // 需求⑤：宽与高互相钳制，盒子比例恒等于片段自身的比例。旧实现只钳高：maxResultH
        // 的 180dp 下限在分屏/小窗的矮窗口里生效后，宽 = 高 × 比例 可能反超列宽，盒子
        // 溢出列外——"小窗里换脸结果比例不对"就是它。现在超宽则回推高度，两个方向都不越界。
        val resultAspect = when {
            tW > 0 && tH > 0 -> tW.toFloat() / tH.toFloat()   // width / height
            !hasTarget && targetAspect > 0f -> targetAspect
            // 需求①：空态呈现"有横向内容时的样子"——固定按 16:9 的横向宽扁盒取尺寸，
            // 不再借用 paneHeight（那是目标窗格的高度预算，空态盒因此显得又窄又高）。
            else -> 16f / 9f
        }
        val maxPaneW = if (resultAspect >= 1f) (screenW - 32).dp else (screenW - 64).dp
        // ⚠ The result pane's height has a CEILING, or a tall target eats the screen:
        // a tall portrait frame scaled to full width would be taller than a phone.
        // The pane used to size itself freely, and the Swap button -- everything below
        // the pane, really -- slid off the first screen; the button was still THERE and
        // still clickable at the edge of the fold, it just could not be seen.
        val maxResultH = (screenH - 460).dp.coerceIn(180.dp, 420.dp)
        var h = maxPaneW / resultAspect
        if (h > maxResultH) h = maxResultH
        var wResult: Dp = h * resultAspect
        if (wResult > maxPaneW) {
            wResult = maxPaneW
            h = wResult / resultAspect
        }
        val resultH: Dp = h
        val resultW: Dp? = wResult

        // Always shown by default. The placeholder reads as a call to action until the
        // inputs exist, and once they do it is the after half of the before/after.
        //
        // `modelsMissing` keeps the download overlay reachable on a fresh install: it
        // lives on this pane because it is the one that cannot draw without the models.
        //
        // ⚠ DISPLAY THUMBNAIL: the pipeline frame is up to 1920 on the long edge (an
        // 8 MB ARGB bitmap) and the pane renders it at roughly the width of the screen --
        // every scroll pass uploads and samples the full thing. The pane only DRAWS what
        // it is given, so the swap result is downscaled for display here; the full
        // `swappedFrame` still lives in the Activity state and is what Save writes.
        // `remember` keys on the bitmap, so the scale happens once per frame, not on
        // every recomposition during a scroll. This is the fix for "the page scrolls
        // janky after a swap".
        val swappedDisplay = remember(preview.swapped) {
            preview.swapped?.displayThumb()?.first
        }
        // The pane is wrapped in a zero-extra Box purely as the batch menu's popup
        // anchor: TopCenter then centres the dropdown on the page and the y-offset
        // drops it just under the label row (3 dp container pad + 40 dp slot + 2 dp
        // row pad = 45 dp from the pane's top edge).
        Box(Modifier.fillMaxWidth()) {
            PreviewPane(
                label = stringResource(R.string.swap_pane_swapped),
                height = resultH,
                bitmap = swappedDisplay,
                placeholder = when {
                    modelsMissing -> ""
                    // Already a finished, localized sentence from the Activity -- notably
                    // the content gate's refusal, which must not be rebuilt here.
                    preview.note != null -> preview.note
                    preview.busy && !preview.warm ->
                        stringResource(R.string.swap_loading_models)
                    preview.busy -> stringResource(R.string.swap_swapping_frame)
                    !hasSource -> stringResource(R.string.swap_pick_a_source)
                    // No "tap refresh" any more: the preview warms itself as soon as both
                    // inputs exist, so this is a transient state rather than an instruction.
                    else -> stringResource(R.string.swap_preparing_preview)
                },
                // Full-width container; the image box inside is narrower when the result is
                // portrait (contentWidth), centred rather than letterboxed into side bars.
                modifier = Modifier.fillMaxWidth(),
                contentWidth = resultW,
                // The download lives here rather than in a bar of its own: this is the pane
                // that cannot draw anything without the models, so it is where their absence
                // is already visible.
                overlay = if (modelsMissing) { { DownloadOverlay(onDownload) } } else null,
                zoom = zoom,
                // 存帧下载/加载圈：紧贴"换脸结果"标签之后的独立槽位。
                afterLabel = {
                    // Spinner WHILE working, save button when there is something to
                    // save. Never both: the fixed slot height keeps either from moving
                    // the trim slider and the Swap button down the screen mid-interaction.
                    //
                    // The save writes the previewed frame straight out of the pane. The
                    // output pane has had a Save frame button since the video path existed,
                    // but it can only reach frames of a FINISHED run -- so pulling one
                    // still out of a clip meant swapping the whole clip first.
                    if (!preview.busy && preview.swapped != null) {
                        IconButton(onClick = onSavePreviewFrame, enabled = idle,
                                   // 用户实测 -22 dp 偏右：向左 1 dp。
                                   modifier = Modifier.offset(x = (-23).dp)) {
                            HintIcon(stringResource(R.string.out_save_frame)) {
                                Icon(
                                    IconDownload,
                                    stringResource(R.string.out_save_frame),
                                    // 同一行触发头的净显示规格：22 dp；与其它图标同色。
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = if (idle) 1f else 0.31f),
                                )
                            }
                        }
                    }
                    if (preview.busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                // 批量添加标题组（标题+箭头、自动保存、清空）：整组靠右，箭头随
                // 批量添加标题组（文本+自动保存开关+清空）：整组靠右；菜单浮层见
                // 下方 Popup（居中下拉）。无箭头：展开与否由菜单本体自己说明。
                trailing = {
                    // 输出设置：以"处理器"里 face_swapper 同款的小齿轮作触发头，在
                    // 批量添加之前；内容以居中浮层挂在窗格锚点上（见下方第二个 Popup）。
                    // 仅视频目标时出现；与批量菜单互斥，开一个关另一个。
                    if (durationMs > 0) {
                        HintIcon(stringResource(R.string.swap_output_settings)) {
                            Icon(
                                Icons.Default.Settings,
                                stringResource(R.string.swap_output_settings),
                                Modifier
                                    .size(32.dp)
                                    .offset(x = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        trimExpanded = !trimExpanded
                                        if (trimExpanded) batchMenuExpanded = false
                                    }
                                    .padding(6.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 批量添加入口：图标而非文本，与左侧齿轮同一触发头样式；
                    // 语义名（“批量添加”）给无障碍与长按提示。
                    HintIcon(stringResource(R.string.swap_batch_menu)) {
                        Icon(
                            painterResource(R.drawable.ic_playlist_add),
                            stringResource(R.string.swap_batch_menu),
                            Modifier
                                .size(34.dp)
                                .offset(x = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    batchMenuExpanded = !batchMenuExpanded
                                    if (batchMenuExpanded) trimExpanded = false
                                }
                                .padding(6.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 自动保存开关：Save 图标，启用时主题色（深），未启用时浅灰。
                    // 与两个触发头同规格：32dp 盒、6dp 内边距，净显示 20dp。
                    HintIcon(stringResource(R.string.batch_autosave)) {
                        Icon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.batch_autosave),
                            Modifier
                                .size(32.dp)
                                .offset(x = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = idle) { onBatchAutoSave(!batchAutoSave) }
                                .padding(6.dp),
                            tint = if (batchAutoSave) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                                       .copy(alpha = if (idle) 0.38f else 0.31f),
                        )
                    }
                    // 清空全部。32dp 盒、6dp 内边距，净显示 20dp，与触发头同规格；
                    // 队列空时不透明度降到 31%，读作"没有可清的东西"，但仍占着位置。
                    // 跑批中同样 31%：图标已被禁用，全亮会读作可点。
                    IconButton(
                        onClearBatch,
                        enabled = idle && batch.isNotEmpty(),
                        // 同「音频」瓦片右下删除图形的水平位置：图标列 28 dp 盒中心在
                        // 页面右端-32 dp，而标签行右端在-24 dp、32 dp 盒中心在-40 dp。
                        // +8 dp 补齐（标签行右端与瓦片行右端的固定差）。
                        modifier = Modifier.size(32.dp).offset(x = 8.dp),
                    ) {
                        HintIcon(stringResource(R.string.batch_clear_desc)) {
                            Icon(Icons.Default.Delete,
                                 stringResource(R.string.batch_clear_desc),
                                 Modifier.size(20.dp),
                                 tint = MaterialTheme.colorScheme.onSurfaceVariant
                                     .copy(alpha = if (idle && batch.isNotEmpty()) 1f else 0.31f))
                        }
                    }
                },
            )
            if (batchMenuExpanded) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(
                        0,
                        with(LocalDensity.current) { 42.dp.roundToPx() },
                    ),
                    onDismissRequest = { batchMenuExpanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Box(
                        Modifier
                            .width((screenW - 44).dp)
                            .shadow(12.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(20.dp))
                            .padding(8.dp),
                    ) {
                        // 添加片段按钮（+） + 缩略图列表，横向排列
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // "+" 添加按钮，64dp，始终最左侧。
                            // 需求2: 无条件渲染——旧判定 (hasTarget && !imageTarget) 让按钮在
                            // "添加目标"为空时整个消失，批量菜单从此没有入口。点击事件除正在
                            // 生成视频（run.busy）外始终生效：队列一旦开始跑就固定下来，防止
                            // 跑批中途改队列。
                            IconButton(
                                onAddToBatch,
                                enabled = !run.busy,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Icon(Icons.Default.Add, stringResource(R.string.swap_batch_add),
                                     Modifier.size(28.dp),
                                     tint = MaterialTheme.colorScheme.onSurfaceVariant
                                         .copy(alpha = if (run.busy) 0.31f else 1f))
                            }
                            // 已添加的片段缩略图，64dp，横向排列
                            batch.forEachIndexed { i, item ->
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable(enabled = (idle || run.canCancel) && item.output != null) {
                                            onOpenBatchOutput(i)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (item.thumb != null) {
                                        val image = remember(item.thumb) { item.thumb.asImageBitmap() }
                                        Image(
                                            image, null,
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(6.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Icon(Icons.Default.PlayArrow, null,
                                             Modifier.size(24.dp),
                                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    // 状态角标
                                    if (item.state != BatchState.Waiting) {
                                        Text(
                                            stringResource(when (item.state) {
                                                BatchState.Running -> R.string.batch_running
                                                BatchState.Done -> R.string.batch_done
                                                BatchState.Refused -> R.string.batch_refused
                                                BatchState.Failed -> R.string.batch_failed
                                                BatchState.Skipped -> R.string.batch_skipped
                                                BatchState.Cancelled -> R.string.batch_cancelled
                                                else -> R.string.batch_waiting
                                            }),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 7.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = when (item.state) {
                                                BatchState.Done -> FfRed
                                                BatchState.Failed -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                    RoundedCornerShape(3.dp),
                                                )
                                                .padding(horizontal = 3.dp, vertical = 1.dp),
                                        )
                                    }
                                    // 已保存角标：该片段的成品已在相册（手动或自动保存）。放左上
                                    // 角，与底部状态角标、右上删除按钮错开——不用点开删除确认，
                                    // 哪些片段的成品已经在相册里一眼可辨，确认弹窗的计数也就对得
                                    // 上了。
                                    if (item.savedUri != null) {
                                        Text(
                                            stringResource(R.string.batch_saved),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 7.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                    RoundedCornerShape(3.dp),
                                                )
                                                .padding(horizontal = 3.dp, vertical = 1.dp),
                                        )
                                    }
                                    // 删除按钮
                                    if (idle) {
                                        IconButton(
                                            { onRemoveFromBatch(i) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(18.dp)
                                                .offset(x = 2.dp, y = (-2).dp),
                                        ) {
                                            Icon(Icons.Default.Delete,
                                                 stringResource(R.string.batch_remove),
                                                 Modifier.size(12.dp),
                                                 tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // The 输出设置 dropdown: same anchor and centred card as the batch menu,
            // holding the clip trim + output size + frame rate that used to sit in
            // their own SectionCard lower on the page (the knobs are per-run and
            // closed by default -- standing controls pushed the Swap button off the
            // first screen on every video target).
            if (durationMs > 0 && trimExpanded) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(
                        0,
                        with(LocalDensity.current) { 42.dp.roundToPx() },
                    ),
                    onDismissRequest = { trimExpanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Box(
                        Modifier
                            .width((screenW - 44).dp),
                    ) {
                        // 卡片外观（阴影/底色/描边）独立成层：拖裁剪滑条时整层淡出，
                        // 边框阴影一并消失；alpha 不能直接给内容容器，否则滑条一起被
                        // 淡掉，故分层。
                        Box(
                            Modifier
                                .matchParentSize()
                                .alpha(if (trimScrubbing) 0f else 1f)
                                .shadow(12.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(20.dp)),
                        )
                        Column(
                            Modifier
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 片段 — 输出设置首项
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .alpha(if (trimScrubbing) 0f else 1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.swap_clip_rate),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${fmt(trimStartMs)} – ${fmt(trimEndMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            val trimSliderColors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.69f),
                                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.69f),
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.69f),
                            )
                            // 自绘线用的颜色在 composable 里取好：Canvas 的 DrawScope
                            // lambda 不是 composable 上下文，碰不了 colorScheme。
                            val scrubActive = MaterialTheme.colorScheme.primary.copy(alpha = 0.69f)
                            val scrubInactive =
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.69f)
                            Box {
                                RangeSlider(
                                    value = trimStartMs..trimEndMs,
                                    onValueChange = { r ->
                                        trimScrubbing = true
                                        // Which handle moved: RangeSlider reports the whole range, so the
                                        // edge has to be inferred by comparing against what it was. The
                                        // previews then follow the handle under the finger rather than
                                        // always showing the start frame.
                                        val edge = if (r.start != trimStartMs) TrimEdge.Start else TrimEdge.End
                                        // Keep at least a third of a second, so the encoder always gets a frame.
                                        onTrimChange(r.start, maxOf(r.endInclusive, r.start + 333f), edge)
                                    },
                                    valueRange = 0f..durationMs.toFloat(),
                                    enabled = idle,
                                    onValueChangeFinished = { trimScrubbing = false },
                                    colors = trimSliderColors,
                                    // 一个源接两个拇指：任一拇指被按住（或点轨道带动
                                    // 最近拇指）都算进入聚焦态。
                                    startInteractionSource = trimSliderIx,
                                    endInteractionSource = trimSliderIx,
                                    // 聚焦态下真实滑条隐去（拇指竖杠一并消失），由下面的
                                    // Canvas 接手外观；手势仍走这个控件本身。
                                    modifier = Modifier.alpha(if (trimScrubbing) 0f else 1f),
                                )
                                if (trimScrubbing) {
                                    Canvas(
                                        Modifier
                                            .matchParentSize()
                                            .alpha(0.5f),
                                    ) {
                                        val inset = 10.dp.toPx()
                                        val trackW = (size.width - inset * 2f)
                                            .coerceAtLeast(1f)
                                        val h = 3.dp.toPx()
                                        val y = (size.height - h) / 2f
                                        val f0 = if (durationMs > 0)
                                                     trimStartMs / durationMs.toFloat()
                                                 else 0f
                                        val f1 = if (durationMs > 0)
                                                     trimEndMs / durationMs.toFloat()
                                                 else 1f
                                        drawRoundRect(
                                            color = scrubInactive,
                                            topLeft = Offset(inset, y),
                                            size = Size(trackW, h),
                                            cornerRadius = CornerRadius(h / 2f, h / 2f),
                                        )
                                        drawRoundRect(
                                            color = scrubActive,
                                            topLeft = Offset(inset + trackW * f0, y),
                                            size = Size(
                                                (trackW * (f1 - f0)).coerceAtLeast(h), h,
                                            ),
                                            cornerRadius = CornerRadius(h / 2f, h / 2f),
                                        )
                                    }
                                }
                            }
                            // 滑条之外的余下部分同层淡出。
                            Column(
                                Modifier.alpha(if (trimScrubbing) 0f else 1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // The REAL rate, not a hardcoded 30. The estimate was wrong on every
                                // clip that was not 30 fps, and it is the number the ETA is read against.
                                val effFps = if (opts.outputFps in 1..inputFps) opts.outputFps else inputFps
                                val estFrames = ((trimEndMs - trimStartMs) / 1000f * effFps).roundToInt()
                                Text(
                                    stringResource(R.string.swap_clip_summary,
                                                   estFrames, fmt(durationMs.toFloat()), effFps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Frame rate and output size are the same kind of decision.
                                Spacer(Modifier.height(6.dp))
                                // ⚠ The SOURCE option is named by its own number, not by the word "same".
                                // Sitting in a row that reads 480p / 720p / 1080p, "Same as source" was the
                                // one chip that did not say what it would produce -- and the clip's size is
                                // already on this screen, so there was nothing to look up. A clip whose
                                // short edge is not a familiar number ("606p") still reads honestly, and
                                // the hint underneath carries the full WxH either way.
                                val srcShort = minOf(targetW, targetH)
                                // "Same (960p)": the word says what the choice MEANS and the number says
                                // what it produces. The number alone made the source chip look like one
                                // more fixed size rather than the leave-it-alone option, which is what it
                                // is and what most runs want.
                                val srcName = if (srcShort > 0)
                                                  stringResource(R.string.swap_size_source_at, srcShort)
                                              else stringResource(R.string.swap_size_source)
                                // OUTPUT SIZE, on the SHORT edge so the aspect ratio never changes and
                                // "480p" means what it means everywhere else. Only sizes BELOW the clip's
                                // own are offered, for the same reason the frame rate only offers lower
                                // rates: enlarging costs bitrate and adds nothing, because the swapper runs
                                // at 256 whatever the frame is.
                                //
                                // ⚠ It is applied at DECODE, so it makes the RUN faster too -- detector prep
                                // and paste-back scale with frame area, and 4K is ~9x the area of 1080p.
                                // What it cannot do is make a face sharper; that is pixel boost and the
                                // enhancer, and this control must not be mistaken for them.
                                val shortEdge = srcShort
                                val sizes = listOf(480, 720, 1080).filter { it < shortEdge }
                                                .map { it to (it.toString() + "p") } +
                                            listOf(0 to srcName)
                                if (sizes.size > 1) {
                                    OptionSteps(
                                        stringResource(R.string.swap_output_size),
                                        sizes,
                                        if (opts.outputMaxShortEdge in 1 until shortEdge)
                                            opts.outputMaxShortEdge else 0,
                                        { onOptsChange(opts.copy(outputMaxShortEdge = it)) },
                                        hint = if (opts.outputMaxShortEdge in 1 until shortEdge)
                                                   stringResource(R.string.swap_size_hint_smaller)
                                               else if (targetW > 0 && targetH > 0)
                                                   stringResource(R.string.swap_size_hint_source_dims,
                                                                  targetW, targetH)
                                               else stringResource(R.string.swap_size_hint_source),
                                        enabled = idle,
                                    )
                                }

                                // Frame rate. Only rates BELOW the input's are offered: a higher one would
                                // duplicate frames, and each duplicate costs a full swap to produce nothing
                                // new. Dropping frames is the only direction that saves anything.
                                //
                                // ⚠ The low stops are the point, and 24/30/60 alone were not enough to be
                                // useful. On a 30 fps clip the deepest cut available was 24 -- a 20% saving
                                // against the CPU backend, which is an order of magnitude slower than the
                                // NPU -- and on a 24 fps clip nothing qualified, so the control hid itself
                                // and offered no reduction at all. 5/10/15 are what make it worth having:
                                // 30 -> 10 is a third of the frames and close to a third of the time,
                                // because VideoSwapper decimates BEFORE the swap rather than after it.
                                //
                                // Ascending, with "same as source" last: the slider then runs from cheapest
                                // on the left to full quality on the right, which is the direction the
                                // trade-off reads in.
                                val rates = listOf(5, 10, 15, 24, 30, 60).filter { it < inputFps }
                                                .map { it to "$it" } +
                                            listOf(0 to stringResource(R.string.swap_rate_same, inputFps))
                                if (rates.size > 1) {
                                    OptionSteps(
                                        stringResource(R.string.swap_frame_rate),
                                        rates,
                                        if (opts.outputFps in 1..inputFps) opts.outputFps else 0,
                                        { onOptsChange(opts.copy(outputFps = it)) },
                                        hint = if (opts.outputFps == 0 || opts.outputFps >= inputFps)
                                                   stringResource(R.string.swap_rate_hint_every)
                                               else stringResource(R.string.swap_rate_hint_drop),
                                        enabled = idle,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- run
        // One button, two jobs: a separate Cancel would sit dead for the entire time the
        // only thing you can do is start a swap.
        //
        // A still target has no button at all. The pane above IS the output, so a Swap
        // button would offer to compute something the user is already looking at, and the
        // Save button below is the only thing left to do.
        if (!imageTarget) {
            // The primary action carries the brand gradient -- the same sweep as the
            // header, FaceFusion red deepening to its hover state -- and a play mark, so
            // the one thing that starts a run reads as the thing that starts a run. While
            // busy it flips to a bordered cancel, because a solid red button that now says
            // "Cancel" would look like a run that is still inviting to be started.
            val busy = run.busy
            // READY = 两个输入都在、模型齐、Lip Sync 有驱动音。它刻意不含 idle：
            // 添加目标后要复制视频文件并读元数据（preparing，可能耗时数秒），期间
            // 若把按钮压成半透明灰，用户看到"目标已选好按钮却是灰的"会以为坏了。
            // 需求6 配套: the queue is self-held now -- rows run on their OWN uris, so
            // a queue with runnable rows can start even with an empty target pane;
            // requiring hasTarget here left the button dead on exactly the screen a
            // row delete produces (pane cleared, queue intact).
            val ready = hasSource &&
                        (hasTarget || batch.any {
                            it.state == BatchState.Waiting ||
                            it.state == BatchState.Running ||
                            it.state == BatchState.Cancelled
                        }) &&
                        !modelsMissing && (!opts.lipSync || hasVoice)
            // A batch whose every row has landed (Done/Refused/Failed/Skipped) is a RESULT,
            // not a pending run. The button used to keep reading "Swap n clips" and stayed
            // clickable, and pressing it again deleted every finished output just to run
            // the same batch a second time. It reads "Start" again and stays dead until a
            // row is waiting again -- clear rows or add clips to run more.
            // 需求5: Cancelled rows are RUNNABLE again -- a stopped batch is a batch
            // the user may want to finish, so the button comes back instead of staying
            // dead until a row is cleared by hand.
            val batchDone = batch.isNotEmpty() && batch.none {
                it.state == BatchState.Waiting || it.state == BatchState.Running ||
                it.state == BatchState.Cancelled
            }
            // 需求5: runBatchUi (canCancel) means a batch is between START and its
            // rows being reset -- the button must answer a cancel through that whole
            // window, including the instant busy has dropped but the rows still read
            // finished.
            val canRun = idle && ready && !batchDone
            val clickable = busy || run.canCancel || canRun
            // 只有真正缺条件才置灰；preparing 期间按钮保持品牌色，只是暂时不可点。
            val dimmed = !clickable && (!ready || batchDone)
            val playEnabled = hasSource && hasTarget && idle && !modelsMissing &&
                batch.size <= 1
            val playDimmed = !playEnabled
            @Composable
            fun ActionCell(
                modifier: Modifier = Modifier,
                enabled: Boolean,
                dimmed: Boolean,
                busyBorder: Boolean = false,
                onClick: () -> Unit,
                content: @Composable () -> Unit,
            ) {
                Box(
                    modifier
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (busyBorder) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable(enabled = enabled, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) { content() }
            }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionCell(
                        modifier = Modifier.weight(2f),
                        enabled = clickable,
                        dimmed = dimmed,
                        busyBorder = busy,
                        onClick = {
                            if (busy || run.canCancel) onCancel() else onSwap()
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!busy) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    stringResource(R.string.swap_action),
                                    Modifier.size(22.dp),
                                    tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.69f)
                                           else MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            Text(
                                stringResource(
                                    if (busy || run.canCancel) R.string.swap_cancel
                                    else if (batch.size > 1 && !batchDone) R.string.swap_action_batch
                                    else R.string.swap_action,
                                    batch.size,
                                ),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = when {
                                    busy -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                                    dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.69f)
                                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                                },
                            )
                        }
                    }
                    ActionCell(
                        modifier = Modifier.weight(1f),
                        enabled = playEnabled,
                        dimmed = playDimmed,
                        onClick = onLivePlay,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                stringResource(R.string.player_action),
                                Modifier.size(22.dp),
                                tint = if (playDimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.69f)
                                       else MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                stringResource(R.string.player_action),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = if (playDimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.69f)
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
                if (batch.size > 1) Text(
                    stringResource(R.string.player_batch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Only when there is something to report. It used to carry a standing
        // instruction, which the two empty preview panes above already give.
        // Rendered directly under the Swap button: the batch-queued count is the
        // reply to pressing "Add more clips", so it belongs right beneath the action.
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            // Only on a failure. A crash leaves no in-app log at all, which is why
            // BugReport also persists uncaught exceptions for the next launch.
            if (statusIsError) {
                TextButton(onShareLog) { Text(stringResource(R.string.swap_share_bug_report)) }
            }
        }

        if (run.busy || run.progress > 0f) {
            LinearProgressIndicator(
                progress = { run.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().alpha(0.69f),
            )
            if (run.framesTotal > 0) {
                val fps = if (run.elapsedS > 0) run.framesDone / run.elapsedS else 0.0
                val eta = if (fps > 0) (run.framesTotal - run.framesDone) / fps else 0.0
                Text(
                    stringResource(R.string.swap_progress, run.framesDone, run.framesTotal,
                                   "%.1f".format(fps), eta.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------------------- output result
        //
        // 输出结果，位于日志上方，与输出设置同为可折叠菜单（默认折叠）。
        // 标题行带有下载图标，其点击事件和显示状态与"保存到相册"按钮一致。
        if (outputFile != null || hasOutput) {
            SectionCard(
                stringResource(R.string.swap_output_result),
                collapsible = true,
                expanded = outputResultExpanded,
                onToggle = { outputResultExpanded = !outputResultExpanded },
                floating = false,
                trailing = {
            if (hasOutput && !outputAutoSaved) {
                // The batch's save-all state, derived from the queue itself: the entry
                // lights up only when EVERY clip finished, goes dim and dead once the
                // gallery holds them all, and comes back when the next run resets the
                // queue. A single run (empty queue) keeps the old save-current behaviour.
                val doneItems = batch.filter { it.state == BatchState.Done }
                val allBatchDone = batch.isNotEmpty() && doneItems.size == batch.size
                val allBatchSaved = doneItems.isNotEmpty() &&
                        doneItems.all { it.savedUri != null }
                val batchSaveAllReady = allBatchDone && !allBatchSaved && !savingAll
                val batchMode = batch.isNotEmpty()
                IconButton(
                    onClick = { if (batchMode) onSaveAll() else onSave() },
                    enabled = if (batchMode) batchSaveAllReady else (idle || run.canCancel),
                    modifier = Modifier.size(26.dp),
                ) {
                    // ⚠ Explicit tint: a disabled IconButton multiplies the inherited
                    // colour by Compose's own disabled alpha (0.38), which would stack
                    // with ours. Enabled reads onSurfaceVariant like every other icon;
                    // disabled is a fixed colour at exactly 31% so the dim stays honest.
                    val tint = if (batchMode && !batchSaveAllReady)
                                   MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.31f)
                               else if (idle || run.canCancel)
                                   MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.31f)
                    HintIcon(stringResource(R.string.swap_save_to_gallery)) {
                        Icon(
                            IconDownload,
                            stringResource(R.string.swap_save_to_gallery),
                            Modifier.size(18.dp),
                            tint = tint,
                        )
                    }
                }
            }
                },
            ) {
            if (outputFile != null) {
                // SWIPE BETWEEN BATCH RESULTS. The indices of everything finished, and where
                // the pane currently sits in that list.
                val doneIx = batch.indices.filter { batch[it].output != null }
                val cur = doneIx.indexOfFirst { batch[it].output == outputFile }
                Box(
                    Modifier.pointerInput(doneIx.size, cur) {
                        if (doneIx.size < 2 || cur < 0) return@pointerInput
                        // ⚠ HORIZONTAL only, and accumulated to a threshold rather than acted
                        // on per event. This cannot be detectHorizontalDragGestures: its slop
                        // check is single-axis (|dx| > slop, dy never consulted) and children
                        // see events before the page's verticalScroll, so on a fast flick --
                        // whose trace always carries some sideways drift -- this pane crossed
                        // the line first, consumed the whole gesture and the page froze
                        // mid-fling. Users read that as "the swipe didn't register" and
                        // needed two or three tries. The fix is to judge BOTH axes against
                        // slop and take the gesture only when horizontal clearly dominates;
                        // anything else is released untouched and the page scrolls.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            var dx = 0f
                            var dy = 0f
                            var horizontal: Boolean? = null
                            while (horizontal == null) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                if (abs(dx) > slop || abs(dy) > slop) {
                                    // 1.2x margin: near-diagonal flicks stay with the page.
                                    horizontal = abs(dx) > abs(dy) * 1.2f
                                } else if (!ev.changes.any { it.pressed }) {
                                    return@awaitEachGesture
                                }
                            }
                            if (!horizontal) return@awaitEachGesture
                            // A horizontal swipe it is -- from here the pane owns the
                            // gesture: consume moves so the page does not also scroll,
                            // accumulate, and swap results on lift past the threshold.
                            var accum = dx
                            while (true) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                accum += change.positionChange().x
                                change.consume()
                                if (!ev.changes.any { it.pressed }) break
                            }
                            val step = if (accum < -60f) 1 else if (accum > 60f) -1 else 0
                            if (step != 0)
                                doneIx.getOrNull(cur + step)?.let(onOpenBatchOutput)
                        }
                    }
                ) {
                    OutputPane(
                        file = outputFile,
                        height = resultH,
                        onSaveFrame = onSaveFrame,
                        partial = outputPartial,
                        // Playable DURING a batch: every finished clip is a complete file
                        // on disk (muxer stopped inside its own swap), so idle -- which is
                        // false for the whole run -- must not gate it. canCancel is the
                        // flag that says a batch is live; play what has landed while the
                        // rest still encode.
                        enabled = idle || run.canCancel,
                        contentWidth = resultW,
                    )
                }
                // Says the swipe exists. A gesture with nothing on screen to suggest it is a
                // gesture only its author knows about -- which is what the batch queue itself
                // had just been.
                if (doneIx.size > 1 && cur >= 0) {
                    Text(
                        stringResource(R.string.batch_output_of, cur + 1, doneIx.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (hasOutput) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Auto-save already put this clip in the gallery, so the Save button
                    // shows as the same spent "Saved to gallery" label it does after a
                    // manual save -- dim at 31% and dead, not a plain text line. Share
                    // stays: sending it somewhere is a different action from keeping it.
                    val doneItems = batch.filter { it.state == BatchState.Done }
                    val allBatchSaved = doneItems.isNotEmpty() &&
                            doneItems.all { it.savedUri != null }
                    val batchAllSaved = batch.isNotEmpty() && allBatchSaved
                    val spent = outputAutoSaved || batchAllSaved
                    Button(onSave,
                           // Every clip the gallery does not have is written before
                           // this button matters again; at that point it is a label,
                           // not an action, and a second press would write duplicates.
                           enabled = !spent && (idle || run.canCancel),
                           modifier = Modifier.weight(1f),
                           shape = RoundedCornerShape(14.dp),
                           colors = ButtonDefaults.buttonColors(
                               containerColor = MaterialTheme.colorScheme.surface,
                               contentColor = MaterialTheme.colorScheme.onBackground,
                               disabledContainerColor = MaterialTheme.colorScheme.surface,
                               disabledContentColor = if (spent)
                                   MaterialTheme.colorScheme.onSurfaceVariant
                                       .copy(alpha = 0.31f)
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                           ),
                           border = BorderStroke(1.dp,
                                                 MaterialTheme.colorScheme.outlineVariant)) {
                        Text(stringResource(
                            if (spent || saved) R.string.swap_saved_to_gallery
                            else R.string.swap_save_to_gallery))
                    }
                    // Share reads the gallery uri, not the working file -- nothing to send
                    // until the clip is saved. Dim and dead at 31% until then, live once
                    // savedUri exists (the auto-saved branch has it by definition).
                    val shareEnabled = idle && saved
                    OutlinedButton(onShare, enabled = shareEnabled,
                                   shape = RoundedCornerShape(14.dp),
                                   // Same control background as the Save button next to it:
                                   // card-surface in both schemes, not the default accent.
                                   colors = ButtonDefaults.outlinedButtonColors(
                                       containerColor = MaterialTheme.colorScheme.surface,
                                       contentColor = MaterialTheme.colorScheme.onBackground,
                                       disabledContainerColor = MaterialTheme.colorScheme.surface,
                                       disabledContentColor = if (!saved)
                                           MaterialTheme.colorScheme.onSurfaceVariant
                                               .copy(alpha = 0.31f)
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                   ),
                                   border = BorderStroke(1.dp,
                                                         MaterialTheme.colorScheme.outlineVariant)) {
                        Text(stringResource(R.string.swap_share))
                    }
                    // Deleting a render IS destructive -- minutes of NPU time, and the file is
                    // gone from the phone -- so this one asks, unlike the source and target
                    // buttons, which only drop a reference to a file the user still has.
                    //
                    // ⚠ VIDEO ONLY, and that is not an oversight. A still has no output file:
                    // its result is the swapped PANE, regenerated from the source and target
                    // whenever both are present. The button was shown for stills too and did
                    // nothing at all -- discardOutput() deletes outputFile, which is null on
                    // that path -- so it confirmed and then visibly ignored the answer.
                    //
                    // Clearing the pane instead would be worse, not better: the autowarm effect
                    // would redraw it within the same second. The way to get rid of a still's
                    // result is to remove the target, which has its own button on its own pane.
                    if (outputFile != null) {
                        OutlinedButton({ confirmDeleteOutput = true }, enabled = idle,
                                       shape = RoundedCornerShape(14.dp),
                                       // Same control background as the buttons around it.
                                       colors = ButtonDefaults.outlinedButtonColors(
                                           containerColor = MaterialTheme.colorScheme.surface,
                                           contentColor = MaterialTheme.colorScheme.onBackground,
                                           disabledContainerColor = MaterialTheme.colorScheme.surface,
                                           disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                       ),
                                       border = BorderStroke(1.dp,
                                                             MaterialTheme.colorScheme.outlineVariant)) {
                            HintIcon(stringResource(R.string.out_delete)) {
                                Icon(Icons.Default.Delete, stringResource(R.string.out_delete),
                                     Modifier.size(18.dp),
                                     tint = MaterialTheme.colorScheme.onSurfaceVariant
                                         .copy(alpha = if (idle) 1f else 0.31f))
                            }
                        }
                    }
                }
                if (savedPath != null)
                    Text(
                        savedPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            }
        }

        // ---------------------------------------------------------------- log
        if (log.isNotEmpty()) LogBox(log, expanded = logExpanded,
                                     onToggle = { logExpanded = !logExpanded },
                                     floating = false)

        Spacer(Modifier.height(8.dp))
    }

    if (confirmDeleteOutput) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOutput = false },
            title = { Text(stringResource(R.string.out_delete_title)) },
            text = { Text(stringResource(R.string.out_delete_body)) },
            confirmButton = {
                TextButton({ confirmDeleteOutput = false; onDeleteOutput() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton({ confirmDeleteOutput = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // ---------------------------------------------------------------- settings sheets
    //
    // What used to be the Advanced accordion, split by the processor each group belongs to
    // and hung off that processor's own gear. Advanced sat below the trim slider and the
    // Swap button, so reaching a mask blur meant scrolling PAST the control that starts the
    // run -- and every group in it was reached the same way regardless of which stage it
    // configured.
    //
    // ⚠ Face Masker and Face Detector are NOT the swapper's own settings: the masker is
    // shared with the lip syncer (one BoxMaskCache serves both, see Pipeline::Impl) and the
    // detector feeds every stage. They live behind the swapper's gear because face_swapper
    // is the one processor that is always on, so its gear is the one that can always be
    // reached -- not because they belong to it. Anything added here that a second stage
    // also reads deserves the same note.
    val sheet = settingsFor
    if (sheet != null) {
        AlertDialog(
            onDismissRequest = { settingsFor = null },
            confirmButton = {
                TextButton({ settingsFor = null }) { Text(stringResource(R.string.swap_close)) }
            },
            title = {
                Text(stringResource(when (sheet) {
                    "enhancer" -> R.string.swap_proc_enhancer
                    "lipsync"  -> R.string.swap_proc_lip_syncer
                    else       -> R.string.swap_proc_swapper
                }))
            },
            text = {
                // Scrollable: the swapper sheet holds three expandable cards, and all three
                // open at once is taller than a phone in landscape.
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (sheet) {
                        "enhancer" -> {
                            OptionSlider(
                                stringResource(R.string.opt_blend), opts.enhanceBlend,
                                { onOptsChange(opts.copy(enhanceBlend = it)) },
                                hint = when {
                                    opts.enhanceBlend >= 0.95f ->
                                        stringResource(R.string.opt_blend_hint_full)
                                    opts.enhanceBlend <= 0.05f ->
                                        stringResource(R.string.opt_blend_hint_none)
                                    else -> stringResource(R.string.opt_blend_hint_mixed)
                                },
                            )
                            // It runs on the swapper's own crop: gpen_bfr_256 and
                            // hyperswap_1a_256 declare the same template and size, so no
                            // second alignment is involved.
                            Text(stringResource(R.string.opt_enhancer_note, opts.pixelBoostLabel),
                                 style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                        }
                        "lipsync" -> {
                            OptionSlider(
                                stringResource(R.string.opt_weight), opts.lipSyncWeight,
                                { onOptsChange(opts.copy(lipSyncWeight = it)) },
                                hint = stringResource(R.string.opt_lip_sync_weight_hint),
                            )
                            // The Voice picker deliberately stays on the main screen: it is
                            // a REQUIRED input that gates the Swap button, not a knob, and
                            // a required input behind a gear is a required input nobody
                            // finds.
                        }
                        else -> {
                            FaceSwapperCard(opts, onOptsChange, openCard == "swapper",
                                            { onToggleCard("swapper") },
                                            inswapperAvailable = hasInswapper)
                            FaceMaskerCard(opts, onOptsChange, openCard == "masker",
                                           { onToggleCard("masker") })
                            FaceDetectorCard(opts, onOptsChange, openCard == "detector",
                                             { onToggleCard("detector") })
                            if (opts != SwapOptions()) {
                                TextButton(
                                    onClick = { onOptsChange(SwapOptions()) },
                                    modifier = Modifier.align(Alignment.End),
                                ) { Text(stringResource(R.string.swap_reset_defaults)) }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * The three pipeline stages -- face_swapper, face_enhancer, lip_syncer -- as the chip
 * rows upstream FaceFusion draws them in, sized to sit inside the target tile's settings
 * popup. It was a SectionCard on the page; moving it under the target tile's gear keeps
 * the stages beside the input they act on and takes ~160 dp off a fresh screen.
 */
@Composable
private fun ProcessorsPanel(
    opts: SwapOptions,
    onOptsChange: (SwapOptions) -> Unit,
    hasEnhancer: Boolean,
    hasLipSyncer: Boolean,
    hasTarget: Boolean,
    durationMs: Long,
    idle: Boolean,
    onRequestModel: (String, String) -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    // Two rows of two, upstream's shape. Three chips do not fit across a phone and
    // Row does not wrap -- it SQUEEZES, so labels lose their shape rather than
    // moving down, and these are upstream's identifiers.
    //
    // 8 dp both ways here, unlike the Material chips this replaces: a Surface has
    // no enforced 48 dp interactive box padding it out, so the spacing asked for
    // is the spacing seen.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // face_swapper is always on and cannot be turned off: this app IS the
            // swapper. It still gets a chip, because the row exists to say WHICH
            // stages run.
            ProcessorChip(
                name = stringResource(R.string.swap_proc_swapper),
                // Always installed -- missing() makes it a REQUIRED model, so the
                // app never reaches this row without it.
                model = "",
                installed = true, on = true, available = true,
                enabled = idle, onToggle = {}, onRequestModel = onRequestModel,
                onSettings = { onOpenSettings("swapper") },
            )
            ProcessorChip(
                name = stringResource(R.string.swap_proc_enhancer),
                model = "gpen",
                installed = hasEnhancer,
                on = opts.faceEnhance,
                available = true,
                enabled = idle,
                onToggle = { onOptsChange(opts.copy(faceEnhance = !opts.faceEnhance)) },
                onRequestModel = onRequestModel,
                onSettings = { onOpenSettings("enhancer") },
            )
        }
        // ⚠ `available` is false only once a PHOTO is picked. `durationMs > 0`
        // alone was false on an empty screen, so the chip greyed out the moment the
        // app opened and looked broken beside face_enhancer, which needs no target.
        // There is nothing to say no about until there is a target.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProcessorChip(
                name = stringResource(R.string.swap_proc_lip_syncer),
                // edtalk, matching hasLipSyncer -- wav2lip is not offered because
                // ffpipe no longer opens it.
                model = "edtalk",
                installed = hasLipSyncer,
                on = opts.lipSync,
                available = !hasTarget || durationMs > 0,
                enabled = idle,
                onToggle = { onOptsChange(opts.copy(lipSync = !opts.lipSync)) },
                onRequestModel = onRequestModel,
                onSettings = { onOpenSettings("lipsync") },
            )
        }
    }
}

@Composable
private fun ProcessorChip(
    /** The stage's label, as upstream names it (face_swapper…). */
    name: String,
    /** What the downloader calls this stage's model; "" for one always present. */
    model: String,
    installed: Boolean,
    on: Boolean,
    available: Boolean,
    /** False while a run owns the pipeline: the chip goes inert, not away. */
    enabled: Boolean,
    onToggle: () -> Unit,
    /** A stage whose model is missing asks for the download instead of toggling. */
    onRequestModel: (String, String) -> Unit,
    // The gear, drawn INSIDE the chip at its trailing edge. Null for a chip
    // with nothing to configure; also hidden while the model is missing, where
    // the chip's job is to offer the download and settings would be settings
    // for something that cannot run.
    onSettings: (() -> Unit)? = null,
) {
    val active = installed && on && available
    val clickable = enabled && (!installed || available)
    Surface(
        onClick = { if (installed) onToggle() else onRequestModel(name, model) },
        enabled = clickable,
        shape = RoundedCornerShape(8.dp),
        // The chip's fill matches the other controls (cards, buttons) -- the
        // theme's surface -- not the page background. Selection is carried by
        // the border (primary when a stage is ON, outlineVariant when off) and
        // the check disc, never by a fill.
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            // A single hairline in both states: the border is a separator, not
            // a selection bar -- selection reads from the primary colour, the
            // check disc and the bold label.
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.69f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            // Filled with the accent (text colour) now that the
                            // chip itself is page-coloured.
                            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.69f)
                            // A model that is not on the device gets a hollow
                            // disc, so "off" and "not installed" are not the
                            // same picture. Upstream has no such state.
                            !installed -> Color.Transparent
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                    .then(
                        if (!installed)
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline,
                                            CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Icon(Icons.Default.Check, null, Modifier.size(12.dp),
                         tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.69f))
                } else if (!installed) {
                    Icon(Icons.Default.Add, null, Modifier.size(12.dp),
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    active -> MaterialTheme.colorScheme.onBackground
                    !installed -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (active) FontWeight.SemiBold
                            else FontWeight.Normal,
                // ⚠ weight(fill = false) is what keeps every gear THE SAME
                // SIZE. Row does not wrap, it SQUEEZES, and with two chips
                // across a phone the squeeze landed on whichever child had no
                // weight -- the icon. So face_swapper and face_enhancer, which
                // share a row, drew a visibly smaller gear than lip_syncer,
                // which has its row to itself. A weighted child is measured
                // with what is LEFT after the unweighted ones, so the label now
                // absorbs the shortfall (it ellipsises) and the gear never
                // changes size. fill = false so a short label still does not
                // stretch the chip.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Its own clickable inside the chip's, which Compose resolves to the
            // innermost -- so the gear opens settings and does NOT also toggle
            // the stage underneath it. 22 dp of touch target inside a 36 dp
            // chip is below the 48 dp guideline, but a chip that grew to hold a
            // 48 dp box would no longer fit two across a phone, which is the
            // layout constraint this row is already built around.
            if (installed && onSettings != null) {
                HintIcon(stringResource(R.string.swap_proc_settings, name)) {
                    Icon(
                        Icons.Default.Settings,
                        stringResource(R.string.swap_proc_settings, name),
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable(enabled = enabled) { onSettings() }
                            .alpha(if (enabled) 1f else 0.31f),
                        tint = if (active) MaterialTheme.colorScheme.onBackground
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The model download, drawn over a preview pane.
 *
 * Only ever composed when the files are actually missing, so there is no button sitting
 * around inviting a 275 MB transfer nobody needs.
 *
 * Shared with [LiveScreen] rather than private to this file: Live is a tab, so it can be
 * the first screen a fresh install sees, and it needs the same offer. It briefly had a
 * plain Button of its own instead -- same onDownload, but none of the progress, the byte
 * counter, the error or the retry, so the two screens disagreed about what a download
 * looks like for no reason beyond where the composable happened to live.
 */
@Composable
fun DownloadOverlay(onDownload: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                ModelDownload.running -> {
                    Text(ModelDownload.currentName,
                         style = MaterialTheme.typography.bodyMedium,
                         fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ModelDownload.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dl_progress,
                            ModelDownload.doneBytes / 1048576,
                            ModelDownload.totalBytes / 1048576,
                            ModelDownload.fileIndex, ModelDownload.fileCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(stringResource(R.string.dl_models_required),
                         style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ModelDownload.error ?: stringResource(R.string.dl_not_on_device),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = if (ModelDownload.error != null)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onDownload, shape = RoundedCornerShape(14.dp)) {
                        Text(stringResource(if (ModelDownload.error != null) R.string.dl_retry
                                            else R.string.dl_download))
                    }
                }
            }
        }
    }
}
