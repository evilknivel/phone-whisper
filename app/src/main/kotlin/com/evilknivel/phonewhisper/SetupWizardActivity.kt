package com.evilknivel.phonewhisper

import android.Manifest
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.evilknivel.phonewhisper.Prefs.cleanupMode
import java.util.Locale

/**
 * First-run setup: explains the app, then walks through the microphone permission, offline or
 * online transcription, a model or a provider with its key, AI cleanup, and the accessibility
 * service (with the disclosure Google Play requires). Every choice is written to the same prefs
 * the settings screens use, so the assistant can be run again at any time.
 */
class SetupWizardActivity : SettingsScreen() {

    private enum class Step { WELCOME, MICROPHONE, MODE, LOCAL_MODEL, CLOUD, CLEANUP, ACCESSIBILITY, DONE }

    private var step = Step.WELCOME

    private val useLocal get() = prefs().getBoolean("use_local", true)

    private val downloadListener: (Model, DownloadState) -> Unit = { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getString("step")?.let { Step.valueOf(it) } ?: Step.WELCOME
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val previous = previousStep()
                if (previous == null) finish() else show(previous)
            }
        })
        show(step)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("step", step.name)
    }

    override fun onResume() {
        ModelDownloader.addListener(downloadListener)
        // Coming back from the accessibility settings: show whether the service now runs.
        WhisperAccessibilityService.onRunningChanged = { refresh() }
        super.onResume()
    }

    override fun onPause() {
        ModelDownloader.removeListener(downloadListener)
        WhisperAccessibilityService.onRunningChanged = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Navigation ---

    /** Local mode skips the cloud step and vice versa. */
    private fun visibleSteps() = Step.entries.filter {
        when (it) {
            Step.LOCAL_MODEL -> useLocal
            Step.CLOUD -> !useLocal
            else -> true
        }
    }

    private fun nextStep() = visibleSteps().let { steps -> steps.getOrNull(steps.indexOf(step) + 1) }
    private fun previousStep() = visibleSteps().let { steps -> steps.getOrNull(steps.indexOf(step) - 1) }

    private fun show(newStep: Step) {
        step = newStep
        // API keys are typed on the cloud and cleanup steps: keep them out of screenshots.
        if (step == Step.CLOUD || step == Step.CLEANUP) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        when (step) {
            Step.WELCOME -> welcome()
            Step.MICROPHONE -> microphone()
            Step.MODE -> mode()
            Step.LOCAL_MODEL -> localModel()
            Step.CLOUD -> cloud()
            Step.CLEANUP -> cleanup()
            Step.ACCESSIBILITY -> accessibility()
            Step.DONE -> done()
        }
        refresh()
    }

    private fun finishSetup() {
        prefs().edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        finish()
    }

    /** Step counter, the step's content, and Back / Next at the bottom. */
    private fun page(title: String, nextLabel: String = "Next", onNext: () -> Boolean = { true }, content: LinearLayout.() -> Unit) {
        val steps = visibleSteps()
        setScreen(title) {
            addView(note("Step ${steps.indexOf(step) + 1} of ${steps.size}"))
            content()
            addView(LinearLayout(this@SetupWizardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(24), dp(24), dp(24), dp(32))
                if (previousStep() != null) {
                    addView(MaterialButton(this@SetupWizardActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Back"
                        setOnClickListener { previousStep()?.let { show(it) } }
                    })
                }
                addView(MaterialButton(this@SetupWizardActivity).apply {
                    text = nextLabel
                    setOnClickListener {
                        if (!onNext()) return@setOnClickListener
                        val next = nextStep()
                        if (next == null) finishSetup() else show(next)
                    }
                })
            })
        }
    }

    private fun text(body: String) = TextView(this).apply {
        text = body
        textSize = 16f
        setTextColor(attrColor(android.R.attr.textColorPrimary))
        setPadding(dp(24), dp(8), dp(24), dp(8))
        setLineSpacing(0f, 1.2f)
    }

    /** A radio option row; [selected] is re-read on every refresh. */
    private fun option(title: String, subtitle: String, selected: () -> Boolean, onSelect: () -> Unit): LinearLayout {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }
        onRefresh { radio.isChecked = selected() }
        return settingsRow(title, subtitle, radio) { onSelect(); refresh() }
    }

    // --- Steps ---

    private fun welcome() = page("Welcome", nextLabel = "Start setup") {
        addView(text(
            "Phone Whisper lets you dictate into almost any app, without changing your keyboard.\n\n" +
                "• A small button appears while a keyboard is open.\n" +
                "• Tap it, speak, tap again: your words appear in the text field.\n" +
                "• Hold it instead to have an AI polish punctuation and grammar.\n\n" +
                "This assistant sets up the microphone, where your speech is turned into text, " +
                "optional AI cleanup, and the permission to insert text. It takes about two minutes."
        ))
        addView(settingsRow("Skip setup", "Set everything up yourself in the settings") { finishSetup() })
    }

    private fun microphone() = page("Microphone") {
        addView(text("Phone Whisper records only while you dictate: from your tap until your second tap."))
        val status = settingsRow("Allow microphone", "") {
            ActivityCompat.requestPermissions(this@SetupWizardActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val sub = status.subtitle()
        onRefresh {
            sub.visibility = View.VISIBLE
            sub.text = if (hasPerm(Manifest.permission.RECORD_AUDIO)) "✓ Allowed" else "Tap to allow"
        }
        addView(status)
    }

    private fun mode() = page("Offline or online?") {
        addView(text("Where should your speech be turned into text?"))
        addView(option(
            "Offline, on this phone",
            "Private and free. Needs a one-time download of 130–670 MB. Very good for German and English.",
            selected = { useLocal },
        ) { setLocal(true) })
        addView(option(
            "Online, with a cloud service",
            "Highest accuracy for many languages and no download. Needs an API key (OpenAI, OpenRouter or your own server) and costs a little per minute.",
            selected = { !useLocal },
        ) { setLocal(false) })
        addView(note("You can change this any time in the settings."))
    }

    private fun setLocal(local: Boolean) {
        prefs().edit().putBoolean("use_local", local).apply()
        WhisperAccessibilityService.instance?.reloadModel()
    }

    private fun localModel() = page("Choose a model", onNext = {
        val installed = MODEL_CATALOG.any { ModelDownloader.isInstalled(this, it) || ModelDownloader.state(it) != null }
        if (!installed) toast("Download a model first, or go back and choose online")
        installed
    }) {
        addView(text("Tap a model to download and use it. The download continues if you go on."))
        val recommended = if (Locale.getDefault().language == "en") "Parakeet 110M" else "Parakeet 0.6B"
        for (model in MODEL_CATALOG.filter { !it.legacy }) addView(modelOption(model, model.name == recommended))
        addView(note("Parakeet 0.6B and Ultra understand German and 23 more European languages; Whisper 99 languages; Parakeet 110M and Moonshine English only."))
    }

    private fun modelOption(model: Model, recommended: Boolean): LinearLayout {
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }
        val title = if (recommended) "${model.name} (recommended)" else model.name
        val row = option(title, "", selected = { prefs().getString("model_name", "") == model.archive }) {
            prefs().edit().putString("model_name", model.archive).apply()
            if (ModelDownloader.isInstalled(this, model)) {
                WhisperAccessibilityService.instance?.reloadModel()
            } else {
                if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                DownloadService.start(this, model)
            }
        }
        (row.getChildAt(0) as LinearLayout).addView(progress)
        val sub = row.subtitle()
        onRefresh {
            sub.visibility = View.VISIBLE
            val download = ModelDownloader.state(model)
            progress.visibility = if (download != null) View.VISIBLE else View.GONE
            sub.text = when {
                download is DownloadState.Downloading -> {
                    progress.isIndeterminate = false
                    progress.progress = (download.progress * 100).toInt()
                    "Downloading: ${(download.progress * 100).toInt()}%"
                }
                download is DownloadState.Extracting -> { progress.isIndeterminate = true; "Installing..." }
                ModelDownloader.isInstalled(this, model) -> "✓ Installed · ${model.languages}"
                else -> "${model.sizeMb} MB · ${model.languages}"
            }
        }
        return row
    }

    private fun cloud() = page("Cloud service", onNext = { saveCloud(CloudTask.TRANSCRIPTION) }) {
        addView(text("Pick a service and paste your API key. Your key stays on this phone."))
        providerOptions(CloudTask.TRANSCRIPTION)
        keyFields(CloudTask.TRANSCRIPTION)
        addView(note(
            "OpenAI: platform.openai.com → API keys. OpenRouter: openrouter.ai → Keys (many models, one key). " +
                "Custom: any OpenAI-compatible server, e.g. Groq or a Whisper server on your computer."
        ))
    }

    private fun cleanup() = page("AI cleanup", onNext = {
        when (prefs().cleanupMode()) {
            Prefs.CLEANUP_NEVER -> true
            Prefs.CLEANUP_ALWAYS -> saveCloud(CloudTask.POST_PROCESSING)
            // Holding without a key just inserts the raw text, so a key can be added later.
            else -> saveCloud(CloudTask.POST_PROCESSING, quiet = true).also {
                if (!it) toast("No key yet: held dictations stay unpolished until you add one under API keys")
            } || true
        }
    }) {
        addView(text(
            "A language model can polish your dictation: punctuation, capitalization, grammar and " +
                "misheard words. It keeps your language. This sends the text (not the audio) to a cloud service."
        ))
        for ((mode, label) in Prefs.CLEANUP_CHOICES) {
            val subtitle = when (mode) {
                Prefs.CLEANUP_HOLD -> "Tap the button for plain dictation, hold it for a polished one (recommended)"
                Prefs.CLEANUP_ALWAYS -> "Every dictation is polished"
                else -> "Dictations are inserted as recognized"
            }
            addView(option(label, subtitle, selected = { prefs().cleanupMode() == mode }) {
                prefs().edit().putString(Prefs.CLEANUP_MODE, mode).apply()
                // The provider and key fields below only matter with cleanup.
                show(Step.CLEANUP)
            })
        }
        if (prefs().cleanupMode() != Prefs.CLEANUP_NEVER) {
            if (!useLocal && !prefs().contains(CloudTask.POST_PROCESSING.providerPref)) {
                // Online users most likely want the same service for cleanup.
                prefs().edit().putString(
                    CloudTask.POST_PROCESSING.providerPref, prefs().transcriptionTarget().provider.prefValue
                ).apply()
            }
            addView(sectionHeader("Service for cleanup"))
            providerOptions(CloudTask.POST_PROCESSING)
            keyFields(CloudTask.POST_PROCESSING)
        }
    }

    private fun accessibility() = page("Insert text into apps") {
        addView(text(AccessibilityDisclosure.TEXT))
        val agree = CheckBox(this@SetupWizardActivity).apply {
            text = "I agree"
            isChecked = AccessibilityDisclosure.hasConsent(this@SetupWizardActivity)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply { marginStart = dp(16) }
        }
        addView(agree)
        // Apps installed from a file need "Allow restricted settings" before Android lets the
        // service be switched on, so that comes first.
        addView(settingsRow("1. Allow restricted settings", "Open App info, tap ⋮ in the top right, choose \"Allow restricted settings\"") {
            AccessibilityDisclosure.openAppInfo(this@SetupWizardActivity)
        })
        addView(note(
            "Only needed if you installed the APK yourself. If the ⋮ menu doesn't offer it, go on with step 2: " +
                "some Android versions show it only after Android has refused once (\"App was denied access\")."
        ))
        val open = settingsRow("2. Turn on Phone Whisper", "") {
            if (!agree.isChecked) { toast("Please read and agree first"); return@settingsRow }
            AccessibilityDisclosure.giveConsent(this@SetupWizardActivity)
            AccessibilityDisclosure.openSettings(this@SetupWizardActivity)
        }
        val sub = open.subtitle()
        onRefresh {
            sub.visibility = View.VISIBLE
            sub.text = if (WhisperAccessibilityService.instance != null) "✓ Phone Whisper is enabled"
                else "Opens the accessibility settings: find \"Phone Whisper\" under installed apps and turn it on"
        }
        addView(open)
    }

    private fun done() = page("All set", nextLabel = "Finish") {
        addView(text(
            "Open any app and tap into a text field. The Phone Whisper button appears next to the keyboard.\n\n" +
                "• Tap: dictate. Tap again to stop.\n" +
                "• Hold: dictate with AI cleanup (the button turns purple).\n" +
                "• Orange button: something went wrong. Tap to retry, or tap the ✕ next to it to discard.\n\n" +
                "The status at the top of the settings tells you if anything is still missing. " +
                "You can run this assistant again from the settings."
        ))
    }

    // --- Cloud helpers ---

    private fun LinearLayout.providerOptions(task: CloudTask) {
        for (provider in CloudProvider.entries) {
            addView(option(provider.label, "", selected = { prefs().cloudTarget(task).provider == provider }) {
                prefs().edit().putString(task.providerPref, provider.prefValue).apply()
                show(step)
            })
        }
    }

    private var keyInput: EditText? = null
    private var addressInput: EditText? = null

    /** Key field (masked, never prefilled) and, for a custom server, the address. */
    private fun LinearLayout.keyFields(task: CloudTask) {
        val target = prefs().cloudTarget(task)
        addressInput = if (target.provider == CloudProvider.CUSTOM) EditText(this@SetupWizardActivity).apply {
            hint = "Server address, e.g. https://api.groq.com/openai/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(prefs().getString(CUSTOM_BASE_URL_PREF, ""))
        }.also { addView(it, fieldParams()) } else null
        keyInput = EditText(this@SetupWizardActivity).apply {
            hint = when {
                target.apiKey.isNotBlank() -> "Key saved. Paste a new one to replace it"
                target.provider == CloudProvider.CUSTOM -> "API key (optional)"
                else -> "${target.provider.label} API key"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSingleLine = true
        }.also { addView(it, fieldParams()) }
    }

    private fun fieldParams() = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
        marginStart = dp(24); marginEnd = dp(24); topMargin = dp(8)
    }

    /** Store what was typed; returns false (stay on the step) while the provider isn't usable. */
    private fun saveCloud(task: CloudTask, quiet: Boolean = false): Boolean {
        val provider = prefs().cloudTarget(task).provider
        val edit = prefs().edit()
        keyInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { edit.putString(provider.keyPref, it) }
        addressInput?.text?.toString()?.trim()?.let { edit.putString(CUSTOM_BASE_URL_PREF, it) }
        edit.apply()
        val target = prefs().cloudTarget(task)
        if (!target.isConfigured) {
            if (!quiet) toast(if (target.baseUrl.isBlank()) "Enter the server address" else "Paste your ${provider.label} API key")
            return false
        }
        return true
    }
}
