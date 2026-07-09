package com.photoncam.ui.viewfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Range
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import androidx.camera.core.MeteringPointFactory
import com.photoncam.camera.CameraManager
import com.photoncam.camera.CameraParams
import com.photoncam.camera.LensInfo
import com.photoncam.film.FilmCatalog
import com.photoncam.film.FilmStock
import com.photoncam.processing.DateImprintColor
import com.photoncam.processing.DateImprintFont
import com.photoncam.processing.DateImprintPosition
import com.photoncam.processing.DateImprintSize
import com.photoncam.processing.DateImprintStyle
import com.photoncam.processing.LutProcessor
import com.photoncam.processing.PhotoProcessingWorker
import com.photoncam.utils.AppSettings
import com.photoncam.utils.GalleryExporter
import com.photoncam.utils.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.atan2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class GridMode(val label: String) {
    OFF("OFF"),
    THIRDS("3×3"),
    SQUARE("4×4"),
    NINES("9×9"),
    GOLDEN("PHI"),
    DIAGONAL("DIAG"),
}

/** Tap-to-focus indicator state. [focused] flips to true once AF converges. */
data class FocusPoint(val x: Float, val y: Float, val focused: Boolean = false)

data class ViewfinderUiState(
    val selectedFilm: FilmStock = FilmCatalog.default,
    val totalShotsTaken: Int = 0,        // cumulative lifetime counter
    val isCapturing: Boolean = false,
    val processingCount: Int = 0,
    val lastCapturedUri: Uri? = null,
    val latestGalleryUri: Uri? = null,   // last known gallery photo (persists across sessions)
    val favoriteFilmIds: Set<String> = emptySet(),
    val dateImprintEnabled: Boolean = true,
    val dateImprintStyle: DateImprintStyle = DateImprintStyle.CLASSIC,
    val dateImprintColor: DateImprintColor = DateImprintColor.AMBER,
    val dateImprintFont: DateImprintFont = DateImprintFont.LED,
    val dateImprintSize: DateImprintSize = DateImprintSize.MEDIUM,
    val dateImprintPosition: DateImprintPosition = DateImprintPosition.BOTTOM_RIGHT,
    val dateImprintGlow: Int = 100,
    val dateImprintBlur: Int = 50,
    val dateImprintOpacity: Int = 50,
    val dateImprintBlurRepeat: Int = 3,
    val showDateImprintMenu: Boolean = false,
    val lightLeakEnabled: Boolean = true,
    val error: String? = null,
    val flashEnabled: Boolean = false,
    val screenFlashActive: Boolean = false,
    val showFilmSelector: Boolean = false,
    val availableLenses: List<LensInfo> = emptyList(),
    val selectedLens: LensInfo? = null,
    val evIndex: Int = 0,
    val evRange: Range<Int>? = null,
    val evStep: Double = 1.0,
    val mainZoomRatio: Float = 1.0f,
    val focusPoint: FocusPoint? = null,
    val showSettingsMenu: Boolean = false,
    val focusDurationSeconds: Int = 5,
    val histogramEnabled: Boolean = false,
    val histogramData: FloatArray? = null,
    val cameraParamsEnabled: Boolean = false,
    val cameraParams: CameraParams? = null,
    val levelEnabled: Boolean = false,
    val levelAngle: Float = 0f,
    val gridMode: GridMode = GridMode.OFF,
)

// Processed JPEGs live in cache/processed only for the duration of a single doWork()
// (write → save to gallery → delete). Any file older than this is an orphan — either from
// an older app version that never cleaned up, or an interrupted run. Safe to sweep on launch.
private const val STALE_PROCESSED_TTL_MS = 60 * 60 * 1000L // 1 hour

