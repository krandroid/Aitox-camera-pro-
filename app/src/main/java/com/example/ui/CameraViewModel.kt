package com.example.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {

    enum class CameraMode {
        PRO, VIDEO, PHOTO, PORTRAIT, NIGHT, AI_EDITOR
    }

    enum class FlashMode {
        AUTO, ON, OFF, TORCH
    }

    enum class ProTabOption {
        SHUTTER, ISO, WB, FOCUS
    }

    private val _currentMode = MutableLiveData(CameraMode.PHOTO)
    val currentMode: LiveData<CameraMode> = _currentMode

    // Pro Mode states
    private val _proTab = MutableLiveData(ProTabOption.SHUTTER)
    val proTab: LiveData<ProTabOption> = _proTab

    private val _manualShutterIndex = MutableLiveData(0) // Default 0 = AUTO
    val manualShutterIndex: LiveData<Int> = _manualShutterIndex

    private val _manualIsoIndex = MutableLiveData(0) // Default 0 = AUTO
    val manualIsoIndex: LiveData<Int> = _manualIsoIndex

    private val _manualWbIndex = MutableLiveData(0) // Default 0 = AUTO
    val manualWbIndex: LiveData<Int> = _manualWbIndex

    private val _manualFocusIndex = MutableLiveData(0) // Default 0 = AUTO
    val manualFocusIndex: LiveData<Int> = _manualFocusIndex

    private val _zoomMultiplier = MutableLiveData(1.0f)
    val zoomMultiplier: LiveData<Float> = _zoomMultiplier

    private val _flashMode = MutableLiveData(FlashMode.AUTO)
    val flashMode: LiveData<FlashMode> = _flashMode

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    private val _isAiEnabled = MutableLiveData(false)
    val isAiEnabled: LiveData<Boolean> = _isAiEnabled

    private val _isRearCamera = MutableLiveData(true)
    val isRearCamera: LiveData<Boolean> = _isRearCamera

    private val _timerSeconds = MutableLiveData(0) // 0 means off, otherwise 3 or 10
    val timerSeconds: LiveData<Int> = _timerSeconds

    private val _isNightProcessing = MutableLiveData(false)
    val isNightProcessing: LiveData<Boolean> = _isNightProcessing

    private val _nightProcessingProgress = MutableLiveData(0)
    val nightProcessingProgress: LiveData<Int> = _nightProcessingProgress

    fun setMode(mode: CameraMode) {
        _currentMode.value = mode
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(1.0f, 10.0f)
        _zoomMultiplier.value = clamped
    }

    fun toggleFlash() {
        _flashMode.value = when (_flashMode.value) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.TORCH
            FlashMode.TORCH, null -> FlashMode.AUTO
        }
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
    }

    fun setAiEnabled(enabled: Boolean) {
        _isAiEnabled.value = enabled
    }

    fun switchCamera() {
        _isRearCamera.value = !(_isRearCamera.value ?: true)
    }

    fun setTimer(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun setProTab(tab: ProTabOption) {
        _proTab.value = tab
    }

    fun setManualShutter(index: Int) {
        _manualShutterIndex.value = index
    }

    fun setManualIso(index: Int) {
        _manualIsoIndex.value = index
    }

    fun setManualWb(index: Int) {
        _manualWbIndex.value = index
    }

    fun setManualFocus(index: Int) {
        _manualFocusIndex.value = index
    }

    fun startNightProcessing() {
        _isNightProcessing.value = true
        _nightProcessingProgress.value = 0
    }

    fun updateNightProgress(progress: Int) {
        _nightProcessingProgress.value = progress
        if (progress >= 100) {
            _isNightProcessing.value = false
        }
    }
}
