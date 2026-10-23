package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kafkasl.phonewhisper.Prefs.cancelOnScreenOff
import com.kafkasl.phonewhisper.Prefs.haptics
import com.kafkasl.phonewhisper.Prefs.idleReleaseMinutes
import com.kafkasl.phonewhisper.Prefs.micSource
import com.kafkasl.phonewhisper.Prefs.numThreads
import com.kafkasl.phonewhisper.Prefs.overlayKeyboardOnly
import com.kafkasl.phonewhisper.Prefs.preserveClipboard
import com.kafkasl.phonewhisper.Prefs.trimSilence
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WhisperAccessibilityService? = null
            private set

        /** Called on the main thread when the service starts or stops; MainActivity uses it to refresh its status. */
        var onRunningChanged: (() -> Unit)? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val BARS_DP = 22
        private const val FEEDBACK_OFFSET_DP = 64
        private const val VISIBILITY_DEBOUNCE_MS = 100L
        private const val KEYBOARD_WATCHDOG_MS = 1000L
        private const val LEVEL_UPDATE_MS = 50L

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_RETRY = 0xDDF59E0B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    /** RETRY: the last recording failed and is kept; tap retries it, a long press discards it. */
    private enum class State { IDLE, RECORDING, TRANSCRIBING, RETRY }

    @Volatile private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var levelBars: LevelBarsView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var recorderThread: Thread? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // The overlay remembers which edge it sits on and how far down, so it survives rotation.
    private var onRightEdge = true
    private var yFraction = 0.5f

    /** Audio of the last recording that could not be transcribed, kept for a retry. */
    @Volatile private var failedPcm: ByteArray? = null

    // Local transcription engine. Loading, releasing and local transcription all run on
    // [modelExecutor], one at a time, so a model is never released while it transcribes.
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val transcriberLock = Any()
    @Volatile private var localTranscriber: LocalTranscriber? = null
    private val pendingLoads = AtomicInteger()
    private val modelLoading get() = pendingLoads.get() > 0

    private val trimmerLock = Any()
    private var silenceTrimmer: SilenceTrimmer? = null
    private var trimmerFailed = false

    private val idleRelease = Runnable {
        if (state == State.IDLE || state == State.RETRY) {
            Log.i(TAG, "Releasing local model after inactivity")
            modelExecutor.execute { unloadModel() }
        }
    }

    private val refreshVisibility = Runnable { updateOverlayVisibility() }

    /** Android doesn't always report the keyboard closing (e.g. on the Home gesture), so re-check while shown. */
    private val keyboardWatchdog = Runnable { updateOverlayVisibility() }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && state == State.RECORDING && prefs().cancelOnScreenOff()) {
                cancelRecording()
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.OVERLAY_MODE -> handler.post { updateOverlayVisibility() }
            Prefs.IDLE_RELEASE_MINUTES -> handler.post { scheduleIdleRelease() }
        }
    }

    private val dp get() = resources.displayMetrics.density

    private val screenSize: Pair<Int, Int>
        get() {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }

    override fun onServiceConnected() {
        instance = this
        onRunningChanged?.invoke()
        showOverlay()
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        reloadModel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window changes include the keyboard opening and closing.
        handler.removeCallbacks(refreshVisibility)
        handler.postDelayed(refreshVisibility, VISIBILITY_DEBOUNCE_MS)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Window metrics are updated after the configuration callback.
        handler.post { placeOverlay() }
    }

    override fun onDestroy() {
        instance = null
        onRunningChanged?.invoke()
        state = State.IDLE
        handler.removeCallbacksAndMessages(null)
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        try { unregisterReceiver(screenOffReceiver) } catch (_: IllegalArgumentException) {}
        releaseRecorder()
        removeOverlay()
        modelExecutor.execute {
            unloadModel()
            synchronized(trimmerLock) { silenceTrimmer?.release(); silenceTrimmer = null }
        }
        modelExecutor.shutdown()
        super.onDestroy()
    }

    // --- Local model ---

    /**
     * (Re)load the selected local model, or just free the current one in cloud mode. The old model
     * is released before the new one loads, so two large models are never in memory at once.
     * Called from MainActivity when the model, the thread count or the mode changes.
     */
    fun reloadModel() {
        pendingLoads.incrementAndGet()
        modelExecutor.execute {
            try {
                unloadModel()
                if (prefs().getBoolean("use_local", true)) {
                    val loaded = loadSelectedModel()
                    synchronized(transcriberLock) { localTranscriber = loaded }
                    Log.i(TAG, if (loaded != null) "Local transcription ready" else "No usable local model")
                }
            } finally {
                pendingLoads.decrementAndGet()
                handler.post { scheduleIdleRelease() }
            }
        }
    }

    private fun unloadModel() {
        synchronized(transcriberLock) {
            localTranscriber?.release()
            localTranscriber = null
        }
    }

    private fun loadSelectedModel(): LocalTranscriber? {
        val available = LocalTranscriber.availableModels(this)
        val selected = prefs().getString("model_name", "").orEmpty()
        val name = selected.takeIf { it in available } ?: available.firstOrNull() ?: return null
        return LocalTranscriber.create(File(LocalTranscriber.modelsDir(this), name), prefs().numThreads())
    }

    /** Free the local model after the configured idle time; it is loaded again when recording starts. */
    private fun scheduleIdleRelease() {
        handler.removeCallbacks(idleRelease)
        val minutes = prefs().idleReleaseMinutes()
        if (minutes > 0 && localTranscriber != null && state != State.RECORDING && state != State.TRANSCRIBING) {
            handler.postDelayed(idleRelease, minutes * 60_000L)
        }
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val bars = LevelBarsView(this).apply { visibility = View.GONE }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(bars, FrameLayout.LayoutParams((BARS_DP * dp).toInt(), (BARS_DP * dp).toInt(), Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (_, screenH) = screenSize
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = (startY + (ev.rawY - touchY).toInt()).coerceIn(0, maxOf(0, screenH - ringSize))
                    wm.updateViewLayout(v, params)
                    updateFeedbackPosition()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        val held = ev.eventTime - ev.downTime
                        if (held >= ViewConfiguration.getLongPressTimeout()) onLongPress() else onTap()
                    } else {
                        val (screenW, screenH) = screenSize
                        onRightEdge = params.x + ringSize / 2 > screenW / 2
                        yFraction = (params.y + ringSize / 2f) / screenH
                        placeOverlay()
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = overlay
        button = img
        levelBars = bars
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        positionOverlay(params)
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        updateOverlayVisibility()
    }

    /**
     * In "only with keyboard" mode the button shows while a keyboard is open, and always while it
     * records, transcribes or offers a retry. It is hidden, not removed, so it keeps its place;
     * the hidden window is made untouchable, or it would swallow taps meant for the app below.
     */
    private fun updateOverlayVisibility() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        handler.removeCallbacks(keyboardWatchdog)
        val keyboardOnly = prefs().overlayKeyboardOnly()
        val show = !keyboardOnly || state != State.IDLE || isKeyboardVisible()
        val visibility = if (show) View.VISIBLE else View.GONE
        if (view.visibility != visibility) {
            view.visibility = visibility
            params.flags = if (show) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        }
        if (keyboardOnly && show && state == State.IDLE) handler.postDelayed(keyboardWatchdog, KEYBOARD_WATCHDOG_MS)
    }

    /** Needs flagRetrieveInteractiveWindows, otherwise [getWindows] is empty. */
    private fun isKeyboardVisible(): Boolean =
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true

    /** Snap the button to its edge at its relative height, for the current screen size. */
    private fun positionOverlay(params: WindowManager.LayoutParams) {
        val (screenW, screenH) = screenSize
        val ringSize = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        params.x = if (onRightEdge) screenW - ringSize - margin else margin
        params.y = (yFraction * screenH - ringSize / 2f).toInt().coerceIn(margin, maxOf(margin, screenH - ringSize - margin))
    }

    private fun placeOverlay() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        positionOverlay(params)
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        updateFeedbackPosition()
    }

    private fun updateFeedbackPosition() {
        val view = feedbackView ?: return
        val params = feedbackLayoutParams ?: return
        val bubble = layoutParams ?: return
        positionFeedback(params, bubble)
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        levelBars = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            view.text = text
            updateFeedbackPosition()

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    // --- State machine ---

    /** Switch state and update the button to match. Safe to call from any thread. */
    private fun enter(newState: State) {
        state = newState
        handler.post {
            applyAppearance()
            updateOverlayVisibility()
            if (newState == State.IDLE || newState == State.RETRY) scheduleIdleRelease()
            else handler.removeCallbacks(idleRelease)
        }
    }

    private fun applyAppearance() {
        val img = button ?: return
        val (color, icon) = when (state) {
            State.IDLE -> COLOR_IDLE to R.drawable.ic_mic
            State.RECORDING -> COLOR_RECORDING to null
            State.TRANSCRIBING -> COLOR_BUSY to R.drawable.ic_mic
            State.RETRY -> COLOR_RETRY to R.drawable.ic_retry
        }
        img.background = circle(color)
        if (icon != null) img.setImageResource(icon) else img.setImageDrawable(null)
        img.alpha = if (state == State.TRANSCRIBING) 0.6f else 1f
        levelBars?.apply {
            reset()
            visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
        }
        spinner?.visibility = if (state == State.TRANSCRIBING) View.VISIBLE else View.GONE
    }

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
            State.RETRY -> retry()
        }
    }

    private fun onLongPress() {
        when (state) {
            State.RETRY -> {
                failedPcm = null
                enter(State.IDLE)
                showFeedback("Recording discarded")
            }
            else -> onTap()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) { toast("Microphone not available"); return }

        // VOICE_RECOGNITION is the source meant for speech recognition (no voice-call processing).
        val source = if (prefs().micSource() == Prefs.MIC_RAW) MediaRecorder.AudioSource.MIC
            else MediaRecorder.AudioSource.VOICE_RECOGNITION

        // Creating or starting the recorder fails when another app (or a call) holds the microphone.
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (_: SecurityException) { toast("Audio permission denied"); return }
        catch (e: IllegalArgumentException) { toast("Microphone not available"); return }

        val started = recorder.state == AudioRecord.STATE_INITIALIZED && try {
            recorder.startRecording()
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) { false }
        if (!started) {
            recorder.release()
            toast("Microphone is busy")
            return
        }

        val stream = ByteArrayOutputStream()
        audioRecord = recorder
        pcmStream = stream
        failedPcm = null
        enter(State.RECORDING)
        vibrate()

        // Load the local model while the user speaks if it was released for inactivity.
        if (prefs().getBoolean("use_local", true) && localTranscriber == null && !modelLoading) reloadModel()

        recorderThread = thread {
            val buf = ByteArray(bufSize)
            var lastLevelAt = 0L
            while (state == State.RECORDING) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    stream.write(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - lastLevelAt >= LEVEL_UPDATE_MS) {
                        lastLevelAt = now
                        val level = LevelBarsView.levelOf(buf, n)
                        handler.post { levelBars?.setLevel(level) }
                    }
                } else if (n < 0) break
            }
        }
    }

    /** Stop the recorder, wait for the reader thread so the last buffer isn't lost, and free it. */
    private fun releaseRecorder() {
        val recorder = audioRecord ?: return
        audioRecord = null
        try { recorder.stop() } catch (_: IllegalStateException) {}
        recorderThread?.join(1000)
        recorderThread = null
        recorder.release()
    }

    private fun stopAndTranscribe() {
        enter(State.TRANSCRIBING)
        releaseRecorder()
        vibrate()

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { finish("No audio captured"); return }
        thread { transcribe(pcm, trim = prefs().trimSilence()) }
    }

    /** Screen turned off while recording: drop the audio so a pocket recording isn't sent anywhere. */
    private fun cancelRecording() {
        handler.post {
            if (state != State.RECORDING) return@post
            state = State.TRANSCRIBING
            releaseRecorder()
            pcmStream = null
            enter(State.IDLE)
            showFeedback("Recording cancelled: screen turned off", 3000)
        }
    }

    private fun retry() {
        val pcm = failedPcm ?: run { enter(State.IDLE); return }
        failedPcm = null
        enter(State.TRANSCRIBING)
        showFeedback("Retrying...", 1200)
        // No trimming on retry: if the VAD found no speech, the user wants the raw audio tried.
        thread { transcribe(pcm, trim = false) }
    }

    /**
     * Runs on a worker thread. Local mode never falls back to the cloud: if the model is still
     * loading it waits for it, and a missing model is reported instead.
     */
    private fun transcribe(pcm: ByteArray, trim: Boolean) {
        var samples = pcmToFloats(pcm)
        if (trim) {
            val trimmed = trimSilence(samples)
            if (trimmed == null) { fail(pcm, "No speech detected"); return }
            Log.i(TAG, "Trimmed silence: ${samples.size / SAMPLE_RATE}s -> ${trimmed.size / SAMPLE_RATE}s")
            samples = trimmed
        }

        if (!prefs().getBoolean("use_local", true)) { transcribeApi(pcm, samples, trimmed = trim); return }

        if (localTranscriber == null && !modelLoading) {
            if (LocalTranscriber.availableModels(this).isEmpty()) {
                fail(null, "No local model. Download one in the Phone Whisper app.")
                return
            }
            reloadModel()
        }
        if (modelLoading) showFeedback("Loading model...", 1500)
        // Queued behind any load, so it runs as soon as the model is ready.
        modelExecutor.execute { transcribeLocal(pcm, samples) }
    }

    private fun transcribeLocal(pcm: ByteArray, samples: FloatArray) {
        try {
            val text = synchronized(transcriberLock) {
                val transcriber = localTranscriber ?: return fail(pcm, "Could not load the local model")
                val t0 = System.currentTimeMillis()
                transcriber.transcribe(samples, SAMPLE_RATE).also {
                    val ms = System.currentTimeMillis() - t0
                    Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")
                }
            }
            handleTranscriptionResult(text)
        } catch (e: Exception) {
            Log.e(TAG, "Local transcription failed", e)
            fail(pcm, ErrorMessages.local(e.message))
        }
    }

    private fun transcribeApi(pcm: ByteArray, samples: FloatArray, trimmed: Boolean) {
        val target = prefs().transcriptionTarget()
        if (!target.isConfigured) {
            val missing = if (target.baseUrl.isBlank()) "custom API address" else "${target.provider.label} API key"
            fail(pcm, "Set the $missing in Phone Whisper"); return
        }

        val audio = if (trimmed) floatsToPcm(samples) else pcm
        TranscriberClient.transcribe(WavWriter.encode(audio), target) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else if (result.error == null) {
                handleTranscriptionResult(null)
            } else {
                fail(pcm, ErrorMessages.cloud(result.error, result.statusCode))
            }
        }
    }

    private fun trimSilence(samples: FloatArray): FloatArray? = synchronized(trimmerLock) {
        if (silenceTrimmer == null && !trimmerFailed) {
            silenceTrimmer = SilenceTrimmer.create(SilenceTrimmer.modelFile(this))
            trimmerFailed = silenceTrimmer == null
        }
        // Without a working VAD the recording is used as is.
        silenceTrimmer?.trim(samples) ?: if (trimmerFailed) samples else null
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return samples
    }

    private fun floatsToPcm(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    /** Show [message]. With [pcm], the recording is kept and the button offers a retry. */
    private fun fail(pcm: ByteArray?, message: String) {
        failedPcm = pcm
        if (pcm != null) {
            enter(State.RETRY)
            showFeedback("$message\nTap to retry, hold to discard", 5000)
        } else {
            enter(State.IDLE)
            showFeedback(message, 4000)
        }
    }

    /** Back to idle, optionally with a short message. */
    private fun finish(message: String? = null) {
        enter(State.IDLE)
        message?.let { showFeedback(it) }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) { finish("No speech detected"); return }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val target = prefs().postProcessingTarget()

        if (!usePostProcessing) {
            handler.post { injectText(text); finish() }
            return
        }
        if (!target.isConfigured) {
            handler.post {
                injectText(text, feedback = "Cleanup is not set up. Raw text used.")
                finish()
            }
            return
        }

        val stored = prefs().getString("post_processing_prompt", null)
        val prompt = PostProcessor.migratePrompt(stored) ?: PostProcessor.DEFAULT_PROMPT
        PostProcessor.process(text, prompt, target) { result ->
            handler.post {
                if (result.text != null && result.text.isNotBlank()) {
                    injectText(result.text)
                } else {
                    injectText(text, feedback = "Cleanup failed: ${ErrorMessages.cloud(result.error, null)}. Raw text used.")
                }
                finish()
            }
        }
    }

    private fun vibrate() {
        if (!prefs().haptics()) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    // --- Text injection ---

    /**
     * Insert [text] into the focused field. By default the text goes through the clipboard and is
     * pasted. With "keep clipboard" on, it is written into the field directly (`ACTION_SET_TEXT`)
     * when the caret position is known, and only pasted via the clipboard as a fallback.
     * [feedback] replaces the default message shown after a successful insert.
     */
    private fun injectText(text: String, feedback: String? = null) {
        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = prefs().preserveClipboard() && candidates.any { setTextAtCaret(it, text, strict = true) }
        val usedClipboard = !injected
        if (!injected) {
            copyToClipboard(text)
            injected = candidates.any { pasteInto(it) || setTextAtCaret(it, text, strict = false) }
        }
        Log.i(TAG, if (injected) "Text injection succeeded" else "No injection action succeeded; clipboard fallback only")

        val message = when {
            candidates.isEmpty() -> "No text field focused — copied to clipboard"
            !injected -> "Could not insert — copied to clipboard"
            feedback != null -> feedback
            usedClipboard -> "Copied to clipboard"
            else -> null
        }
        message?.let { showFeedback(it, if (feedback != null || !injected) 3500 else 2000) }
    }

    /** Copy the transcript, marked sensitive so Android hides it in the clipboard preview. */
    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            putBoolean(key, true)
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    /**
     * Only the field that has focus is a target. Trying every text field in the window could
     * paste into an unrelated one (a search bar, say) when pasting into the focused one fails.
     */
    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows?.filter { it.isActive || it.isFocused }?.forEach { window -> window.root?.let { add(it) } }
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(::isTextTarget)?.let { candidates += it }
            collectFocusedTargets(root, candidates)
        }
        return candidates.distinct().sortedByDescending(::candidateScore)
    }

    private fun collectFocusedTargets(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isFocused && isTextTarget(node)) out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectFocusedTargets(it, out) }
        }
    }

    private fun isTextTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun pasteInto(node: AccessibilityNodeInfo): Boolean {
        logNode("Paste into", node)
        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $ok")
        return ok
    }

    /**
     * Write the field's new text directly. With [strict], only when the caret position is known
     * (see [textAfterInsert]); otherwise the text is appended, as a last resort after pasting failed.
     */
    private fun setTextAtCaret(node: AccessibilityNodeInfo, text: String, strict: Boolean): Boolean {
        if (!node.isEditable && node.className?.toString()?.contains("EditText") != true) return false
        val current = node.text?.toString().orEmpty()
        val updated = textAfterInsert(
            current, node.hintText?.toString(), node.isShowingHintText,
            node.textSelectionStart, node.textSelectionEnd, text
        ) ?: if (strict) return false else current + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "ACTION_SET_TEXT (strict=$strict) => $ok")
        return ok
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    /** Logs the field's shape, never its content: the text belongs to other apps. */
    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} textLength=${node.text?.length ?: 0} actions=[$actions]"
        )
    }

    private fun prefs() = Prefs.of(this)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