@HiltViewModel
class ViewfinderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: CameraManager,
    private val workManager: WorkManager,
    private val galleryExporter: GalleryExporter,
    private val settingsRepository: SettingsRepository,
    private val lutProcessor: LutProcessor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewfinderUiState())
    val uiState: StateFlow<ViewfinderUiState> = _uiState.asStateFlow()

    private var evJob: Job? = null
    private var bindJob: Job? = null
    private var zoomJob: Job? = null
    private var focusJob: Job? = null
    private var savedLensId: String? = null

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Roll angle around the camera optical axis: 0° = portrait upright, ±90° = landscape
            val gx = event.values[0]
            val gy = event.values[1]
            // atan2(gx, gy): 0° = portrait upright, +90° = landscape CW, ±180° = upside-down
            val roll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            _uiState.update { it.copy(levelAngle = roll) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Track work IDs we've already reported to the UI to avoid duplicate updates.
    private val reportedWorkIds = mutableSetOf<UUID>()

    // On the first work-info emission we seed reportedWorkIds with all already-terminal
    // work so historical FAILED/SUCCEEDED records (persisted by WorkManager across restarts)
    // are never re-surfaced on launch. Only work that transitions to terminal *after* startup
    // is reported. Prevents the phantom "Processing failed" snackbar on every app launch.
    private var workHistorySeeded = false

    init {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            savedLensId = saved.selectedLensId
            _uiState.update { s ->
                s.copy(
                    selectedFilm = FilmCatalog.findById(saved.filmId) ?: FilmCatalog.default,
                    lightLeakEnabled = saved.lightLeakEnabled,
                    dateImprintEnabled = saved.dateImprintEnabled,
                    dateImprintStyle = saved.dateImprintStyle,
                    dateImprintColor = saved.dateImprintColor,
                    dateImprintFont = saved.dateImprintFont,
                    dateImprintSize = saved.dateImprintSize,
                    dateImprintPosition = saved.dateImprintPosition,
                    dateImprintGlow = saved.dateImprintGlow,
                    dateImprintBlur = saved.dateImprintBlur,
                    dateImprintOpacity = saved.dateImprintOpacity,
                    dateImprintBlurRepeat = saved.dateImprintBlurRepeat,
                    totalShotsTaken = saved.totalShotsTaken,
                    favoriteFilmIds = saved.favoriteFilmIds,
                    flashEnabled = saved.flashEnabled,
                    evIndex = saved.evIndex,
                    mainZoomRatio = saved.mainZoomRatio,
                    focusDurationSeconds = saved.focusDurationSeconds,
                    histogramEnabled = saved.histogramEnabled,
                    cameraParamsEnabled = saved.cameraParamsEnabled,
                    levelEnabled = saved.levelEnabled,
                    gridMode = runCatching { GridMode.valueOf(saved.gridMode) }.getOrDefault(GridMode.OFF),
                )
            }
            // Warm the LUT cache for the restored film so the first shot isn't slow.
            preloadLut(_uiState.value.selectedFilm)
            // Re-apply histogram enable state in case bindCamera() already ran
            cameraManager.setHistogramEnabled(saved.histogramEnabled)
            cameraManager.setCameraParamsEnabled(saved.cameraParamsEnabled)
            if (saved.levelEnabled) registerLevelSensor()

            // Re-apply saved zoom in case bindCamera() already ran before settings loaded
            // (race condition on cold start). Silently ignored if camera isn't bound yet —
            // bindCamera() will call effectiveZoomRatio() which now has the correct value.
            if (saved.mainZoomRatio != 1.0f) {
                cameraManager.setZoomRatio(saved.mainZoomRatio)
            }
        }
        // Collect histogram data from CameraManager and push to UI state
        viewModelScope.launch {
            cameraManager.histogramData.collect { data ->
                _uiState.update { it.copy(histogramData = data) }
            }
        }

        // Collect real-time camera params (shutter / ISO / aperture)
        viewModelScope.launch {
            cameraManager.cameraParams.collect { params ->
                _uiState.update { it.copy(cameraParams = params) }
            }
        }

        // Populate VIEW button with last gallery photo from previous sessions
        viewModelScope.launch(Dispatchers.IO) {
            val uri = galleryExporter.getLatestPhotoUri()
            if (uri != null) _uiState.update { it.copy(latestGalleryUri = uri) }
        }

        // Housekeeping: sweep orphaned processed JPEGs left in cache by older app versions
        // (which never deleted them). Only files older than the TTL are removed, so a
        // currently-running worker's freshly written file is never touched.
        viewModelScope.launch(Dispatchers.IO) {
            val processedDir = File(context.cacheDir, "processed")
            val cutoff = System.currentTimeMillis() - STALE_PROCESSED_TTL_MS
            processedDir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        }

        // Recover orphaned captures: any RAW still in filesDir/captures was never processed to
        // completion (the worker deletes the RAW on every terminal outcome). This happens if the
        // app was hard-killed (force-stop / task-killer / crash) mid-processing. Re-enqueue each
        // with REPLACE so processing resumes even if a stale work record is stuck; params come
        // from the sidecar written at capture time. RAWs without a sidecar can't be faithfully
        // reprocessed (pre-feature or corrupt) — delete them so they don't linger forever.
        viewModelScope.launch(Dispatchers.IO) {
            val capturesDir = File(context.filesDir, "captures")
            capturesDir.listFiles()?.forEach { raw ->
                if (!raw.isFile || !raw.name.startsWith("RAW_") || !raw.name.endsWith(".jpg")) return@forEach
                val input = PhotoProcessingWorker.readSidecar(raw)
                if (input == null) {
                    raw.delete()
                    return@forEach
                }
                workManager.enqueueUniqueWork(
                    "proc_${raw.name}",
                    ExistingWorkPolicy.REPLACE,
                    buildProcessingRequest(input),
                )
            }
        }

        // Physically delete finished (SUCCEEDED/FAILED/CANCELLED) work records so the
        // WorkManager DB doesn't grow unboundedly and stale failures stop accumulating.
        // Leaves ENQUEUED/RUNNING work intact.
        workManager.pruneWork()

        // Observe all PhotonCam processing work: update processingCount and surface
        // completed URIs. Uses LiveData.asFlow() so it works on all API levels.
        viewModelScope.launch {
            workManager.getWorkInfosByTagLiveData(PhotoProcessingWorker.WORK_TAG)
                .asFlow()
                .collect { workInfos ->
                    // Seed dedup with pre-existing terminal work on the first emission so
                    // historical failures/successes never surface on launch. init runs before
                    // any user capture, so emission #1 reflects only the persisted DB.
                    if (!workHistorySeeded) {
                        workHistorySeeded = true
                        workInfos.filter { it.state.isFinished }
                            .forEach { reportedWorkIds.add(it.id) }
                    }

                    val active = workInfos.count {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    _uiState.update { it.copy(processingCount = active) }

                    workInfos.filter {
                        it.state == WorkInfo.State.SUCCEEDED && it.id !in reportedWorkIds
                    }.forEach { info ->
                        reportedWorkIds.add(info.id)
                        val uriStr = info.outputData.getString(PhotoProcessingWorker.KEY_GALLERY_URI)
                        val uri = uriStr?.let { Uri.parse(it) }
                        if (uri != null) {
                            _uiState.update { it.copy(lastCapturedUri = uri, latestGalleryUri = uri) }
                            persistSettings()
                        }
                    }

                    workInfos.filter {
                        it.state == WorkInfo.State.FAILED && it.id !in reportedWorkIds
                    }.forEach { info ->
                        reportedWorkIds.add(info.id)
                        _uiState.update { it.copy(error = "Processing failed") }
                    }
                }
        }
    }

    private fun persistSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.save(
                AppSettings(
                    filmId = state.selectedFilm.id,
                    lightLeakEnabled = state.lightLeakEnabled,
                    selectedLensId = state.selectedLens?.id,
                    evIndex = state.evIndex,
                    dateImprintEnabled = state.dateImprintEnabled,
                    dateImprintStyle = state.dateImprintStyle,
                    dateImprintColor = state.dateImprintColor,
                    dateImprintFont = state.dateImprintFont,
                    dateImprintSize = state.dateImprintSize,
                    dateImprintPosition = state.dateImprintPosition,
                    dateImprintGlow = state.dateImprintGlow,
                    dateImprintBlur = state.dateImprintBlur,
                    dateImprintOpacity = state.dateImprintOpacity,
                    dateImprintBlurRepeat = state.dateImprintBlurRepeat,
                    totalShotsTaken = state.totalShotsTaken,
                    favoriteFilmIds = state.favoriteFilmIds,
                    flashEnabled = state.flashEnabled,
                    mainZoomRatio = state.mainZoomRatio,
                    focusDurationSeconds = state.focusDurationSeconds,
                    histogramEnabled = state.histogramEnabled,
                    cameraParamsEnabled = state.cameraParamsEnabled,
                    levelEnabled = state.levelEnabled,
                    gridMode = state.gridMode.name,
                )
            )
        }
    }

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // Zoom-ratio lens change: camera is already bound to the logical back camera.
        // Just update the zoom ratio — no rebind needed, which would stutter the preview.
        // Guard: skip this fast-path if the front camera is currently bound; in that case
        // we must rebind to DEFAULT_BACK_CAMERA before setting a zoom ratio.
        val pendingLens = _uiState.value.selectedLens
        if (pendingLens?.zoomRatio != null && cameraManager.isBound &&
            cameraManager.currentLensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
            viewModelScope.launch {
                // Use effectiveZoomRatio() so zoom_main respects the slider (mainZoomRatio)
                // rather than the fixed 1.0f stored in LensInfo.zoomRatio.
                cameraManager.setZoomRatio(effectiveZoomRatio())
                    .onFailure { e -> _uiState.update { it.copy(error = "Zoom failed: ${e.message}") } }
            }
            return
        }

        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            // For zoom-ratio-based devices (most modern phones) the selector is always
            // DEFAULT_BACK_CAMERA. Physical-camera lenses (some OEM phones) carry a
            // specific selector and trigger a real rebind.
            val lensToUse = _uiState.value.selectedLens?.takeIf { it.zoomRatio == null }

            cameraManager.bindToLifecycle(lifecycleOwner, previewView, lensToUse)
                .onSuccess { exposureState ->
                    // Keep the saved evIndex — the camera resets EV to 0 on every bind,
                    // so reading exposureState.exposureCompensationIndex would overwrite
                    // the user's saved value with 0 on every resume/lens-switch.
                    val savedEvIndex = _uiState.value.evIndex
                    val range = exposureState.exposureCompensationRange
                    val clampedEvIndex = savedEvIndex.coerceIn(range.lower, range.upper)
                    _uiState.update {
                        it.copy(
                            evIndex = clampedEvIndex,
                            evRange = range,
                            evStep = exposureState.exposureCompensationStep.toDouble(),
                        )
                    }
                    // Re-apply saved EV to the newly-bound camera hardware.
                    if (clampedEvIndex != 0) {
                        cameraManager.setExposureCompensation(clampedEvIndex)
                    }

                    // Discover zoom-ratio lenses now that the camera is bound and
                    // ZoomState is available. Skip if lenses are already populated.
                    if (_uiState.value.availableLenses.isEmpty()) {
                        val zoomLenses = cameraManager.discoverZoomLenses()
                        if (zoomLenses.isNotEmpty()) {
                            val restored = savedLensId?.let { id -> zoomLenses.firstOrNull { it.id == id } }
                            val default = restored
                                ?: zoomLenses.firstOrNull { it.label == "1×" }
                                ?: zoomLenses.first()
                            _uiState.update { s ->
                                s.copy(
                                    availableLenses = zoomLenses,
                                    selectedLens = s.selectedLens ?: default,
                                )
                            }
                            // Apply zoom — for zoom_main, honour the slider (mainZoomRatio);
                            // for other lenses, apply the lens zoom ratio.
                            cameraManager.setZoomRatio(effectiveZoomRatio())
                        }
                    } else {
                        // Re-binding after a full rebind (e.g. switching back from front camera):
                        // re-apply the zoom ratio for the selected lens so the preview matches
                        // the highlighted pill in the lens selector.
                        cameraManager.setZoomRatio(effectiveZoomRatio())
                    }

                    // Re-apply hardware flash mode after each bind (ImageCapture is recreated).
                    // Only set on back camera — front camera uses screen flash instead.
                    val isFront = _uiState.value.selectedLens?.id == "camera_front"
                    if (!isFront) {
                        cameraManager.setFlashEnabled(_uiState.value.flashEnabled)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Camera init failed: ${e.message}") }
                }
        }
    }

    /** Effective zoom ratio to apply to the camera for the current lens selection. */
    private fun effectiveZoomRatio(): Float {
        val state = _uiState.value
        return if (state.selectedLens?.id == "zoom_main") state.mainZoomRatio
               else state.selectedLens?.zoomRatio ?: 1.0f
    }

    /** Called while the slider is dragged — updates live zoom, no persist. */
    fun setMainZoomRatio(ratio: Float) {
        val clamped = ratio.coerceIn(1.0f, 8.0f)
        _uiState.update { it.copy(mainZoomRatio = clamped) }
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch {
            cameraManager.setZoomRatio(clamped)
                .onFailure { e ->
                    val msg = e.message ?: ""
                    // OperationCanceledException fires on every rapid slider drag — suppress it.
                    if (!msg.contains("OperationCanceled") && !msg.contains("Camera not bound")) {
                        _uiState.update { it.copy(error = "Zoom failed: $msg") }
                    }
                }
        }
    }

    /** Called when the user releases the slider — persists the value. */
    fun commitMainZoomRatio() {
        persistSettings()
    }

    fun selectLens(lens: LensInfo) {
        if (_uiState.value.selectedLens?.id == lens.id) return
        // Returning to 1× resets the zoom slider to 1× (per spec)
        val newZoom = if (lens.id == "zoom_main") 1.0f else _uiState.value.mainZoomRatio
        _uiState.update { it.copy(selectedLens = lens, mainZoomRatio = newZoom) }
        persistSettings()
        // Zoom-ratio lenses: the LaunchedEffect(selectedLens?.id) in ViewfinderScreen
        // calls bindCamera(), which detects isBound + zoomRatio != null and routes to
        // setZoomRatio() without rebinding. Physical lenses trigger a full rebind.
    }

    fun selectNextLens() {
        val lenses = _uiState.value.availableLenses
        if (lenses.size < 2) return
        val current = _uiState.value.selectedLens ?: return
        val idx = lenses.indexOfFirst { it.id == current.id }
        selectLens(lenses[(idx + 1) % lenses.size])
    }

    fun selectPrevLens() {
        val lenses = _uiState.value.availableLenses
        if (lenses.size < 2) return
        val current = _uiState.value.selectedLens ?: return
        val idx = lenses.indexOfFirst { it.id == current.id }
        selectLens(lenses[(idx - 1 + lenses.size) % lenses.size])
    }

    fun adjustExposure(delta: Int) {
        val state = _uiState.value
        val range = state.evRange ?: return
        val stepsPerThird = if (state.evStep > 0.0) {
            ((1.0 / 3.0) / state.evStep + 0.5).toInt().coerceAtLeast(1)
        } else 1
        val newIndex = (state.evIndex + delta * stepsPerThird).coerceIn(range.lower, range.upper)
        if (newIndex == state.evIndex) return
        _uiState.update { it.copy(evIndex = newIndex) }

        evJob?.cancel()
        evJob = viewModelScope.launch {
            cameraManager.setExposureCompensation(newIndex)
                .onSuccess { persistSettings() }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    if (!msg.contains("Camera not bound") &&
                        !msg.contains("Canceled by another") &&
                        !msg.contains("OperationCanceled")) {
                        _uiState.update { it.copy(error = "EV adjust failed: $msg") }
                    }
                }
        }
    }

    fun toggleDateImprintMenu() {
        _uiState.update { it.copy(showDateImprintMenu = !it.showDateImprintMenu) }
    }

    fun setDateImprintEnabled(enabled: Boolean) {
        _uiState.update { it.copy(dateImprintEnabled = enabled) }
        persistSettings()
    }

    fun setDateImprintStyle(style: DateImprintStyle) {
        _uiState.update { it.copy(dateImprintStyle = style) }
        persistSettings()
    }

    fun setDateImprintColor(color: DateImprintColor) {
        _uiState.update { it.copy(dateImprintColor = color) }
        persistSettings()
    }

    fun setDateImprintFont(font: DateImprintFont) {
        _uiState.update { it.copy(dateImprintFont = font) }
        persistSettings()
    }

    fun setDateImprintSize(size: DateImprintSize) {
        _uiState.update { it.copy(dateImprintSize = size) }
        persistSettings()
    }

    fun setDateImprintPosition(position: DateImprintPosition) {
        _uiState.update { it.copy(dateImprintPosition = position) }
        persistSettings()
    }

    fun setDateImprintGlow(glow: Int) {
        _uiState.update { it.copy(dateImprintGlow = glow.coerceIn(0, 100)) }
        persistSettings()
    }

    fun setDateImprintBlur(blur: Int) {
        _uiState.update { it.copy(dateImprintBlur = blur.coerceIn(0, 100)) }
        persistSettings()
    }

    fun setDateImprintOpacity(opacity: Int) {
        _uiState.update { it.copy(dateImprintOpacity = opacity.coerceIn(0, 100)) }
        persistSettings()
    }

    fun setDateImprintBlurRepeat(repeat: Int) {
        _uiState.update { it.copy(dateImprintBlurRepeat = repeat.coerceIn(0, 20)) }
        persistSettings()
    }

    fun toggleLightLeak() {
        _uiState.update { it.copy(lightLeakEnabled = !it.lightLeakEnabled) }
        persistSettings()
    }

    fun selectFilm(film: FilmStock) {
        _uiState.update { it.copy(selectedFilm = film, showFilmSelector = false) }
        persistSettings()
        // Warm the LUT cache now so the next shot doesn't pay the decode on the critical path.
        preloadLut(film)
    }

    private fun preloadLut(film: FilmStock) {
        val resId = film.lutResId ?: return
        viewModelScope.launch { lutProcessor.preload(resId) }
    }

    fun toggleFilmSelector() {
        _uiState.update { it.copy(showFilmSelector = !it.showFilmSelector) }
    }

    fun toggleFlash() {
        val enabled = !_uiState.value.flashEnabled
        _uiState.update { it.copy(flashEnabled = enabled) }
        val isFront = _uiState.value.selectedLens?.id == "camera_front"
        if (!isFront) cameraManager.setFlashEnabled(enabled)
        persistSettings()
    }

    fun toggleFavorite(filmId: String) {
        val current = _uiState.value.favoriteFilmIds
        val updated = if (filmId in current) current - filmId else current + filmId
        _uiState.update { it.copy(favoriteFilmIds = updated) }
        persistSettings()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun reapplyZoom() {
        val ratio = effectiveZoomRatio()
        viewModelScope.launch {
            cameraManager.setZoomRatio(ratio)
        }
    }

    fun reapplyEv() {
        val index = _uiState.value.evIndex
        if (index == 0) return
        viewModelScope.launch {
            cameraManager.setExposureCompensation(index)
        }
    }

    fun tapToFocus(x: Float, y: Float, factory: MeteringPointFactory) {
        val meteringPoint = factory.createPoint(x, y)
        val duration = _uiState.value.focusDurationSeconds
        focusJob?.cancel()
        _uiState.update { it.copy(focusPoint = FocusPoint(x, y, focused = false)) }
        focusJob = viewModelScope.launch {
            cameraManager.tapToFocus(meteringPoint, duration)
                .onSuccess {
                    _uiState.update { s -> s.copy(focusPoint = s.focusPoint?.copy(focused = true)) }
                }
            // duration == 0 means infinite: keep bracket until next tap
            if (duration > 0) {
                delay(duration * 1000L)
                _uiState.update { it.copy(focusPoint = null) }
            }
        }
    }

    fun toggleSettingsMenu() {
        _uiState.update { it.copy(showSettingsMenu = !it.showSettingsMenu) }
    }

    fun setFocusDuration(seconds: Int) {
        // 0 = infinite (∞ position on slider), 1–30 = seconds
        _uiState.update { it.copy(focusDurationSeconds = seconds.coerceIn(0, 30)) }
    }

    fun toggleHistogram() {
        val enabled = !_uiState.value.histogramEnabled
        _uiState.update { it.copy(histogramEnabled = enabled) }
        cameraManager.setHistogramEnabled(enabled)
        persistSettings()
    }

    fun toggleCameraParams() {
        val enabled = !_uiState.value.cameraParamsEnabled
        _uiState.update { it.copy(cameraParamsEnabled = enabled) }
        cameraManager.setCameraParamsEnabled(enabled)
        persistSettings()
    }

    fun setGridMode(mode: GridMode) {
        _uiState.update { it.copy(gridMode = mode) }
        persistSettings()
    }

    fun toggleLevel() {
        val enabled = !_uiState.value.levelEnabled
        _uiState.update { it.copy(levelEnabled = enabled) }
        if (enabled) registerLevelSensor() else sensorManager.unregisterListener(levelListener)
        persistSettings()
    }

    private fun registerLevelSensor() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        sensorManager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun saveAndCloseSettings() {
        persistSettings()
        _uiState.update { it.copy(showSettingsMenu = false) }
    }

    fun clearLastCapture() {
        _uiState.update { it.copy(lastCapturedUri = null) }
    }

    /** Builds the processing work request (expedited + exponential backoff) from the given input. */
    private fun buildProcessingRequest(input: Data): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PhotoProcessingWorker>()
            .addTag(PhotoProcessingWorker.WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(input)
            .build()

    @OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun capture() {
        val state = _uiState.value
        if (state.isCapturing) return

        val film = state.selectedFilm
        val dateEnabled = state.dateImprintEnabled
        val dateStyle = state.dateImprintStyle
        val dateColor = state.dateImprintColor
        val dateFont = state.dateImprintFont
        val dateSize = state.dateImprintSize
        val datePosition = state.dateImprintPosition
        val dateGlow = state.dateImprintGlow
        val dateBlur = state.dateImprintBlur
        val dateOpacity = state.dateImprintOpacity
        val dateBlurRepeat = state.dateImprintBlurRepeat
        val lightLeak = state.lightLeakEnabled
        val isFrontCamera = state.selectedLens?.id == "camera_front"
        val useScreenFlash = state.flashEnabled && isFrontCamera

        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, error = null) }

            if (useScreenFlash) {
                // Lock AE BEFORE showing the flash so the camera keeps the pre-flash
                // exposure metered for the ambient scene.  Without this, AE sees the
                // bright white screen and stops down, producing a dark capture.
                cameraManager.setAeLock(true)
                _uiState.update { it.copy(screenFlashActive = true) }
                delay(300) // wait for display hardware to reach max brightness
            }

            cameraManager.takePicture()
                .onSuccess { rawFile ->
                    if (useScreenFlash) {
                        cameraManager.setAeLock(false)
                        _uiState.update { it.copy(screenFlashActive = false) }
                    }
                    val newTotal = _uiState.value.totalShotsTaken + 1
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            totalShotsTaken = newTotal,
                        )
                    }
                    persistSettings()

                    // Enqueue processing as a WorkManager task so it survives app close.
                    val inputData = workDataOf(
                        PhotoProcessingWorker.KEY_RAW_FILE_PATH to rawFile.absolutePath,
                        PhotoProcessingWorker.KEY_FILM_ID to film.id,
                        PhotoProcessingWorker.KEY_DATE_IMPRINT_ENABLED to dateEnabled,
                        PhotoProcessingWorker.KEY_DATE_STYLE to dateStyle.name,
                        PhotoProcessingWorker.KEY_DATE_COLOR to dateColor.name,
                        PhotoProcessingWorker.KEY_DATE_FONT to dateFont.name,
                        PhotoProcessingWorker.KEY_DATE_SIZE to dateSize.name,
                        PhotoProcessingWorker.KEY_DATE_POSITION to datePosition.name,
                        PhotoProcessingWorker.KEY_DATE_GLOW to dateGlow,
                        PhotoProcessingWorker.KEY_DATE_BLUR to dateBlur,
                        PhotoProcessingWorker.KEY_DATE_OPACITY to dateOpacity,
                        PhotoProcessingWorker.KEY_DATE_BLUR_REPEAT to dateBlurRepeat,
                        PhotoProcessingWorker.KEY_LIGHT_LEAK_ENABLED to lightLeak,
                    )
                    // Persist params next to the RAW so an orphaned capture (app hard-killed
                    // mid-processing) can be faithfully reprocessed on next launch.
                    PhotoProcessingWorker.writeSidecar(rawFile, inputData)
                    workManager.enqueueUniqueWork(
                        "proc_${rawFile.name}",
                        ExistingWorkPolicy.KEEP,
                        buildProcessingRequest(inputData),
                    )
                }
                .onFailure { e ->
                    if (useScreenFlash) {
                        cameraManager.setAeLock(false)
                        _uiState.update { it.copy(screenFlashActive = false) }
                    }
                    _uiState.update { it.copy(isCapturing = false, error = "Capture failed: ${e.message}") }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.shutdown()
        sensorManager.unregisterListener(levelListener)
    }
}
