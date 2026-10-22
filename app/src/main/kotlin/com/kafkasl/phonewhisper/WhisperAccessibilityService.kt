package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    @Volatile private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
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

    /** Audio of the last recording that could not be transcribed; long-press the button to retry it. */
    @Volatile private var failedPcm: ByteArray? = null

    // Local transcription engine. Loading and releasing run on [modelExecutor], one at a time;
    // [transcriberLock] keeps a model from being released while it transcribes.
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val transcriberLock = Any()
    @Volatile private var localTranscriber: LocalTranscriber? = null
    private val pendingLoads = AtomicInteger()
    private val modelLoading get() = pendingLoads.get() > 0

    private val dp get() = resources.displayMetrics.density

    private val screenSize: Pair<Int, Int>
        get() {
            val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        reloadModel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Window metrics are updated after the configuration callback.
        handler.post { placeOverlay() }
    }

    override fun onDestroy() {
        instance = null
        state = State.IDLE
        releaseRecorder()
        removeOverlay()
        modelExecutor.execute { unloadModel() }
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
        val threads = prefs().getInt("num_threads", LocalTranscriber.DEFAULT_THREADS)
        return LocalTranscriber.create(File(LocalTranscriber.modelsDir(this), name), threads)
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

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
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
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        positionOverlay(params)
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
    }

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

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
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

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun onLongPress() {
        val pcm = failedPcm
        when {
            state != State.IDLE -> onTap()
            pcm == null -> startRecording()
            else -> {
                failedPcm = null
                setTranscribing()
                transcribe(pcm)
            }
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

        // Creating or starting the recorder fails when another app (or a call) holds the microphone.
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
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
        state = State.RECORDING
        failedPcm = null
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        recorderThread = thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) stream.write(buf, 0, n) else if (n < 0) break
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

    private fun setTranscribing() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)
    }

    private fun stopAndTranscribe() {
        setTranscribing()
        releaseRecorder()

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }
        transcribe(pcm)
    }

    /**
     * Local mode never falls back to the cloud: if the model is still loading or missing, the
     * recording is kept for a retry instead of being sent off the device.
     */
    private fun transcribe(pcm: ByteArray) {
        if (!prefs().getBoolean("use_local", true)) { transcribeApi(pcm); return }
        when {
            localTranscriber != null -> transcribeLocal(pcm)
            modelLoading -> fail(pcm, "Local model is still loading")
            else -> fail(null, "No local model. Download one in the Phone Whisper app.")
        }
    }

    private fun transcribeLocal(pcm: ByteArray) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val text = synchronized(transcriberLock) {
                    val transcriber = localTranscriber ?: return@thread fail(pcm, "Local model is still loading")
                    val t0 = System.currentTimeMillis()
                    transcriber.transcribe(samples, SAMPLE_RATE).also {
                        val ms = System.currentTimeMillis() - t0
                        Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")
                    }
                }

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                fail(pcm, "Local error: ${e.message}")
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val target = prefs().transcriptionTarget()
        if (target.apiKey.isBlank()) { fail(pcm, "Set ${target.provider.label} API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(WavWriter.encode(pcm), target) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else if (result.error == null) {
                handleTranscriptionResult(null)
            } else {
                fail(pcm, "Error: ${result.error}")
            }
        }
    }

    /** Show [message] and return to idle. With [pcm], the recording is kept for a long-press retry. */
    private fun fail(pcm: ByteArray?, message: String) {
        failedPcm = pcm
        val hint = if (pcm != null) "\nHold the button to retry" else ""
        showFeedback(message + hint, 4000)
        handler.post {
            state = State.IDLE
            setBusy(false)
            setAppearance(COLOR_IDLE)
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val target = prefs().postProcessingTarget()

        if (usePostProcessing) {
            if (target.apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs ${target.provider.label} API key. Using raw text.")
                    injectText(text)
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

            PostProcessor.process(text, prompt, target) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw text used", feedbackDurationMs = 3000)
                    }
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        val injected = candidates.any { tryInjectIntoNode(it, text) }
        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")

        when {
            injected || candidates.isNotEmpty() -> feedback?.let { showFeedback(it, feedbackDurationMs) }
            else -> showFeedback("No text field focused — copied to clipboard", 3000)
        }
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
            Log.i(TAG, "Root: package=${root.packageName} class=${root.className}")
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

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end).coerceAtMost(current.length)
            val replacementEnd = maxOf(start, end).coerceAtMost(current.length)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
