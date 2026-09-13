package com.ivan.spintracklab

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: TrackingOverlayView
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var calibrationText: TextView
    private lateinit var graph: SpeedGraphView
    private lateinit var sessionButton: Button

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private lateinit var sessionStore: SessionStore

    @Volatile
    private var calibration = DiscCalibration()

    @Volatile
    private var trackerConfig = TrackerConfig()

    private var sessionActive = false
    private var sessionStartedAtWallMs = 0L
    private var sessionStartedAtElapsedMs = 0L
    private var sessionOmegaSum = 0.0
    private var sessionConfidenceSum = 0.0
    private var sessionMaxOmega = 0f
    private var sessionLockedSamples = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 18, 22)
        window.navigationBarColor = Color.rgb(16, 18, 22)
        sessionStore = SessionStore(this)
        trackerConfig = loadTrackerConfig()
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 18, 22))
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = TrackingOverlayView(this).apply {
            onCenterSelected = { x, y ->
                calibration = calibration.copy(
                    centerX = x,
                    centerY = y,
                    revision = calibration.revision + 1
                )
                graph.clear()
                calibrationText.text = "Centre calibrated: %.2f / %.2f".format(Locale.US, x, y)
            }
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(0xE8101216.toInt())
        }

        statusText = TextView(this).apply {
            text = "Starting camera…"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        metricsText = TextView(this).apply {
            text = "angle —   ω —   FPS —   confidence —"
            setTextColor(0xFFC9CFD7.toInt())
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        calibrationText = TextView(this).apply {
            text = "Tap the centre of the test disc to calibrate"
            setTextColor(0xFF9099A6.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        val graphLabel = TextView(this).apply {
            text = "Angular velocity • last ~3 s"
            setTextColor(0xFF8D9AAA.toInt())
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }
        graph = SpeedGraphView(this)

        panel.addView(statusText)
        panel.addView(metricsText)
        panel.addView(calibrationText)
        panel.addView(graphLabel)
        panel.addView(
            graph,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
            )
        )

        val primaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sessionButton = makeButton("Start session") { toggleSession() }
        val settingsButton = makeButton("Settings") { showSettingsDialog() }
        val historyButton = makeButton("History") { showHistoryDialog() }
        addWeighted(primaryControls, sessionButton)
        addWeighted(primaryControls, settingsButton)
        addWeighted(primaryControls, historyButton)

        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val resetButton = makeButton("Reset centre") {
            calibration = DiscCalibration(revision = calibration.revision + 1)
            graph.clear()
            calibrationText.text = "Centre reset to frame middle"
        }
        val clearGraphButton = makeButton("Clear graph") { graph.clear() }
        addWeighted(secondaryControls, resetButton)
        addWeighted(secondaryControls, clearGraphButton)

        panel.addView(primaryControls)
        panel.addView(secondaryControls)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        return root
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        setAllCaps(false)
        minHeight = dp(44)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { action() }
    }

    private fun addWeighted(row: LinearLayout, button: Button) {
        row.addView(
            button,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        )
    }

    private fun startCamera() {
        statusText.text = "Camera ready — point at a test disc"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(
                analyzerExecutor,
                BallTrackerAnalyzer(
                    calibrationProvider = { calibration },
                    configProvider = { trackerConfig },
                    onState = { state ->
                        runOnUiThread { renderTrackingState(state) }
                    }
                )
            )

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                statusText.text = "Camera error: ${t.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun renderTrackingState(state: TrackingState) {
        overlay.submitState(state)
        graph.addSample(state.angularVelocityRadPerSecond)

        statusText.text = when {
            sessionActive && state.locked -> "RECORDING • TRACK LOCKED • ${state.directionLabel}"
            sessionActive -> "RECORDING • searching/stabilising…"
            state.locked -> "TRACK LOCKED • ${state.directionLabel}"
            state.detected -> "Marker detected • stabilising…"
            else -> "Searching for bright marker…"
        }

        metricsText.text = if (state.detected) {
            "angle %5.1f°   ω %+5.2f rad/s\nFPS %4.1f   confidence %d%%   radius %.2f".format(
                Locale.US,
                state.angleDegrees,
                state.angularVelocityRadPerSecond,
                state.fps,
                (state.confidence * 100).toInt(),
                state.radiusFraction
            )
        } else {
            "angle —   ω %+.2f rad/s\nFPS %.1f   confidence —".format(
                Locale.US,
                state.angularVelocityRadPerSecond,
                state.fps
            )
        }

        if (sessionActive && state.locked) {
            val omega = abs(state.angularVelocityRadPerSecond)
            sessionOmegaSum += omega
            sessionConfidenceSum += state.confidence
            sessionMaxOmega = max(sessionMaxOmega, omega)
            sessionLockedSamples++
        }
    }

    private fun toggleSession() {
        if (!sessionActive) {
            sessionActive = true
            sessionStartedAtWallMs = System.currentTimeMillis()
            sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
            sessionOmegaSum = 0.0
            sessionConfidenceSum = 0.0
            sessionMaxOmega = 0f
            sessionLockedSamples = 0
            graph.clear()
            sessionButton.text = "Stop session"
            statusText.text = "RECORDING • waiting for track lock…"
        } else {
            finishSession()
        }
    }

    private fun finishSession() {
        val duration = (SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs).coerceAtLeast(0L)
        val count = sessionLockedSamples.coerceAtLeast(1)
        val session = TestSession(
            startedAtMs = sessionStartedAtWallMs,
            durationMs = duration,
            averageAbsOmega = (sessionOmegaSum / count).toFloat(),
            maxAbsOmega = sessionMaxOmega,
            averageConfidence = (sessionConfidenceSum / count).toFloat(),
            lockedSamples = sessionLockedSamples
        )
        sessionStore.add(session)
        sessionActive = false
        sessionButton.text = "Start session"
        statusText.text = if (sessionLockedSamples > 0) {
            "Session saved • ${duration / 1000f} s"
        } else {
            "Session saved • no stable track samples"
        }
    }

    private fun showSettingsDialog() {
        var outer = trackerConfig.outerRadiusFraction
        var inner = trackerConfig.innerRadiusRatio
        var sensitivity = trackerConfig.sensitivity
        var showTrail = trackerConfig.showTrail

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }

        fun addSlider(title: String, initialProgress: Int, onProgress: (Int, TextView) -> Unit): SeekBar {
            val label = TextView(this).apply {
                setTextColor(Color.DKGRAY)
                textSize = 14f
            }
            val seek = SeekBar(this).apply {
                max = 100
                progress = initialProgress.coerceIn(0, 100)
            }
            onProgress(seek.progress, label)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onProgress(progress, label)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            container.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            })
            container.addView(label)
            container.addView(seek)
            return seek
        }

        addSlider("Search ring size", ((outer - 0.32f) / 0.17f * 100f).toInt()) { progress, label ->
            outer = 0.32f + 0.17f * (progress / 100f)
            label.text = "Outer radius: %d%% of short frame side".format((outer * 100).toInt())
        }
        addSlider("Inner ring boundary", ((inner - 0.35f) / 0.40f * 100f).toInt()) { progress, label ->
            inner = 0.35f + 0.40f * (progress / 100f)
            label.text = "Inner radius: %d%% of outer radius".format((inner * 100).toInt())
        }
        addSlider("Bright-marker sensitivity", (sensitivity * 100f).toInt()) { progress, label ->
            sensitivity = progress / 100f
            label.text = "Sensitivity: $progress%"
        }

        val trailSwitch = Switch(this).apply {
            text = "Show motion trail"
            isChecked = showTrail
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> showTrail = checked }
        }
        container.addView(trailSwitch)

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tracker settings")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                trackerConfig = TrackerConfig(
                    outerRadiusFraction = outer.coerceIn(0.32f, 0.49f),
                    innerRadiusRatio = inner.coerceIn(0.35f, 0.75f),
                    sensitivity = sensitivity.coerceIn(0.05f, 1f),
                    showTrail = showTrail
                )
                saveTrackerConfig(trackerConfig)
                calibration = calibration.copy(revision = calibration.revision + 1)
                graph.clear()
                calibrationText.text = "Settings applied • tracking reset"
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showHistoryDialog() {
        val sessions = sessionStore.load()
        val text = TextView(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setTextColor(Color.DKGRAY)
            textSize = 14f
            this.text = if (sessions.isEmpty()) {
                "No test sessions saved yet."
            } else {
                sessions.mapIndexed { index, session -> session.summary(sessions.size - index) }
                    .joinToString("\n\n")
            }
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Test session history")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> sessionStore.clear() }
            .show()
    }

    private fun loadTrackerConfig(): TrackerConfig {
        val prefs = getSharedPreferences("spintrack_config", Context.MODE_PRIVATE)
        return TrackerConfig(
            outerRadiusFraction = prefs.getFloat("outer", 0.46f),
            innerRadiusRatio = prefs.getFloat("inner", 0.55f),
            sensitivity = prefs.getFloat("sensitivity", 0.55f),
            showTrail = prefs.getBoolean("trail", true)
        )
    }

    private fun saveTrackerConfig(config: TrackerConfig) {
        getSharedPreferences("spintrack_config", Context.MODE_PRIVATE)
            .edit()
            .putFloat("outer", config.outerRadiusFraction)
            .putFloat("inner", config.innerRadiusRatio)
            .putFloat("sensitivity", config.sensitivity)
            .putBoolean("trail", config.showTrail)
            .apply()
    }

    private fun showPermissionRequired() {
        statusText.text = "Camera permission is required for tracking"
        val button = Button(this).apply {
            text = "Grant camera permission"
            setAllCaps(false)
            setOnClickListener { permissionLauncher.launch(Manifest.permission.CAMERA) }
        }
        (statusText.parent as? LinearLayout)?.addView(button)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (sessionActive) finishSession()
        analyzerExecutor.shutdownNow()
        super.onDestroy()
    }
}
