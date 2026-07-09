package com.photoncam.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.ExposureState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class CameraParams(
    val shutterNs: Long,   // SENSOR_EXPOSURE_TIME in nanoseconds
    val iso: Int,          // SENSOR_SENSITIVITY
    val aperture: Float,   // LENS_APERTURE  (f-number)
)

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    @Volatile private var camera: Camera? = null
    private var cachedProvider: ProcessCameraProvider? = null

    // ── Camera params (shutter / ISO / aperture) ──────────────────────────────
    private val _cameraParams = MutableStateFlow<CameraParams?>(null)
    val cameraParams: StateFlow<CameraParams?> = _cameraParams.asStateFlow()
    @Volatile private var cameraParamsEnabled = false
    private var lastParamsMs = 0L

    // ── Histogram ─────────────────────────────────────────────────────────────
    private val _histogramData = MutableStateFlow<FloatArray?>(null)
    val histogramData: StateFlow<FloatArray?> = _histogramData.asStateFlow()
    @Volatile private var histogramEnabled = false
    private var lastHistogramMs = 0L  // throttle to ~10 fps

    val isBound: Boolean get() = camera != null
    val currentLensFacing: Int? get() = camera?.cameraInfo?.lensFacing

    // ── Orientation tracking ──────────────────────────────────────────────────
    //
    // CameraX's ImageCapture.targetRotation defaults to Surface.ROTATION_0 and
    // never changes on its own. Without updating it, the EXIF orientation tag is
    // always written as "portrait" regardless of how the phone is held, so landscape
    // photos appear rotated after processing. The OrientationEventListener (backed
    // by the accelerometer) fires as the device rotates and keeps targetRotation
    // in sync with the real physical orientation.
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when {
                orientation < 45 || orientation >= 315 -> Surface.ROTATION_0   // portrait
                orientation < 135                      -> Surface.ROTATION_270  // landscape right
                orientation < 225                      -> Surface.ROTATION_180  // upside-down portrait
                else                                   -> Surface.ROTATION_90   // landscape left
            }
            imageCapture?.targetRotation = rotation
        }
    }

    // ── Lens enumeration (zoom-ratio based) ───────────────────────────────────
    //
    // Modern Android phones (Pixel, Samsung, etc.) expose rear cameras as a
    // single logical multi-camera. CameraX's availableCameraInfos often returns
    // only that one logical camera, so the old approach of enumerating back cameras
    // and rebinding to each physical camera ID never found more than 1 lens.
    //
    // Instead, we use the logical camera's ZoomState (min/max ratio) together with
    // Camera2's physical camera characteristics to infer optical zoom breakpoints.
    // Switching lens = setZoomRatio() on the already-bound logical camera; no rebind.

    /**
     * Discovers available lenses after the camera is bound.
     * Uses ZoomState.minZoomRatio for ultrawide and Camera2 physical camera
     * enumeration to detect telephoto.  Must be called on the main thread.
     */
    fun discoverZoomLenses(): List<LensInfo> {
        val cam = camera ?: return emptyList()
        val zoomState = cam.cameraInfo.zoomState.value ?: return emptyList()
        val minRatio = zoomState.minZoomRatio

        // Count distinct back-camera focal lengths via Camera2 for telephoto detection.
        // We include all back-facing cameras (physical + logical) and deduplicate by FL
        // value so that a logical camera reporting the same FL as a physical one doesn't
        // inflate the count. This avoids needing the API-28-only LOGICAL_MULTI_CAMERA_PHYSICAL_IDS.
        val cam2 = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
        val physicalBackFls: List<Float> = cam2.cameraIdList.mapNotNull { id ->
            runCatching {
                val chars = cam2.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK)
                    return@runCatching null
                chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            }.getOrNull()
        }.sortedBy { it }.distinctBy { "%.2f".format(it) }

        val lenses = mutableListOf<LensInfo>()

        // Ultrawide: present when the logical camera can zoom out below 1×
        if (minRatio < 0.95f) {
            lenses.add(
                LensInfo(
                    id = "zoom_wide",
                    label = "%.1f×".format(minRatio),
                    selector = CameraSelector.DEFAULT_BACK_CAMERA,
                    zoomRatio = minRatio,
                )
            )
        }

        // Main: always 1×
        lenses.add(
            LensInfo(
                id = "zoom_main",
                label = "1×",
                selector = CameraSelector.DEFAULT_BACK_CAMERA,
                zoomRatio = 1.0f,
            )
        )

        // Telephoto: present when there are 3+ physical back cameras and the longest
        // focal length is at least 1.5× the median (distinguishes tele from main).
        if (physicalBackFls.size >= 3) {
            val medianFl = physicalBackFls[physicalBackFls.size / 2]
            val teleFl = physicalBackFls.last()
            if (teleFl / medianFl >= 1.5f) {
                val teleRatio = (teleFl / medianFl).roundToInt().toFloat().coerceAtLeast(2f)
                lenses.add(
                    LensInfo(
                        id = "zoom_tele",
                        label = "${teleRatio.toInt()}×",
                        selector = CameraSelector.DEFAULT_BACK_CAMERA,
                        zoomRatio = teleRatio,
                    )
                )
            }
        }

        // Front camera: always appended last so back lenses come first in the ring
        val hasFront = cam2.cameraIdList.any { id ->
            runCatching {
                cam2.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        }
        if (hasFront) {
            lenses.add(
                LensInfo(
                    id = "camera_front",
                    label = "Front",
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA,
                    zoomRatio = null,  // Front camera requires a full rebind
                )
            )
        }

        // Only return if there are actually multiple options
        return if (lenses.size > 1) lenses else emptyList()
    }

    // ── Camera binding ────────────────────────────────────────────────────────

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    suspend fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensInfo: LensInfo? = null,
    ): Result<ExposureState> {
        val result = withContext(Dispatchers.Main) {
            runCatching {
                val cameraProvider = getCameraProvider()

                previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            if (!cameraParamsEnabled) return
                            val now = System.currentTimeMillis()
                            if (now - lastParamsMs < 200L) return
                            lastParamsMs = now
                            val shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                            val aperture = result.get(CaptureResult.LENS_APERTURE) ?: return
                            _cameraParams.value = CameraParams(shutterNs, iso, aperture)
                        }
                    }
                )
                val preview = previewBuilder.build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                // Cap capture resolution at 4096px on the long edge.
                // Larger sensors (e.g. 50 MP) produce bitmaps that overflow the
                // processing heap; anything above ~12 MP gives no visible benefit
                // after film simulation and JPEG compression.
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(4096, 3072),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    )
                    .build()

                // For zoom-ratio lenses (zoomRatio != null), the selector is always
                // DEFAULT_BACK_CAMERA — the logical multi-camera handles switching.
                val selector = lensInfo?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA

                // Clear before unbind so any in-flight EV futures fail fast
                camera = null
                cameraProvider.unbindAll()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(320, 240),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { image ->
                            try {
                                if (histogramEnabled) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastHistogramMs >= 100L) {
                                        lastHistogramMs = now
                                        _histogramData.value = computeLuminanceHistogram(image)
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }

                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, selector, preview, imageCapture!!, imageAnalysis,
                )

                // Start tracking device orientation so targetRotation stays current.
                // enable() is safe to call multiple times; a no-op if already enabled.
                orientationListener.enable()

                camera!!.cameraInfo.exposureState
            }
        }
        // runCatching inside withContext catches CancellationException.
        // Re-throw it so bindJob cancellation propagates silently instead of showing an error.
        result.exceptionOrNull()
            ?.takeIf { it is kotlinx.coroutines.CancellationException }
            ?.let { throw it }
        return result
    }

    // ── Zoom ratio ────────────────────────────────────────────────────────────

    suspend fun setZoomRatio(ratio: Float): Result<Unit> {
        val result = runCatching {
            val ctrl = camera?.cameraControl ?: error("Camera not bound")
            suspendCancellableCoroutine { cont ->
                val future = ctrl.setZoomRatio(ratio)
                future.addListener({
                    if (!cont.isActive) return@addListener
                    try {
                        future.get()
                        cont.resume(Unit)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, cameraExecutor)
            }
        }
        result.exceptionOrNull()
            ?.takeIf { it is kotlinx.coroutines.CancellationException }
            ?.let { throw it }
        return result
    }

    // ── Exposure compensation ─────────────────────────────────────────────────

    fun getEvRange(): Range<Int>? =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange

    fun getEvStep(): Double =
        camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toDouble() ?: 1.0

    fun getCurrentEvIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    suspend fun setExposureCompensation(index: Int): Result<Int> {
        val result = runCatching {
            val ctrl = camera?.cameraControl ?: error("Camera not bound")
            suspendCancellableCoroutine { cont ->
                val future = ctrl.setExposureCompensationIndex(index)
                future.addListener({
                    if (!cont.isActive) return@addListener
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, cameraExecutor)
            }
        }
        // runCatching catches CancellationException, breaking structured concurrency.
        // Re-throw it so the caller's coroutine cancels silently instead of showing an error.
        result.exceptionOrNull()
            ?.takeIf { it is kotlinx.coroutines.CancellationException }
            ?.let { throw it }
        return result
    }

    // ── Flash ─────────────────────────────────────────────────────────────────

    // ── AE lock (screen flash) ────────────────────────────────────────────────
    //
    // Lock auto-exposure before showing the screen flash so the camera does not
    // stop down in response to the bright white screen.  The pre-flash exposure
    // (metered for the ambient scene) is preserved for the capture, which gives
    // the correct exposure once the white screen is illuminating the subject.
    // Cameras that do not support AE lock silently ignore the request.

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setAeLock(locked: Boolean) {
        val ctrl = camera?.cameraControl ?: return
        Camera2CameraControl.from(ctrl).captureRequestOptions =
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                .build()
    }

    fun setFlashEnabled(enabled: Boolean) {
        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    suspend fun takePicture(): Result<File> = withContext(Dispatchers.IO) {
        val capture = imageCapture
            ?: return@withContext Result.failure(IllegalStateException("Camera not bound"))

        val outputFile = createOutputFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(Result.success(outputFile))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun getCameraProvider(): ProcessCameraProvider {
        cachedProvider?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    val provider = future.get()
                    cachedProvider = provider
                    cont.resume(provider)
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    private fun createOutputFile(): File {
        // Millisecond precision so two shots in the same second get distinct filenames
        // (the filename also keys the unique processing work + its params sidecar).
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        // Persistent internal storage (filesDir), NOT cacheDir: the processing worker
        // runs deferred and must still find this RAW after the app is backgrounded/killed.
        // cacheDir is OS-evictable under storage pressure and would vanish before the worker runs.
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        return File(dir, "RAW_$ts.jpg")
    }

    // ── Histogram ─────────────────────────────────────────────────────────────

    fun setCameraParamsEnabled(enabled: Boolean) {
        cameraParamsEnabled = enabled
        if (!enabled) _cameraParams.value = null
    }

    fun setHistogramEnabled(enabled: Boolean) {
        histogramEnabled = enabled
        if (!enabled) _histogramData.value = null
    }

    private fun computeLuminanceHistogram(image: ImageProxy): FloatArray {
        val plane = image.planes[0]   // Y plane (luminance)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val counts = IntArray(256)
        var rowStart = 0
        for (y in 0 until height) {
            var i = rowStart
            for (x in 0 until width) {
                counts[buffer[i].toInt() and 0xFF]++
                i += pixelStride
            }
            rowStart += rowStride
        }
        val max = counts.max().toFloat().coerceAtLeast(1f)
        return FloatArray(256) { counts[it] / max }
    }

    // ── Tap-to-focus ──────────────────────────────────────────────────────────

    suspend fun tapToFocus(meteringPoint: MeteringPoint, durationSeconds: Int): Result<Unit> {
        val result = runCatching {
            val ctrl = camera?.cameraControl ?: error("Camera not bound")
            val action = FocusMeteringAction.Builder(meteringPoint)
                .apply {
                    if (durationSeconds > 0) setAutoCancelDuration(durationSeconds.toLong(), TimeUnit.SECONDS)
                    else disableAutoCancel()   // 0 = hold focus indefinitely
                }
                .build()
            suspendCancellableCoroutine { cont ->
                val future = ctrl.startFocusAndMetering(action)
                future.addListener({
                    if (!cont.isActive) return@addListener
                    try { future.get(); cont.resume(Unit) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                }, cameraExecutor)
            }
        }
        result.exceptionOrNull()
            ?.takeIf { it is kotlinx.coroutines.CancellationException }
            ?.let { throw it }
        return result
    }

    fun shutdown() {
        orientationListener.disable()
        // Do NOT shut down cameraExecutor here: CameraManager is a @Singleton (process-lifetime)
        // but shutdown() is called from ViewModel.onCleared() (screen-lifetime). Terminating the
        // executor kills it for the whole process, so the next capture in the same process has its
        // CameraX result dispatched to a dead executor → RejectedExecutionException → crash. The
        // single-thread executor lives with the singleton; the OS reclaims it on process death.
    }
}
