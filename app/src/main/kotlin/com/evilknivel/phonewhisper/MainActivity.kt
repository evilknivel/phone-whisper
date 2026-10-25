package com.evilknivel.phonewhisper

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.evilknivel.phonewhisper.Prefs.cleanupMode
import com.evilknivel.phonewhisper.Prefs.language
import com.evilknivel.phonewhisper.Prefs.translationLanguages

/**
 * The main settings screen: status, setup, transcription (cloud or local models) and cleanup.
 * Less common settings live on the sub-screens linked at the bottom.
 */
class MainActivity : SettingsScreen() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var cloudContainer: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton,
        val row: View,
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    private val downloadListener: (Model, DownloadState) -> Unit = { model, state ->
        runOnUiThread { onDownloadState(model, state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migratePrompts()

        setScreen("Phone Whisper") {
            val statusRow = settingsRow("Status", "Checking...")
            statusSubtitle = statusRow.subtitle()
            addView(statusRow)

            // --- Setup ---
            addView(sectionHeader("Setup"))
            val audioRow = settingsRow("Audio permission", "Checking...") {
                if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                }
            }
            audioRowSub = audioRow.subtitle()
            addView(audioRow)

            val accRow = settingsRow("Accessibility service", "Checking...") {
                AccessibilityDisclosure.open(this@MainActivity)
            }
            accRowSub = accRow.subtitle()
            addView(accRow)

            // --- Transcription ---
            addView(sectionHeader("Transcription"))
            addView(toggleRow(
                "Use cloud transcription", "OpenAI, OpenRouter or your own server",
                get = { !prefs().getBoolean("use_local", true) },
            ) { cloud ->
                prefs().edit().putBoolean("use_local", !cloud).apply()
                // Loads the local model, or frees it in cloud mode.
                WhisperAccessibilityService.instance?.reloadModel()
            })
            addView(choiceRow(
                "Your language",
                Prefs.LANGUAGES,
                current = { prefs().language() },
                // The service reloads a local Whisper model when this pref changes.
                onPick = { prefs().edit().putString(Prefs.LANGUAGE, it).apply() },
            ))
            addView(note(
                "The language you speak. Used by Whisper and cloud transcription, and as the source when " +
                    "translating. Set it if short dictations come out in the wrong language. Parakeet always " +
                    "detects the language itself."
            ))

            cloudContainer = vertical(0).apply {
                addView(providerRow(CloudTask.TRANSCRIPTION, "Transcription provider"))
                addView(modelRow(CloudTask.TRANSCRIPTION, "Transcription model"))
            }
            addView(cloudContainer)

            modelContainer = vertical(0).apply {
                for (m in MODEL_CATALOG) addView(buildModelRow(m))
                addView(note("Parakeet 110M and Moonshine understand English only."))
            }
            addView(modelContainer)

            // --- Cleanup ---
            addView(sectionHeader("AI cleanup"))
            addView(choiceRow(
                "Clean up with AI",
                Prefs.CLEANUP_CHOICES,
                current = { prefs().cleanupMode() },
                onPick = { prefs().edit().putString(Prefs.CLEANUP_MODE, it).apply() },
            ))
            addView(note(
                "A language model fixes punctuation, grammar and recognition errors. " +
                    "Tap the button for a plain dictation; hold it to dictate with cleanup (the button turns purple)."
            ))
            val translationRow = navRow("Translation", "", TranslationSettingsActivity::class.java)
            val translationSub = translationRow.subtitle()
            onRefresh {
                val selected = prefs().translationLanguages()
                translationSub.visibility = View.VISIBLE
                translationSub.text = if (selected.isEmpty()) "Hold, then slide onto a language: pick languages"
                    else "Hold, then slide onto: " + selected.joinToString(" · ") { it.uppercase() }
            }
            addView(translationRow)
            promptContainer = vertical(0).apply {
                addView(providerRow(CloudTask.POST_PROCESSING, "Cleanup provider"))
                addView(modelRow(CloudTask.POST_PROCESSING, "Cleanup model"))
                for (preset in promptPresets()) addView(buildPromptRow(preset))
                val editRow = settingsRow("Edit current prompt", "") { promptPostProcessing() }
                val editSub = editRow.subtitle().apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                onRefresh { editSub.visibility = View.VISIBLE; editSub.text = currentPrompt() }
                addView(editRow)
            }
            addView(promptContainer)

            // --- More ---
            addView(sectionHeader("More settings"))
            addView(navRow("Setup assistant", "Walk through the setup again", SetupWizardActivity::class.java))
            addView(navRow("API keys", "OpenAI, OpenRouter and custom server", ApiKeysActivity::class.java))
            addView(navRow("Button & insertion", "When the button shows, vibration, clipboard", ButtonSettingsActivity::class.java))
            addView(navRow("Advanced", "CPU threads, memory, silence trimming, microphone", AdvancedSettingsActivity::class.java))
            addView(settingsRow("About", "Phone Whisper ${appVersion()} · fork of kafkasl/phone-whisper") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            })
        }

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        Thread { ModelDownloader.pruneInstalled(applicationContext) }.start()
        startSetupOnFirstRun()
    }

    override fun onResume() {
        ModelDownloader.addListener(downloadListener)
        // The service often connects a moment after we return from the accessibility settings.
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

    /**
     * New installs start with the setup assistant. Installs that are already set up (from versions
     * before the assistant) are marked done instead of being walked through it again.
     */
    private fun startSetupOnFirstRun() {
        if (prefs().getBoolean(Prefs.SETUP_DONE, false)) return
        // The service may connect a moment after this screen starts, so "switched on" is enough.
        val configured = accessibilityState() != AccessibilityState.OFF && (
            if (prefs().getBoolean("use_local", true)) LocalTranscriber.availableModels(this).isNotEmpty()
            else prefs().transcriptionTarget().isConfigured
        )
        if (configured) prefs().edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        else startActivity(Intent(this, SetupWizardActivity::class.java))
    }

    /** Stored prompts that are old versions of a preset become the current preset. */
    private fun migratePrompts() {
        val edit = prefs().edit()
        for (key in listOf("post_processing_prompt", "custom_post_processing_prompt")) {
            val stored = prefs().getString(key, null)
            val migrated = PostProcessor.migratePrompt(stored)
            if (migrated != stored) edit.putString(key, migrated)
        }
        edit.apply()
    }

    // --- Cloud rows ---

    private fun providerRow(task: CloudTask, title: String) =
        choiceRow(
            title,
            CloudProvider.entries.map { it to it.label },
            current = { prefs().cloudTarget(task).provider },
            onPick = { prefs().edit().putString(task.providerPref, it.prefValue).apply() },
        )

    private fun modelRow(task: CloudTask, title: String) =
        settingsRow(title, "") { pickModel(task, title) }.also { row ->
            val sub = row.subtitle()
            onRefresh { sub.visibility = View.VISIBLE; sub.text = prefs().cloudTarget(task).model }
        }

    // --- Model rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            contentDescription = "Download ${model.name}"
            setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
            setOnClickListener { onModelAction(model) }
        }
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "✕"
            textSize = 16f
            contentDescription = "Delete ${model.name}"
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setOnClickListener { confirmDelete(model) }
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(deleteBtn)
            addView(radio)
        }

        val row = settingsRow(model.name, modelSummary(model), rightContainer) {
            onModelAction(model)
        }

        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn, row
        )
        onRefresh { refreshCard(model) }

        return row
    }

    private fun modelSummary(model: Model) = "${model.quality} · ${model.sizeMb} MB\n${model.languages}"

    private fun onModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        if (model.legacy) return
        // The download shows its progress in a notification (Android 13+ asks first).
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (!DownloadService.start(this, model)) {
            toast("${model.name} is already downloading")
            return
        }
        refreshCard(model)
    }

    /** Download progress, also for downloads started before this screen was (re)created. */
    private fun onDownloadState(model: Model, state: DownloadState) {
        when (state) {
            is DownloadState.Downloading, is DownloadState.Extracting -> refreshCard(model)
            is DownloadState.Done -> selectModel(model.archive)
            is DownloadState.Error -> {
                refreshCard(model)
                modelRows[model.archive]?.subtitle?.text = "Error: ${state.message}"
            }
        }
    }

    private fun confirmDelete(model: Model) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("Frees about ${model.sizeMb} MB. You can download it again later.")
            .setPositiveButton("Delete") { _, _ ->
                if (!ModelDownloader.delete(this, model)) {
                    toast("Could not delete ${model.name}")
                }
                if (prefs().getString("model_name", "") == model.archive) {
                    prefs().edit().remove("model_name").apply()
                }
                // refresh() picks another installed model; reload either way to free the deleted one.
                refresh()
                WhisperAccessibilityService.instance?.reloadModel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        val download = ModelDownloader.state(model)

        views.row.visibility = if (model.legacy && !installed) View.GONE else View.VISIBLE
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.deleteBtn.visibility = if (installed && download == null) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.dlBtn.isEnabled = download == null

        when (download) {
            is DownloadState.Downloading -> {
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = false
                views.progress.progress = (download.progress * 100).toInt()
                views.subtitle.text = "Downloading: ${(download.progress * 100).toInt()}%"
            }
            is DownloadState.Extracting -> {
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = true
                views.subtitle.text = "Installing..."
            }
            else -> {
                views.progress.visibility = View.GONE
                views.subtitle.text = modelSummary(model)
            }
        }
    }

    // --- Prompt rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.subtitle())
        onRefresh { refreshPromptRow(preset) }
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT && current != PostProcessor.SIMPLE_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    // --- State updates ---

    override fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = accessibilityState()
        val useLocal = prefs().getBoolean("use_local", true)
        val cleanupMode = prefs().cleanupMode()
        // Only "always" makes cleanup part of every dictation; in "hold" mode a missing key just
        // means held dictations stay raw.
        val usePostProcessing = cleanupMode == Prefs.CLEANUP_ALWAYS
        val transTarget = prefs().transcriptionTarget()
        val ppTarget = prefs().postProcessingTarget()
        val installedModels = LocalTranscriber.availableModels(this)

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = when (acc) {
            AccessibilityState.RUNNING -> "Enabled"
            // The system killed the service (battery saver, app update) or blocked it (restricted settings).
            AccessibilityState.ENABLED_NOT_RUNNING -> "On, but not running. Turn it off and on again; on Xiaomi also allow Autostart and no battery restrictions"
            AccessibilityState.OFF -> "Tap to enable in settings"
        }

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        cloudContainer.visibility = if (useLocal) View.GONE else View.VISIBLE
        promptContainer.visibility = if (cleanupMode != Prefs.CLEANUP_NEVER) View.VISIBLE else View.GONE

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur !in installedModels) {
            (MODEL_CATALOG.map { it.archive }.firstOrNull { it in installedModels } ?: installedModels.firstOrNull())
                ?.let { prefs().edit().putString("model_name", it).apply(); WhisperAccessibilityService.instance?.reloadModel() }
        }

        val missing = missingSetup(audio, acc, useLocal, installedModels.isNotEmpty(), transTarget, usePostProcessing, ppTarget)
        val ready = missing.isEmpty()
        statusSubtitle.text = if (ready) "Ready — tap the button to dictate" else "Setup required: ${missing.joinToString()}"
        statusSubtitle.setTextColor(if (ready) attrColor(androidx.appcompat.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))

        super.refresh()
    }

    private fun accessibilityState(): AccessibilityState {
        if (WhisperAccessibilityService.instance != null) return AccessibilityState.RUNNING
        val self = ComponentName(this, WhisperAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == self }
        return if (enabled) AccessibilityState.ENABLED_NOT_RUNNING else AccessibilityState.OFF
    }

    /**
     * Free-text model ID with a filterable list: presets first, replaced by the provider's live
     * model list once loaded (OpenRouter's is public; OpenAI's and a custom server's use the key).
     */
    private fun pickModel(task: CloudTask, title: String) {
        val target = prefs().cloudTarget(task)
        val provider = target.provider
        val presets = task.presets(provider)
        val default = task.defaultModel(provider)
        val prefKey = task.modelPref(provider)
        var allModels = presets
        val input = EditText(this).apply {
            hint = if (provider == CloudProvider.OPENROUTER) "provider/model" else "model"
            isSingleLine = true
            setText(target.model)
        }
        val status = TextView(this).apply {
            text = "Loading models from ${provider.label}..."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), 0, dp(8))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presets.toMutableList())
        val list = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(300))
        }

        // Only filter once the user edits the field, so the full list shows when the dialog opens.
        var edited = false
        fun applyFilter() {
            val query = if (edited) input.text.toString().trim() else ""
            val matches = allModels.filter { it.contains(query, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(matches.ifEmpty { allModels })
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            input.setText(adapter.getItem(pos))
            input.setSelection(input.text.length)
        }
        input.doAfterTextChanged { edited = true; applyFilter() }

        val onModels: (List<String>?, String?) -> Unit = { models, error ->
            runOnUiThread {
                if (!models.isNullOrEmpty()) {
                    allModels = models
                    status.visibility = View.GONE
                    applyFilter()
                } else {
                    status.text = "Could not load model list (${error ?: "empty"}). Enter a model ID."
                }
            }
        }
        when {
            provider == CloudProvider.OPENROUTER -> OpenRouter.listModels(task.outputModality, onModels)
            target.baseUrl.isBlank() -> status.text = "Set the custom API address under API keys."
            provider == CloudProvider.OPENAI && target.apiKey.isBlank() ->
                status.text = "Set an OpenAI API key to load all models."
            else -> OpenAiModels.listModels(target, task, onModels)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("$title (${provider.label})")
            .setView(vertical(dp(24), dp(8)).apply {
                addView(input)
                addView(status)
                addView(list)
            })
            .setPositiveButton("Save") { _, _ ->
                val model = input.text.toString().trim().ifBlank { default }
                prefs().edit().putString(prefKey, model).apply()
                refresh()
            }
            .setNeutralButton("Default") { _, _ ->
                prefs().edit().remove(prefKey).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "For coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    companion object {
        private const val REPO_URL = "https://github.com/evilknivel/phone-whisper"
    }
}
