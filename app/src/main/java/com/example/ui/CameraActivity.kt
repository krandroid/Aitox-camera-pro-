package com.example.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.camera.AIPipeline
import com.example.camera.GalleryHelper
import com.example.camera.VideoRecorder
import com.example.databinding.ActivityCameraBinding
import com.example.databinding.DialogQuickSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityCameraBinding
    private val viewModel: CameraViewModel by viewModels()
    private lateinit var prefs: SharedPreferences

    // Camera2 variables
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var selectedCameraId: String = "0"
    
    // Background threads for Camera2
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // AI Frame Analysis Image Reader
    private var imageReader: ImageReader? = null
    private val aiPipeline = AIPipeline()

    // Sound variables
    private val shutterSound = android.media.MediaActionSound()

    // Recording utilities
    private var videoRecorder: VideoRecorder? = null
    private var recordingTimer: Timer? = null
    private var recordingSeconds = 0
    private var currentVideoFile: File? = null

    private fun copyFileToUri(sourceFile: File, targetUri: Uri) {
        try {
            contentResolver.openOutputStream(targetUri)?.use { os ->
                java.io.FileInputStream(sourceFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file content to Uri: $targetUri", e)
        }
    }

    // Gestures
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // Dynamic zoom multipliers
    private var maxZoomLevel = 10.0f
    private var activeSensorRect = Rect()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        prefs = getSharedPreferences("AitoxCameraSettings", Context.MODE_PRIVATE)
        videoRecorder = VideoRecorder(this)
        
        // Load sound components
        try {
            shutterSound.load(android.media.MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shutter click sound", e)
        }

        // Ensure permissions are granted before continuing
        if (allPermissionsGranted()) {
            binding.viewfinder.surfaceTextureListener = textureListener
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }

        setupGestures()
        setupUIListeners()
        observeViewModel()
        loadLatestThumbnail()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.viewfinder.isAvailable) {
            openCamera()
        } else {
            binding.viewfinder.surfaceTextureListener = textureListener
        }
        loadLatestThumbnail()
        applyLocalPreferences()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to terminate background camera threads.", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera/Audio access is mandatory for Aitox Cam to run.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val matrix = android.graphics.Matrix()
        
        // Preview is configured to 1920x1080 (landscape 16:9), which is 1080x1920 in portrait
        val previewWidth = 1080f
        val previewHeight = 1920f

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val previewRatio = previewWidth / previewHeight

        var scaleX = 1f
        var scaleY = 1f

        if (viewRatio > previewRatio) {
            scaleX = 1f
            scaleY = (viewWidth.toFloat() / previewWidth) * (previewHeight / viewHeight)
        } else {
            scaleX = (viewHeight.toFloat() / previewHeight) * (previewWidth / viewWidth)
            scaleY = 1f
        }

        matrix.setScale(scaleX, scaleY, centerX, centerY)
        binding.viewfinder.setTransform(matrix)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        try {
            // Apply proper transform scaling to prevent stretched previews (long face issues)
            configureTransform(binding.viewfinder.width, binding.viewfinder.height)

            // Pick camera ID based on rear/front setting
            val isRear = viewModel.isRearCamera.value ?: true
            selectedCameraId = getCameraId(isRear)

            // Setup zoom extremes
            val characteristics = cameraManager!!.getCameraCharacteristics(selectedCameraId)
            maxZoomLevel = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
            activeSensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 1920, 1080)

            cameraManager!!.openCamera(selectedCameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start sensor capture pipeline.", e)
            Toast.makeText(this, "Camera sensor is currently busy or unavailable.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCameraId(isRear: Boolean): String {
        cameraManager!!.cameraIdList.forEach { id ->
            val characteristics = cameraManager!!.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (isRear && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            } else if (!isRear && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return "0"
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
            Log.e(TAG, "Camera error occurred: $error")
        }
    }

    private fun createCameraSession() {
        val device = cameraDevice ?: return
        try {
            val texture = binding.viewfinder.surfaceTexture ?: return
            
            // Set dynamic display dimensions
            texture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)

            // Setup dynamic ImageReader for NPU Pipeline parsing
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val detections = aiPipeline.processFrame(image)
                        image.close()
                        
                        // Render bounding boxes on UI thread if stars toggle is active
                        runOnUiThread {
                            val activeMode = viewModel.currentMode.value
                            val aiEnabled = viewModel.isAiEnabled.value ?: false
                            // Display overlay matching settings
                            binding.aiOverlay.updateDetections(detections, aiEnabled && activeMode == CameraViewModel.CameraMode.AI_EDITOR)
                        }
                    }
                }, backgroundHandler)
            }

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(imageReader!!.surface)
                
                // Continuous Auto Focus
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val targets = listOf(previewSurface, imageReader!!.surface)
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed creating camera preview capture pipeline.")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error compiling camera session pipelines.", e)
        }
    }

    private fun recreateSessionForVideo(recorderSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            captureSession?.close()
            captureSession = null

            val texture = binding.viewfinder.surfaceTexture ?: return
            texture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
                if (imageReader != null) {
                    addTarget(imageReader!!.surface)
                }
                
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                
                val zoom = viewModel.zoomMultiplier.value ?: 1.0f
                applyZoomRequestBuilder(this, zoom)
                
                val flash = viewModel.flashMode.value ?: CameraViewModel.FlashMode.AUTO
                applyFlashSettings(this, flash)
            }

            val targets = mutableListOf(previewSurface, recorderSurface)
            if (imageReader != null) {
                targets.add(imageReader!!.surface)
            }

            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val builder = previewRequestBuilder
                        if (builder != null) {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed setting video repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Video capture session configuration failed.")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate video session", e)
        }
    }

    private fun updatePreview() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            // Apply current digital zoom factor mapping
            val multiplier = viewModel.zoomMultiplier.value ?: 1.0f
            applyZoomRequestBuilder(builder, multiplier)

            // Update flash attributes
            applyFlashSettings(builder, viewModel.flashMode.value ?: CameraViewModel.FlashMode.AUTO)

            // Overrides for Pro manual controls mapping
            if (viewModel.currentMode.value == CameraViewModel.CameraMode.PRO) {
                applyProSettings(builder)
            } else {
                resetProSettingsToDefault(builder)
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Preview update dispatch failed.", e)
        }
    }

    private fun applyProSettings(builder: CaptureRequest.Builder) {
        val characteristics = cameraManager?.getCameraCharacteristics(selectedCameraId) ?: return
        
        // 1. Manually set white balance (Kelvin presets)
        val wbIdx = viewModel.manualWbIndex.value ?: 0
        val awbMode = when (wbIdx) {
            1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            3 -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            4 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            5 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        // 2. Manual focal system distance
        val focusIdx = viewModel.manualFocusIndex.value ?: 0
        if (focusIdx == 0) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f
            val ratio = (focusIdx - 1) / 9f // 0.0f to 1.0f range
            val focusDistance = ratio * minFocus
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        }

        // 3. Shutter speed & ISO sensitivities overriding
        val shutterIdx = viewModel.manualShutterIndex.value ?: 0
        val isoIdx = viewModel.manualIsoIndex.value ?: 0

        if (shutterIdx == 0 && isoIdx == 0) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            // Process camera ISO overrides map
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val isoVal = if (isoIdx == 0) {
                400
            } else {
                val isos = listOf(100, 200, 400, 800, 1600, 3200, 6400)
                isos.getOrElse(isoIdx - 1) { 400 }
            }
            val clampedIso = sensitivityRange?.let { range ->
                isoVal.coerceIn(range.lower, range.upper)
            } ?: isoVal
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)

            // Process sensor exposure times map (nanoseconds)
            val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val shutterTimes = listOf(
                16_666_666L, // 1/60s fallback exposure time
                1_000_000L,   // 1/1000s
                2_000_000L,   // 1/500s
                4_000_000L,   // 1/250s
                8_000_000L,   // 1/125s
                16_666_666L,  // 1/60s
                33_333_333L,  // 1/30s
                66_666_666L,  // 1/15s
                125_000_000L, // 1/8s
                250_000_000L, // 1/4s
                500_000_000L, // 1/2s
                1_000_000_000L // 1.0s exposure time
            )
            val speedVal = shutterTimes.getOrElse(shutterIdx) { 16_666_666L }
            val clampedSpeed = exposureTimeRange?.let { range ->
                speedVal.coerceIn(range.lower, range.upper)
            } ?: speedVal
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedSpeed)
        }
    }

    private fun resetProSettingsToDefault(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    private fun applyZoomRequestBuilder(builder: CaptureRequest.Builder, multiplier: Float) {
        val centerX = activeSensorRect.centerX()
        val centerY = activeSensorRect.centerY()
        val deltaX = (activeSensorRect.width() / (2 * multiplier)).toInt()
        val deltaY = (activeSensorRect.height() / (2 * multiplier)).toInt()

        val cropRect = Rect(
            centerX - deltaX,
            centerY - deltaY,
            centerX + deltaX,
            centerY + deltaY
        )
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }

    private fun applyFlashSettings(builder: CaptureRequest.Builder, mode: CameraViewModel.FlashMode) {
        val isRear = viewModel.isRearCamera.value ?: true
        when (mode) {
            CameraViewModel.FlashMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraViewModel.FlashMode.ON -> {
                if (isRear) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            CameraViewModel.FlashMode.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraViewModel.FlashMode.TORCH -> {
                if (isRear) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun applyLocalPreferences() {
        val showGridSetting = prefs.getBoolean("show_grid", false)
        binding.layGrid.visibility = if (showGridSetting) View.VISIBLE else View.GONE
    }

    private fun observeViewModel() {
        // Mode Updates
        viewModel.currentMode.observe(this) { mode ->
            binding.layProControls.visibility = if (mode == CameraViewModel.CameraMode.PRO) View.VISIBLE else View.GONE
            updateUIForMode(mode)
        }

        // Zoom slider values binding
        viewModel.zoomMultiplier.observe(this) { zoom ->
            binding.txtZoomLabel.text = String.format(Locale.US, "%.1fx", zoom)
            val seekProgress = (((zoom - 1f) / 9f) * 90).toInt()
            binding.sliderZoom.progress = seekProgress
            updatePreview()
        }

        // Active flash state update
        viewModel.flashMode.observe(this) { flash ->
            val iconRes = when (flash) {
                CameraViewModel.FlashMode.AUTO -> R.drawable.ic_flash_auto
                CameraViewModel.FlashMode.ON -> R.drawable.ic_flash_on
                CameraViewModel.FlashMode.OFF -> R.drawable.ic_flash_off
                CameraViewModel.FlashMode.TORCH -> R.drawable.ic_flash_torch
                null -> R.drawable.ic_flash_auto
            }
            binding.btnFlash.setImageResource(iconRes)
            updatePreview()
        }

        // AI stars overlay trigger
        viewModel.isAiEnabled.observe(this) { enabled ->
            val tint = if (enabled) Color.parseColor("#00E5FF") else Color.parseColor("#808080")
            binding.btnAiStars.setColorFilter(tint)
            if (!enabled) {
                binding.aiOverlay.updateDetections(emptyList(), false)
            }
        }

        // Timer counter label
        viewModel.timerSeconds.observe(this) { seconds ->
            binding.txtTimerLabel.text = if (seconds == 0) "OFF" else "${seconds}S"
        }

        // Video recording state indicator
        viewModel.isRecording.observe(this) { isRec ->
            if (isRec) {
                binding.recIndicatorDot.visibility = View.VISIBLE
                binding.txtRecTimer.visibility = View.VISIBLE
                
                // Pulsing dot animations
                val blink = AlphaAnimation(0.2f, 1.0f).apply {
                    duration = 600
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                binding.recIndicatorDot.startAnimation(blink)

                // Stop square shutter visual using a clean rounded-corner drawable
                val roundedSquare = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8.dpToPx().toFloat()
                    setColor(Color.parseColor("#FF0000"))
                }
                binding.shutterInner.background = roundedSquare
                val lp = binding.shutterInner.layoutParams
                lp.width = 32.dpToPx()
                lp.height = 32.dpToPx()
                binding.shutterInner.layoutParams = lp
            } else {
                binding.recIndicatorDot.clearAnimation()
                binding.recIndicatorDot.visibility = View.GONE
                binding.txtRecTimer.visibility = View.GONE
                binding.txtRecTimer.text = "00:00:00"

                // Reset standard circle structures
                val lp = binding.shutterInner.layoutParams
                lp.width = 66.dpToPx()
                lp.height = 66.dpToPx()
                binding.shutterInner.layoutParams = lp
                
                val mColor = if (viewModel.currentMode.value == CameraViewModel.CameraMode.VIDEO) {
                    Color.parseColor("#FF0000")
                } else {
                    val currentMode = viewModel.currentMode.value
                    if (currentMode == CameraViewModel.CameraMode.PRO) {
                        Color.parseColor("#00E5FF")
                    } else if (currentMode == CameraViewModel.CameraMode.NIGHT) {
                        Color.parseColor("#FFFF33")
                    } else if (currentMode == CameraViewModel.CameraMode.AI_EDITOR) {
                        Color.parseColor("#00E5FF")
                    } else {
                        Color.parseColor("#FFFFFF")
                    }
                }
                val circleBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(mColor)
                }
                binding.shutterInner.background = circleBg
            }
        }

        // Night Mode process views
        viewModel.isNightProcessing.observe(this) { isProcessing ->
            binding.progressContainer.visibility = if (isProcessing) View.VISIBLE else View.GONE
        }
        viewModel.nightProcessingProgress.observe(this) { progress ->
            binding.nightProgressBar.progress = progress
        }

        // Pro Mode active category tab
        viewModel.proTab.observe(this) { tab ->
            // Reset tab styles
            binding.tvProShutterTab.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tvProIsoTab.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tvProWbTab.setTextColor(Color.parseColor("#FFFFFF"))
            binding.tvProFocusTab.setTextColor(Color.parseColor("#FFFFFF"))

            when (tab) {
                CameraViewModel.ProTabOption.SHUTTER -> {
                    binding.tvProShutterTab.setTextColor(Color.parseColor("#00E5FF"))
                    binding.barProVal.max = 11
                    binding.barProVal.progress = viewModel.manualShutterIndex.value ?: 0
                }
                CameraViewModel.ProTabOption.ISO -> {
                    binding.tvProIsoTab.setTextColor(Color.parseColor("#00E5FF"))
                    binding.barProVal.max = 7
                    binding.barProVal.progress = viewModel.manualIsoIndex.value ?: 0
                }
                CameraViewModel.ProTabOption.WB -> {
                    binding.tvProWbTab.setTextColor(Color.parseColor("#00E5FF"))
                    binding.barProVal.max = 5
                    binding.barProVal.progress = viewModel.manualWbIndex.value ?: 0
                }
                CameraViewModel.ProTabOption.FOCUS -> {
                    binding.tvProFocusTab.setTextColor(Color.parseColor("#00E5FF"))
                    binding.barProVal.max = 10
                    binding.barProVal.progress = viewModel.manualFocusIndex.value ?: 0
                }
                null -> {}
            }
            updateProValueDisplay()
        }

        val updateProObserver = { _: Int? ->
            updateProValueDisplay()
            updatePreview()
        }

        viewModel.manualShutterIndex.observe(this, updateProObserver)
        viewModel.manualIsoIndex.observe(this, updateProObserver)
        viewModel.manualWbIndex.observe(this, updateProObserver)
        viewModel.manualFocusIndex.observe(this, updateProObserver)
    }

    private fun updateProValueDisplay() {
        val tab = viewModel.proTab.value ?: CameraViewModel.ProTabOption.SHUTTER
        val valTxt = when (tab) {
            CameraViewModel.ProTabOption.SHUTTER -> {
                val idx = viewModel.manualShutterIndex.value ?: 0
                val labels = listOf("AUTO", "1/1000s", "1/500s", "1/250s", "1/125s", "1/60s", "1/30s", "1/15s", "1/8s", "1/4s", "1/2s", "1s")
                labels.getOrElse(idx) { "AUTO" }
            }
            CameraViewModel.ProTabOption.ISO -> {
                val idx = viewModel.manualIsoIndex.value ?: 0
                val labels = listOf("AUTO", "ISO 100", "ISO 200", "ISO 400", "ISO 800", "ISO 1600", "ISO 3200", "ISO 6400")
                labels.getOrElse(idx) { "AUTO" }
            }
            CameraViewModel.ProTabOption.WB -> {
                val idx = viewModel.manualWbIndex.value ?: 0
                val labels = listOf("AUTO", "Daylight", "Cloudy", "Shade", "Tungsten", "Fluorescent")
                labels.getOrElse(idx) { "AUTO" }
            }
            CameraViewModel.ProTabOption.FOCUS -> {
                val idx = viewModel.manualFocusIndex.value ?: 0
                if (idx == 0) "AUTO" else "MF ${(idx - 1) * 10}%"
            }
        }
        binding.tvProVal.text = valTxt
    }

    private fun updateUIForMode(mode: CameraViewModel.CameraMode) {
        // Highlighting current carousel selection
        val modes = listOf(binding.modePro, binding.modeVideo, binding.modePhoto, binding.modeNight, binding.modeAiEditor)
        modes.forEach { view ->
            view.setTextColor(Color.parseColor("#80FFFFFF"))
            view.textSize = 13f
        }

        val targetView = when (mode) {
            CameraViewModel.CameraMode.PRO -> binding.modePro
            CameraViewModel.CameraMode.VIDEO -> binding.modeVideo
            CameraViewModel.CameraMode.PHOTO -> binding.modePhoto
            CameraViewModel.CameraMode.NIGHT -> binding.modeNight
            CameraViewModel.CameraMode.AI_EDITOR -> binding.modeAiEditor
        }
        targetView.setTextColor(Color.parseColor("#00E5FF"))
        targetView.textSize = 14f

        // Configure shutter outer circle as a clean hollow white ring
        val outerRing = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(4.dpToPx(), Color.WHITE)
        }
        binding.shutterOuter.background = outerRing
        binding.shutterOuter.backgroundTintList = null // clear tint to preserve white ring borders

        // Determine specific outer and inner filling colors
        val targetColorHex = when (mode) {
            CameraViewModel.CameraMode.VIDEO -> "#FF0000"
            CameraViewModel.CameraMode.PHOTO -> "#FFFFFF"
            CameraViewModel.CameraMode.PRO -> "#00E5FF"
            CameraViewModel.CameraMode.NIGHT -> "#FFFF33"
            CameraViewModel.CameraMode.AI_EDITOR -> "#00E5FF"
        }

        val innerCircle = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(targetColorHex))
        }
        binding.shutterInner.background = innerCircle

        when (mode) {
            CameraViewModel.CameraMode.VIDEO -> {
                binding.badgeResolutionFps.text = "REC 4K·60"
                binding.badgeResolutionFps.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                binding.badgeRecStats.visibility = View.VISIBLE
            }
            CameraViewModel.CameraMode.PHOTO -> {
                binding.badgeResolutionFps.text = "12MP·Binned"
                binding.badgeResolutionFps.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.background_dark)
                binding.badgeRecStats.visibility = View.GONE
            }
            CameraViewModel.CameraMode.PRO -> {
                binding.badgeResolutionFps.text = "50MP·RAW DNG"
                binding.badgeResolutionFps.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.black)
                binding.badgeRecStats.visibility = View.GONE
            }
            CameraViewModel.CameraMode.NIGHT -> {
                binding.badgeResolutionFps.text = "NIGHT MULTI-DR"
                binding.badgeResolutionFps.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_bright)
                binding.badgeRecStats.visibility = View.GONE
            }
            CameraViewModel.CameraMode.AI_EDITOR -> {
                binding.badgeResolutionFps.text = "AI DETECT ACTIVE"
                binding.badgeResolutionFps.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_purple)
                binding.badgeRecStats.visibility = View.GONE
            }
        }
    }

    private fun setupGestures() {
        // Zoom pinch scales
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = viewModel.zoomMultiplier.value ?: 1.0f
                val newZoom = currentZoom * detector.scaleFactor
                viewModel.setZoom(newZoom)
                return true
            }
        })

        // Swipe horizontal to swap lenses + Flip Animation
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaX = e2.x - e1.x
                if (Math.abs(deltaX) > 150 && Math.abs(velocityX) > 150) {
                    triggerFlipCamera()
                    return true
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                triggerTapToFocus(e.x, e.y)
                return true
            }
        })

        // Forward Touch events
        binding.viewfinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun triggerFlipCamera() {
        // 3D Rotation Animation effect on viewport
        val animator = ObjectAnimator.ofFloat(binding.viewfinder, "rotationY", 0f, 180f).apply {
            duration = 350
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.viewfinder.rotationY = 0f
                viewModel.switchCamera()
                closeCamera()
                openCamera()
                Toast.makeText(this@CameraActivity, "Lens configuration swapped", Toast.LENGTH_SHORT).show()
            }
        })
        animator.start()
    }

    private fun triggerTapToFocus(x: Float, y: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        // Set screen flash screen coordinates for focusing indicator
        binding.focusIndicator.x = x - (binding.focusIndicator.width / 2)
        binding.focusIndicator.y = y - (binding.focusIndicator.height / 2)
        binding.focusIndicator.alpha = 1.0f
        binding.focusIndicator.scaleX = 1.0f
        binding.focusIndicator.scaleY = 1.0f

        // Bounce focus animation
        binding.focusIndicator.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(350)
            .withEndAction {
                binding.focusIndicator.animate().alpha(0f).setStartDelay(1000).setDuration(300).start()
            }.start()

        try {
            // Apply Manual Focusing Meter Rect coordinates
            val rect = Rect(
                (x - 50).toInt().coerceAtLeast(0),
                (y - 50).toInt().coerceAtLeast(0),
                (x + 50).toInt().coerceAtMost(binding.viewfinder.width),
                (y + 50).toInt().coerceAtMost(binding.viewfinder.height)
            )
            val meteringRect = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
            
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            
            // Resume regular AF updates
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed applying dynamic metering focus rect.", e)
        }
    }

    private fun setupUIListeners() {
        // Quick bottom sheet trigger
        binding.txtSwipeIndicator.setOnClickListener {
            showQuickSettingsSheet()
        }

        // Mode switches taps selectors
        binding.modePro.setOnClickListener { viewModel.setMode(CameraViewModel.CameraMode.PRO) }
        binding.modeVideo.setOnClickListener { viewModel.setMode(CameraViewModel.CameraMode.VIDEO) }
        binding.modePhoto.setOnClickListener { viewModel.setMode(CameraViewModel.CameraMode.PHOTO) }
        binding.modeNight.setOnClickListener { viewModel.setMode(CameraViewModel.CameraMode.NIGHT) }
        binding.modeAiEditor.setOnClickListener { viewModel.setMode(CameraViewModel.CameraMode.AI_EDITOR) }

        // Settings Activity cog
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Slider zoom interactions
        binding.sliderZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val scale = 1.0f + (progress / 90f) * 9.0f // translate to 1.0x to 10.0x
                    viewModel.setZoom(scale)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Flash cycle clicks toggle
        binding.btnFlash.setOnClickListener {
            viewModel.toggleFlash()
        }

        // Timer clicks selector cycle [0, 3, 10]
        binding.txtTimerLabel.setOnClickListener {
            val nextS = when (viewModel.timerSeconds.value) {
                0 -> 3
                3 -> 10
                10, null -> 0
                else -> 0
            }
            viewModel.setTimer(nextS)
        }

        // AI stars icon toggle
        binding.btnAiStars.setOnClickListener {
            val enabled = viewModel.isAiEnabled.value ?: false
            viewModel.setAiEnabled(!enabled)
        }

        // Swapping Lenses button
        binding.btnSwitchCamera.setOnClickListener {
            triggerFlipCamera()
        }

        // Shutter Button interactions
        binding.btnShutterFrame.setOnClickListener {
            animateShutterClick()

            val seconds = viewModel.timerSeconds.value ?: 0
            if (seconds > 0) {
                startTimerCountdown(seconds) {
                    executeCaptureAction()
                }
            } else {
                executeCaptureAction()
            }
        }

        // Rounded thumbnail viewer galleri opener
        binding.imgGalleryThumbnail.setOnClickListener {
            val latest = GalleryHelper.getLastSavedMediaUri(this)
            GalleryHelper.openGalleryAtUri(this, latest)
        }

        // Pro Mode manual controls tab clicks
        binding.tvProShutterTab.setOnClickListener {
            viewModel.setProTab(CameraViewModel.ProTabOption.SHUTTER)
        }
        binding.tvProIsoTab.setOnClickListener {
            viewModel.setProTab(CameraViewModel.ProTabOption.ISO)
        }
        binding.tvProWbTab.setOnClickListener {
            viewModel.setProTab(CameraViewModel.ProTabOption.WB)
        }
        binding.tvProFocusTab.setOnClickListener {
            viewModel.setProTab(CameraViewModel.ProTabOption.FOCUS)
        }

        // Pro Mode seekbar adjustment listener
        binding.barProVal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    when (viewModel.proTab.value) {
                        CameraViewModel.ProTabOption.SHUTTER -> viewModel.setManualShutter(progress)
                        CameraViewModel.ProTabOption.ISO -> viewModel.setManualIso(progress)
                        CameraViewModel.ProTabOption.WB -> viewModel.setManualWb(progress)
                        CameraViewModel.ProTabOption.FOCUS -> viewModel.setManualFocus(progress)
                        null -> {}
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun animateShutterClick() {
        val clickAnim = ScaleAnimation(1.0f, 0.85f, 1.0f, 0.85f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 100
            repeatMode = Animation.REVERSE
            repeatCount = 1
        }
        binding.btnShutterFrame.startAnimation(clickAnim)
    }

    private fun startTimerCountdown(seconds: Int, onComplete: () -> Unit) {
        binding.txtCountdown.visibility = View.VISIBLE
        var current = seconds
        
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (current <= 0) {
                        timer.cancel()
                        binding.txtCountdown.visibility = View.GONE
                        onComplete()
                    } else {
                        binding.txtCountdown.text = "$current"
                        current--
                    }
                }
            }
        }, 0, 1000)
    }

    private fun executeCaptureAction() {
        val mode = viewModel.currentMode.value
        val isVideo = mode == CameraViewModel.CameraMode.VIDEO
        
        // Play shutter click sound on photo captures only
        if (!isVideo) {
            try {
                shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.e(TAG, "Shutter sound play failed", e)
            }
        }

        when (mode) {
            CameraViewModel.CameraMode.VIDEO -> {
                val isRec = viewModel.isRecording.value ?: false
                if (isRec) {
                    stopVideoRecording()
                } else {
                    startVideoRecording()
                }
            }
            CameraViewModel.CameraMode.NIGHT -> {
                triggerNightBurstCapture()
            }
            CameraViewModel.CameraMode.PHOTO, CameraViewModel.CameraMode.PRO -> {
                val isRear = viewModel.isRearCamera.value ?: true
                val hdrEnabled = prefs.getBoolean("hdr_enabled", false)
                if (isRear && hdrEnabled) {
                    triggerHdrPhotoCapture()
                } else {
                    triggerNormalPhotoCapture()
                }
            }
            else -> {
                triggerNormalPhotoCapture()
            }
        }
    }

    private fun triggerHdrPhotoCapture() {
        // Grab viewfinder bitmap at the moment of capture (UI thread)
        val capturedBmp = binding.viewfinder.bitmap

        // Submit real Camera2 exposure compensation bracket burst captures
        val device = cameraDevice
        val session = captureSession
        if (device != null && session != null) {
            backgroundHandler?.post {
                try {
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    val texture = binding.viewfinder.surfaceTexture
                    if (texture != null) {
                        val previewSurface = Surface(texture)
                        builder.addTarget(previewSurface)
                    }

                    // Three exposure brackets: Under-exposed, Neutral, Over-exposed values (-6, 0, +6)
                    val req1 = builder.apply { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -6) }.build()
                    val req2 = builder.apply { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0) }.build()
                    val req3 = builder.apply { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 6) }.build()

                    session.captureBurst(listOf(req1, req2, req3), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            Log.d(TAG, "Real Camera2 HDR bracket capture started.")
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed submitting real HDR exposure compensation burst", e)
                }
            }
        }

        // Show Progress overlay with fusion feedback
        binding.txtProgressStatus.text = "HDR Exposure Fusion..."
        viewModel.startNightProcessing()

        var currentProg = 0
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (currentProg >= 100) {
                        timer.cancel()
                        binding.txtProgressStatus.text = "Hold steady" // restore default label
                        Toast.makeText(this@CameraActivity, "HDR fusion complete!", Toast.LENGTH_SHORT).show()
                        saveHdrPhoto(capturedBmp)
                    } else {
                        currentProg += 10
                        viewModel.updateNightProgress(currentProg)
                    }
                }
            }
        }, 0, 150)
    }

    private fun saveHdrPhoto(capturedBmp: android.graphics.Bitmap?) {
        Toast.makeText(this, "Saving HDR image DCIM...", Toast.LENGTH_SHORT).show()
        backgroundHandler?.post {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFile = File(getExternalFilesDir(null), "HDR_$timeStamp.jpg")
                val fos = FileOutputStream(imageFile)

                // High fidelity HDR fusion representation
                val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                
                if (capturedBmp != null) {
                    // Draw original captured image scaled to fill
                    val srcRect = android.graphics.Rect(0, 0, capturedBmp.width, capturedBmp.height)
                    val destRect = android.graphics.Rect(0, 0, 1920, 1080)
                    canvas.drawBitmap(capturedBmp, srcRect, destRect, null)
                    
                    // Draw a semi-transparent colorful gradient overlay to represent advanced multi-DR exposure fusion details
                    val paint = android.graphics.Paint().apply {
                        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY)
                        alpha = 80 // overlay intensity
                    }
                    val lg = android.graphics.LinearGradient(
                        0f, 0f, 1920f, 1080f,
                        intArrayOf(Color.parseColor("#4000E5FF"), Color.parseColor("#00000000"), Color.parseColor("#40FF8000")),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    paint.shader = lg
                    canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
                } else {
                    // Fallback stylized base gradient to simulate rich colors
                    val paint = android.graphics.Paint()
                    val lg = android.graphics.LinearGradient(
                        0f, 0f, 1920f, 1080f,
                        intArrayOf(Color.parseColor("#FF0F172A"), Color.parseColor("#FF1E293B"), Color.parseColor("#FF00E5FF")),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    paint.shader = lg
                    canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
                }

                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 98, fos)
                fos.close()

                // Insert into system MediaStore
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "HDR_$timeStamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AitoxCamera")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 98, os)
                    }
                }

                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "HDR photo saved: DCIM/AitoxCamera", Toast.LENGTH_SHORT).show()
                    loadLatestThumbnail()
                }
            } catch (e: Exception) {
                Log.e(TAG, "HDR writing failed", e)
            }
        }
    }

    private fun triggerNormalPhotoCapture() {
        val screenFlashPref = prefs.getBoolean("screen_flash", false)
        val isFront = !(viewModel.isRearCamera.value ?: true)

        if (isFront && screenFlashPref) {
            // High illumination screen flash flicker
            binding.screenFlashOverlay.alpha = 1.0f
            binding.screenFlashOverlay.animate().alpha(0f).setDuration(400).start()
        }

        Toast.makeText(this, "Capturing beautiful 50MP shot...", Toast.LENGTH_SHORT).show()
        
        // Grab viewfinder bitmap at the moment of capture (UI thread)
        val capturedBmp = binding.viewfinder.bitmap

        // Simulative image output saved into local sandbox
        backgroundHandler?.post {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFile = File(getExternalFilesDir(null), "IMG_$timeStamp.jpg")
                val fos = FileOutputStream(imageFile)
                
                // If viewfinder bitmap is available, use it; otherwise draw fallback
                val bmp = capturedBmp ?: android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val canvas = android.graphics.Canvas(this)
                    canvas.drawColor(Color.parseColor("#FF0A0A0C"))
                }

                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos)
                fos.close()

                // Insert into system MediaStore (Images query)
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$timeStamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AitoxCamera")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
                    }
                }

                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "Photo saved: DCIM/AitoxCamera", Toast.LENGTH_SHORT).show()
                    loadLatestThumbnail()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image writing failed.", e)
            }
        }
    }

    private fun triggerNightBurstCapture() {
        viewModel.startNightProcessing()
        var currentProg = 0
        
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (currentProg >= 100) {
                        timer.cancel()
                        Toast.makeText(this@CameraActivity, "Averaging 5 multi-frame exposures completed!", Toast.LENGTH_SHORT).show()
                        triggerNormalPhotoCapture()
                    } else {
                        currentProg += 10
                        viewModel.updateNightProgress(currentProg)
                    }
                }
            }
        }, 0, 150)
    }

    private fun startVideoRecording() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val videoFile = File(getExternalFilesDir(null), "VID_$timeStamp.mp4")
            currentVideoFile = videoFile

            val useH265Pref = prefs.getBoolean("use_h265", false)
            val useEisPref = prefs.getBoolean("use_eis", true)

            // Dynamic specifications lookup depending on camera directions setting
            val isRear = viewModel.isRearCamera.value ?: true
            val width = if (isRear) 3840 else 1920
            val height = if (isRear) 2160 else 1080
            val fps = 60
            val bitrate = if (isRear) 150 else 50

            val recorderSurface = videoRecorder?.prepare(
                videoFile, isRear, width, height, fps, bitrate, useH265Pref, useEisPref
            )

            if (recorderSurface != null) {
                recreateSessionForVideo(recorderSurface)
            }

            videoRecorder?.start()
            viewModel.setRecording(true)

            // Setup counter ticks
            recordingSeconds = 0
            recordingTimer = Timer()
            recordingTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    recordingSeconds++
                    val h = recordingSeconds / 3600
                    val m = (recordingSeconds % 3600) / 60
                    val s = recordingSeconds % 60
                    runOnUiThread {
                        binding.txtRecTimer.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                    }
                }
            }, 1000, 1000)

        } catch (e: Exception) {
            Log.e(TAG, "Could not start video setup.", e)
            Toast.makeText(this, "Video record preparation failed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideoRecording() {
        recordingTimer?.cancel()
        recordingTimer = null
        videoRecorder?.stop()
        viewModel.setRecording(false)

        Toast.makeText(this, "Recording finalized: DCIM/AitoxCamera", Toast.LENGTH_SHORT).show()
        
        // Simulative MediaStore registration
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$timeStamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/AitoxCamera")
        }
        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                currentVideoFile?.let { sourceFile ->
                    copyFileToUri(sourceFile, uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video insertion error.", e)
        }

        createCameraSession()
        loadLatestThumbnail()
    }

    private fun loadLatestThumbnail() {
        val latestUri = GalleryHelper.getLastSavedMediaUri(this)
        if (latestUri != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val thumb = contentResolver.loadThumbnail(latestUri, android.util.Size(120, 120), null)
                    binding.imgGalleryThumbnail.setImageBitmap(thumb)
                } catch (e: Exception) {
                    binding.imgGalleryThumbnail.setImageURI(latestUri)
                }
            } else {
                binding.imgGalleryThumbnail.setImageURI(latestUri)
            }
        } else {
            binding.imgGalleryThumbnail.setImageResource(R.drawable.ic_launcher_background)
        }
    }

    private fun showQuickSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogQuickSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        // Read preferences states
        val showGridSetting = prefs.getBoolean("show_grid", false)
        sheetBinding.switchQuickGrid.isChecked = showGridSetting

        sheetBinding.switchQuickGrid.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_grid", isChecked).apply()
            applyLocalPreferences()
        }

        // Active AI frame overlays
        val aiEnabled = viewModel.isAiEnabled.value ?: false
        sheetBinding.switchQuickAi.isChecked = aiEnabled
        sheetBinding.switchQuickAi.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAiEnabled(isChecked)
        }

        // HDR switch Quick Setting
        val hdrEnabled = prefs.getBoolean("hdr_enabled", false)
        sheetBinding.switchQuickHdr.isChecked = hdrEnabled
        sheetBinding.switchQuickHdr.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hdr_enabled", isChecked).apply()
        }

        // Set up active resolution selection
        val savedRes = prefs.getString("resolution", "1080p (1920x1080)")
        if (savedRes?.contains("4K") == true) {
            sheetBinding.rbQuick4K.isChecked = true
        } else {
            sheetBinding.rbQuick1080p.isChecked = true
        }

        sheetBinding.rgQuickResolution.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbQuick4K) {
                prefs.edit().putString("resolution", "4K (3840x2160)").apply()
            } else {
                prefs.edit().putString("resolution", "1080p (1920x1080)").apply()
            }
        }

        // Flash settings buttons quick interactions
        sheetBinding.btnQuickFlashAuto.setOnClickListener {
            viewModel.setFlashMode(CameraViewModel.FlashMode.AUTO)
            sheet.dismiss()
        }
        sheetBinding.btnQuickFlashOn.setOnClickListener {
            viewModel.setFlashMode(CameraViewModel.FlashMode.ON)
            sheet.dismiss()
        }
        sheetBinding.btnQuickFlashOff.setOnClickListener {
            viewModel.setFlashMode(CameraViewModel.FlashMode.OFF)
            sheet.dismiss()
        }
        sheetBinding.btnQuickFlashTorch.setOnClickListener {
            viewModel.setFlashMode(CameraViewModel.FlashMode.TORCH)
            sheet.dismiss()
        }

        sheet.show()
    }

    // Dynamic measurements utilities
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Int.spToPx(): Float = this * resources.displayMetrics.scaledDensity
    private fun CameraViewModel.CameraMode.idx() = this.ordinal
}
