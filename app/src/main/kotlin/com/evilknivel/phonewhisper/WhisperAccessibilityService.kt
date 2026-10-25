package com.evilknivel.phonewhisper

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
import android.graphics.Rect
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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.evilknivel.phonewhisper.Prefs.cancelOnScreenOff
import com.evilknivel.phonewhisper.Prefs.cleanupMode
import com.evilknivel.phonewhisper.Prefs.edgeGlow
import com.evilknivel.phonewhisper.Prefs.haptics
import com.evilknivel.phonewhisper.Prefs.buttonPosition
import com.evilknivel.phonewhisper.Prefs.idleReleaseMinutes
import com.evilknivel.phonewhisper.Prefs.keepScreenOn
import com.evilknivel.phonewhisper.Prefs.language
import com.evilknivel.phonewhisper.Prefs.micSource
import com.evilknivel.phonewhisper.Prefs.numThreads
import com.evilknivel.phonewhisper.Prefs.overlayKeyboardOnly
import com.evilknivel.phonewhisper.Prefs.preserveClipboard
import com.evilknivel.phonewhisper.Prefs.putButtonPosition
import com.evilknivel.phonewhisper.Prefs.translationLanguages
import com.evilknivel.phonewhisper.Prefs.trimSilence
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
        private const val FEEDBACK_GAP_DP = 8
        private const val HOLD_RING_DP = 128
        private const val DISCARD_DP = 36
        /** Taps are shorter than this, so the hold ring doesn't flash on every tap. */
        private const val HOLD_RING_DELAY_MS = 150L
        /**
         * How long the button must be held. Longer than Android's long-press timeout (~0.4 s), so
         * the ring visibly fills before the hold triggers.
         */
        private const val HOLD_MS = 800L
        private const val VISIBILITY_DEBOUNCE_MS = 100L
        private const val KEYBOARD_WATCHDOG_MS = 1000L
        private const val LEVEL_UPDATE_MS = 50L

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_RECORDING_CLEANUP = 0xDD8B5CF6.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_RETRY = 0xDDF59E0B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    /** RETRY: the last recording failed and is kept; tap retries it, the ✕ next to it (or a long press) discards it. */
    private enum class State { IDLE, RECORDING, TRANSCRIBING, RETRY }

    @Volatile private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var levelBars: LevelBarsView? = null
    private var languageBadge: TextView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var holdRing: HoldRingView? = null
    private var holdRingParams: WindowManager.LayoutParams? = null
    private var holdRingAnimator: android.animation.ValueAnimator? = null
    private var discardView: TextView? = null
    private var discardParams: WindowManager.LayoutParams? = null
    private var edgeGlow: EdgeGlowView? = null
    private var audioRecord: AudioRecord? = null
    private var recorderThread: Thread? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Edge and height of the button in the current orientation, read from [Prefs.buttonPosition].
    private var onRightEdge = true
    private var yFraction = 0.5f

    /** Audio of the last recording that could not be transcribed, kept for a retry. */
    @Volatile private var failedPcm: ByteArray? = null

    /**
     * Whether the current dictation (and its retry) is cleaned up by the chat model: always in
     * "always" mode, and in "hold" mode when the recording was started by holding the button.
     */
    @Volatile private var cleanupThisDictation = false

    /** Target language of the current dictation (and its retry), picked on the arc; null = no translation. */
    @Volatile private var translateTo: String? = null

    /** What a chip on the arc does. */
    private sealed class ArcChoice {
        data class Language(val code: String) : ArcChoice()
        object AddLanguage : ArcChoice()
        object Settings : ArcChoice()
    }

    private var radialMenu: RadialMenuView? = null
    private var radialParams: WindowManager.LayoutParams? = null
    private var arcChoices: List<ArcChoice> = emptyList()

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
            // Whisper takes the language at load time.
            Prefs.LANGUAGE -> if (prefs().getBoolean("use_local", true)) reloadModel()
        }
    }

    private val dp get() = resources.displayMetrics.density

    /**
     * The screen without the status bar, the navigation bar (wherever it sits: at the bottom, or
     * at the side in landscape) and display cutouts. The button and its messages stay inside it,
     * so they never cover the system's back, home and recents buttons.
     */
    private val safeArea: Rect
        get() {
            val metrics = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(metrics.bounds).apply {
                left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
            }
        }

    private fun isLandscape(): Boolean =
        (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.let { it.width() > it.height() }

    /** Overlay windows are placed in screen coordinates, the same ones [safeArea] uses. */
    private fun WindowManager.LayoutParams.inScreenCoordinates() = apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        setFitInsetsTypes(0)
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
        return LocalTranscriber.create(File(LocalTranscriber.modelsDir(this), name), prefs().numThreads(), prefs().language())
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

        // The target language of a translating dictation, at the bottom of the button.
        val badge = TextView(this).apply {
            textSize = 9f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            background = pill(COLOR_FEEDBACK_BG)
            visibility = View.GONE
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(bars, FrameLayout.LayoutParams((BARS_DP * dp).toInt(), (BARS_DP * dp).toInt(), Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }.inScreenCoordinates()

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        // The long press fires while the finger is still down, so holding gives immediate feedback.
        var longPressed = false
        val longPress = Runnable { longPressed = true; onLongPress() }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressed = false
                    handler.postDelayed(longPress, HOLD_MS)
                    startHoldRing()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - touchX) + abs(ev.rawY - touchY) >= TAP_THRESHOLD_DP * dp) {
                        handler.removeCallbacks(longPress)
                        stopHoldRing()
                    }
                    if (longPressed) {
                        trackArc(ev.rawX - touchX, ev.rawY - touchY)
                        return@setOnTouchListener true
                    }
                    val safe = safeArea
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = (startY + (ev.rawY - touchY).toInt()).coerceIn(safe.top, maxOf(safe.top, safe.bottom - ringSize))
                    wm.updateViewLayout(v, params)
                    updateFeedbackPosition()
                    updateSatellitePositions()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    stopHoldRing()
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (longPressed) {
                        releaseArc(if (ev.action == MotionEvent.ACTION_UP) radialMenu?.highlighted ?: -1 else -1)
                    } else if (ev.action == MotionEvent.ACTION_CANCEL) {
                        // Nothing was started.
                    } else if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        val safe = safeArea
                        val position = Prefs.ButtonPosition(
                            rightEdge = params.x + ringSize / 2 > safe.centerX(),
                            yFraction = (params.y + ringSize / 2f - safe.top) / maxOf(1, safe.height()),
                        )
                        prefs().edit().putButtonPosition(isLandscape(), position).apply()
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
        }.inScreenCoordinates()

        overlayView = overlay
        button = img
        levelBars = bars
        languageBadge = badge
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
        positionOverlay(params)
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        addSatellites(wm)
        updateOverlayVisibility()
    }

    /** Windows next to the button: the hold ring, the discard button and the edge glow. */
    private fun addSatellites(wm: WindowManager) {
        val untouchable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            // The ring may reach past the screen edge; without this Android would shift it off-center.
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val ringSize = (HOLD_RING_DP * dp).toInt()
        val ring = HoldRingView(this).apply { visibility = View.GONE }
        val ringParams = WindowManager.LayoutParams(
            ringSize, ringSize, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, untouchable, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }.inScreenCoordinates()

        val discardSize = (DISCARD_DP * dp).toInt()
        val discard = TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = circle(COLOR_IDLE)
            contentDescription = "Discard recording"
            visibility = View.GONE
            setOnClickListener { discardFailed() }
        }
        val discardLp = WindowManager.LayoutParams(
            discardSize, discardSize, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Starts hidden, so untouchable; setDiscardVisible switches both.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }.inScreenCoordinates()

        val glow = EdgeGlowView(this).apply { visibility = View.GONE }
        val glowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            untouchable or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }

        val menuSize = (RadialMenuView.SIZE_DP * dp).toInt()
        val menu = RadialMenuView(this).apply {
            highlightColor = COLOR_RECORDING_CLEANUP
            visibility = View.GONE
        }
        val menuParams = WindowManager.LayoutParams(
            menuSize, menuSize, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, untouchable, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }.inScreenCoordinates()

        holdRing = ring; holdRingParams = ringParams
        discardView = discard; discardParams = discardLp
        edgeGlow = glow
        radialMenu = menu; radialParams = menuParams
        positionSatellites()
        wm.addView(glow, glowParams)
        wm.addView(ring, ringParams)
        wm.addView(menu, menuParams)
        wm.addView(discard, discardLp)
    }

    /** Center the hold ring on the button and put the discard button on its inner side. */
    private fun positionSatellites() {
        val bubble = layoutParams ?: return
        val buttonSize = (RING_DP * dp).toInt()
        val cx = bubble.x + buttonSize / 2
        val cy = bubble.y + buttonSize / 2
        for (p in listOfNotNull(holdRingParams, radialParams)) {
            p.x = cx - p.width / 2
            p.y = cy - p.height / 2
        }
        discardParams?.let { p ->
            val gap = (4 * dp).toInt()
            p.x = if (onRightEdge) bubble.x - p.width - gap else bubble.x + buttonSize + gap
            p.y = cy - p.height / 2
        }
    }

    private fun updateSatellitePositions() {
        positionSatellites()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        holdRing?.let { v -> holdRingParams?.let { wm.updateViewLayout(v, it) } }
        radialMenu?.let { v -> radialParams?.let { wm.updateViewLayout(v, it) } }
        discardView?.let { v -> discardParams?.let { wm.updateViewLayout(v, it) } }
    }

    /**
     * Grow the ring while the button is held, in the color of what the hold will do: purple for a
     * dictation with AI cleanup (red without), orange for discarding a failed recording. Nothing
     * while recording or transcribing. It starts after a short delay so taps don't flash it.
     */
    private fun startHoldRing() {
        val ring = holdRing ?: return
        ring.color = when (state) {
            State.IDLE -> if (prefs().cleanupMode() != Prefs.CLEANUP_NEVER) COLOR_RECORDING_CLEANUP else COLOR_RECORDING
            State.RETRY -> COLOR_RETRY
            else -> return
        }
        val total = HOLD_MS
        holdRingAnimator?.cancel()
        holdRingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = HOLD_RING_DELAY_MS
            duration = (total - HOLD_RING_DELAY_MS).coerceAtLeast(100)
            addUpdateListener { ring.progress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    ring.progress = 0f
                    ring.alpha = 1f
                    ring.visibility = View.VISIBLE
                }
            })
            start()
        }
    }

    /** The screen must not time out while the user dictates or waits for the text. */
    private fun setKeepScreenOn(on: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if ((params.flags and flag != 0) == on) return
        params.flags = if (on) params.flags or flag else params.flags and flag.inv()
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
    }

    /** Like the button, the hidden discard window must not take touches meant for the app below. */
    private fun setDiscardVisible(visible: Boolean) {
        val view = discardView ?: return
        val params = discardParams ?: return
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == visibility) return
        view.visibility = visibility
        params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
    }

    /** Hide the ring; after a completed hold it stays full for a moment as confirmation. */
    private fun stopHoldRing() {
        val ring = holdRing ?: return
        holdRingAnimator?.cancel()
        holdRingAnimator = null
        if (ring.visibility != View.VISIBLE) return
        val delay = if (ring.progress >= 1f) 400L else 0L
        ring.animate().alpha(0f).setStartDelay(delay).setDuration(150).withEndAction {
            ring.visibility = View.GONE
            ring.alpha = 1f
        }.start()
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

    /**
     * Snap the button to its edge at its relative height, inside the [safeArea]: in landscape the
     * navigation bar sits at the side, and the button stays clear of it. Edge and height are
     * stored per orientation (see [Prefs.buttonPosition]).
     */
    private fun positionOverlay(params: WindowManager.LayoutParams) {
        prefs().buttonPosition(isLandscape()).let { onRightEdge = it.rightEdge; yFraction = it.yFraction }
        val safe = safeArea
        val ringSize = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        params.x = if (onRightEdge) safe.right - ringSize - margin else safe.left + margin
        params.y = (safe.top + yFraction * safe.height() - ringSize / 2f).toInt()
            .coerceIn(safe.top + margin, maxOf(safe.top + margin, safe.bottom - ringSize - margin))
    }

    private fun placeOverlay() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        positionOverlay(params)
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        updateFeedbackPosition()
        updateSatellitePositions()
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
        holdRingAnimator?.cancel()
        listOf(holdRing, radialMenu, discardView, edgeGlow).forEach { it?.let(wm::removeView) }
        holdRing = null; holdRingParams = null
        radialMenu = null; radialParams = null
        languageBadge = null
        discardView = null; discardParams = null
        edgeGlow = null
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

    /**
     * Messages go above the hold ring, clear of the thumb that holds the button (below it if the
     * button sits at the top), and run from the button's screen edge inwards.
     */
    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val view = feedbackView ?: return
        val safe = safeArea
        val margin = (MARGIN_DP * dp).toInt()
        view.maxWidth = (safe.width() * 0.8f).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(safe.width() - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val buttonCenterY = bubbleParams.y + (RING_DP * dp).toInt() / 2
        val ringRadius = (HOLD_RING_DP * dp).toInt() / 2
        val gap = (FEEDBACK_GAP_DP * dp).toInt()
        val above = buttonCenterY - ringRadius - gap - view.measuredHeight
        feedbackParams.x = if (onRightEdge) safe.right - margin - view.measuredWidth else safe.left + margin
        // Near the top of the screen there is no room above: show it below the ring instead.
        feedbackParams.y = if (above >= safe.top + margin) above else buttonCenterY + ringRadius + gap
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
            State.RECORDING -> (if (cleanupThisDictation) COLOR_RECORDING_CLEANUP else COLOR_RECORDING) to null
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
        setKeepScreenOn((state == State.RECORDING || state == State.TRANSCRIBING) && prefs().keepScreenOn())
        languageBadge?.apply {
            val target = translateTo
            visibility = if (target != null && state != State.IDLE) View.VISIBLE else View.GONE
            text = target?.uppercase().orEmpty()
        }
        setDiscardVisible(state == State.RETRY && overlayView?.visibility == View.VISIBLE)
        edgeGlow?.apply {
            visibility = if (state == State.RECORDING && prefs().edgeGlow()) View.VISIBLE else View.GONE
            setColor(if (cleanupThisDictation) COLOR_RECORDING_CLEANUP else COLOR_RECORDING)
            setLevel(0f)
        }
    }

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording(cleanup = prefs().cleanupMode() == Prefs.CLEANUP_ALWAYS)
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
            State.RETRY -> retry()
        }
    }

    /**
     * Holding the button starts a dictation with AI cleanup (unless cleanup is set to "never") and
     * opens the arc: sliding the thumb onto a language translates the dictation, "+" and the gear
     * open the settings.
     */
    private fun onLongPress() {
        when (state) {
            State.IDLE -> {
                val cleanup = prefs().cleanupMode() != Prefs.CLEANUP_NEVER
                startRecording(cleanup, held = true)
                if (state == State.RECORDING) showArc()
            }
            // Kept as a shortcut; the visible way is the ✕ next to the button.
            State.RETRY -> discardFailed()
            else -> onTap()
        }
    }

    // --- Arc of choices around the held button ---

    /**
     * Languages that can be offered: all selected ones with a cleanup provider, otherwise only
     * English (an installed Whisper translates it; without one, picking it explains what is missing).
     */
    private fun showArc() {
        val menu = radialMenu ?: return
        val aiConfigured = prefs().postProcessingTarget().isConfigured
        val languages = Translation.arcLanguages(prefs().translationLanguages(), prefs().language())
            .filter { aiConfigured || it == "en" }
        arcChoices = languages.map { ArcChoice.Language(it) } + ArcChoice.AddLanguage + ArcChoice.Settings
        menu.labels = arcChoices.map {
            when (it) {
                is ArcChoice.Language -> it.code.uppercase()
                ArcChoice.AddLanguage -> "+"
                ArcChoice.Settings -> "⚙"
            }
        }
        val bubble = layoutParams ?: return
        val safe = safeArea
        val cy = bubble.y + (RING_DP * dp) / 2
        val reach = menu.radius + RadialMenuView.CHIP_DP * dp
        val tilt = RadialMenu.tilt(roomAbove = cy - safe.top, roomBelow = safe.bottom - cy, reach = reach)
        menu.angles = RadialMenu.angles(arcChoices.size, onRightEdge, tilt)
        menu.highlighted = -1
        menu.alpha = 0f
        menu.visibility = View.VISIBLE
        menu.animate().alpha(1f).setDuration(120).start()
    }

    /** Highlight the chip under the thumb; ([dx], [dy]) is its offset from where the hold started. */
    private fun trackArc(dx: Float, dy: Float) {
        val menu = radialMenu?.takeIf { it.visibility == View.VISIBLE } ?: return
        val index = RadialMenu.hit(dx, dy, menu.angles, deadZone = menu.radius * 0.5f)
        if (index != menu.highlighted) {
            menu.highlighted = index
            if (index >= 0) vibrate(Haptic.TICK)
        }
    }

    /** The thumb was lifted over chip [index] (-1: near the button, a dictation without translation). */
    private fun releaseArc(index: Int) {
        val menu = radialMenu ?: return
        if (menu.visibility != View.VISIBLE) return
        menu.animate().cancel()
        menu.visibility = View.GONE
        val choice = arcChoices.getOrNull(index)
        arcChoices = emptyList()
        when (choice) {
            null -> if (cleanupThisDictation && state == State.RECORDING) showFeedback("Recording with AI cleanup", 1500)
            is ArcChoice.Language -> pickTranslation(choice.code)
            ArcChoice.AddLanguage -> openSettings(TranslationSettingsActivity::class.java)
            ArcChoice.Settings -> openSettings(MainActivity::class.java)
        }
    }

    private fun pickTranslation(code: String) {
        if (state != State.RECORDING) return
        val method = Translation.method(
            code,
            aiConfigured = prefs().postProcessingTarget().isConfigured,
            whisperInstalled = LocalTranscriber.whisperTranslators(this).isNotEmpty(),
        )
        if (method == null) {
            showFeedback("Translating needs an AI cleanup provider, or Whisper Base or Small for English", 4000)
            return
        }
        translateTo = code
        applyAppearance()
        val via = if (method == Translation.Method.WHISPER) " (Whisper, offline)" else ""
        showFeedback("Translating into ${Translation.languageName(code)}$via", 2000)
    }

    /** Opening the settings from the arc drops the recording that the hold started. */
    private fun openSettings(screen: Class<*>) {
        if (state == State.RECORDING) {
            state = State.TRANSCRIBING
            releaseRecorder()
            pcmStream = null
            enter(State.IDLE)
        }
        startActivity(Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun discardFailed() {
        if (state != State.RETRY) return
        failedPcm = null
        vibrate(Haptic.DISCARD)
        enter(State.IDLE)
        showFeedback("Recording discarded")
    }

    private fun startRecording(cleanup: Boolean, held: Boolean = false) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showFeedback("Grant audio permission in the Phone Whisper app", 3000); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) { showFeedback("Microphone not available", 3000); return }

        // VOICE_RECOGNITION is the source meant for speech recognition (no voice-call processing).
        val source = if (prefs().micSource() == Prefs.MIC_RAW) MediaRecorder.AudioSource.MIC
            else MediaRecorder.AudioSource.VOICE_RECOGNITION

        // Creating or starting the recorder fails when another app (or a call) holds the microphone.
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (_: SecurityException) { showFeedback("Audio permission denied", 3000); return }
        catch (e: IllegalArgumentException) { showFeedback("Microphone not available", 3000); return }

        val started = recorder.state == AudioRecord.STATE_INITIALIZED && try {
            recorder.startRecording()
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) { false }
        if (!started) {
            recorder.release()
            showFeedback("Microphone is busy", 3000)
            return
        }

        val stream = ByteArrayOutputStream()
        audioRecord = recorder
        pcmStream = stream
        failedPcm = null
        cleanupThisDictation = cleanup
        translateTo = null
        enter(State.RECORDING)
        vibrate(if (held && cleanup) Haptic.CLEANUP else Haptic.CLICK)

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
                        handler.post { levelBars?.setLevel(level); edgeGlow?.setLevel(level) }
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

        if (whisperTranslates()) {
            showFeedback("Translating with Whisper...", 1500)
            modelExecutor.execute { translateWithWhisper(pcm, samples) }
            return
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

    /** English as target and no cleanup provider: an installed Whisper translates (see [Translation]). */
    private fun whisperTranslates(): Boolean {
        val target = translateTo ?: return false
        return Translation.method(
            target, prefs().postProcessingTarget().isConfigured,
            LocalTranscriber.whisperTranslators(this).isNotEmpty(),
        ) == Translation.Method.WHISPER
    }

    /**
     * Runs on [modelExecutor]. The selected model translates if it is a multilingual Whisper;
     * otherwise an installed Whisper is loaded for this dictation only, in place of the selected
     * model (never both in memory), which is loaded again afterwards.
     */
    private fun translateWithWhisper(pcm: ByteArray, samples: FloatArray) {
        try {
            val text = synchronized(transcriberLock) {
                localTranscriber?.takeIf { it.canTranslateToEnglish }
                    ?.let { return@synchronized it.transcribe(samples, SAMPLE_RATE, translateToEnglish = true) }
                val name = LocalTranscriber.whisperTranslators(this).firstOrNull()
                    ?: return fail(pcm, "No Whisper model for offline translation")
                localTranscriber?.release()
                localTranscriber = null
                val whisper = LocalTranscriber.create(File(LocalTranscriber.modelsDir(this), name), prefs().numThreads(), prefs().language())
                    ?: return fail(pcm, "Could not load Whisper for translation")
                try {
                    whisper.transcribe(samples, SAMPLE_RATE, translateToEnglish = true)
                } finally {
                    whisper.release()
                    if (prefs().getBoolean("use_local", true)) localTranscriber = loadSelectedModel()
                }
            }
            handleTranscriptionResult(text, translated = true)
        } catch (e: Exception) {
            Log.e(TAG, "Whisper translation failed", e)
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
        TranscriberClient.transcribe(WavWriter.encode(audio), target, prefs().language()) { result ->
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
            showFeedback("$message\nTap to retry, ✕ to discard", 5000)
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

    /** [translated]: Whisper already translated it; there is no cleanup provider to do more. */
    private fun handleTranscriptionResult(text: String?, translated: Boolean = false) {
        if (text.isNullOrBlank()) { finish("No speech detected"); return }

        val target = prefs().postProcessingTarget()
        val language = translateTo?.takeUnless { translated }

        if (language != null) { translate(text, language, target); return }
        if (!cleanupThisDictation || translated) {
            handler.post { injectText(text); finish() }
            return
        }
        if (!target.isConfigured) {
            handler.post {
                injectText(text, feedback = "AI cleanup is not set up (provider or API key). Raw text used.")
                finish()
            }
            return
        }

        PostProcessor.process(text, currentCleanupPrompt(), target) { result ->
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

    /** The chat model cleans up and translates in one request; a failure inserts the original text. */
    private fun translate(text: String, language: String, target: CloudTarget) {
        if (!target.isConfigured) {
            handler.post {
                injectText(text, feedback = "Translation needs an AI cleanup provider. Original text used.")
                finish()
            }
            return
        }
        val prompt = Translation.prompt(currentCleanupPrompt(), prefs().language(), language)
        PostProcessor.process(text, prompt, target) { result ->
            handler.post {
                if (result.text != null && result.text.isNotBlank()) {
                    injectText(result.text)
                } else {
                    injectText(text, feedback = "Translation failed: ${ErrorMessages.cloud(result.error, null)}. Original text used.")
                }
                finish()
            }
        }
    }

    private fun currentCleanupPrompt(): String =
        PostProcessor.migratePrompt(prefs().getString("post_processing_prompt", null)) ?: PostProcessor.DEFAULT_PROMPT

    /** Distinct patterns, so the thumb that covers the button still feels what happened. */
    private enum class Haptic { CLICK, CLEANUP, DISCARD, TICK }

    private fun vibrate(kind: Haptic = Haptic.CLICK) {
        if (!prefs().haptics()) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(when (kind) {
            Haptic.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Haptic.CLEANUP -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            Haptic.DISCARD -> VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE)
            Haptic.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        })
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
}
