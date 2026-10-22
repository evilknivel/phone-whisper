package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var orKeyRowSub: TextView
    private lateinit var cloudContainer: LinearLayout
    private lateinit var transProviderSub: TextView
    private lateinit var transModelRow: LinearLayout
    private lateinit var transModelSub: TextView
    private lateinit var ppProviderSub: TextView
    private lateinit var ppModelRow: LinearLayout
    private lateinit var ppModelSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var threadsRowSub: TextView
    private lateinit var promptContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val deleteBtn: MaterialButton
    )

    private val downloadListener: (Model, DownloadState) -> Unit = { model, state ->
        runOnUiThread { onDownloadState(model, state) }
    }

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))
        
        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Engine Section ---
        
        val isCloud = !prefs().getBoolean("use_local", true)
        
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "OpenAI or OpenRouter, requires API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            // Loads the local model, or frees it in cloud mode.
            WhisperAccessibilityService.instance?.reloadModel()
            refresh()
        }
        root.addView(cloudRow)

        cloudContainer = vertical(0)
        val transProviderRow = settingsRow("Transcription provider", "") {
            chooseProvider("transcription_provider", "Transcription provider")
        }
        transProviderSub = transProviderRow.findViewWithTag("subtitle")
        cloudContainer.addView(transProviderRow)
        transModelRow = settingsRow("Transcription model", "") {
            pickModel(CloudTask.TRANSCRIPTION, "Transcription model")
        }
        transModelSub = transModelRow.findViewWithTag("subtitle")
        cloudContainer.addView(transModelRow)
        root.addView(cloudContainer)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        val threadsRow = settingsRow("CPU threads", "") { chooseThreads() }
        threadsRowSub = threadsRow.findViewWithTag("subtitle")
        modelContainer.addView(threadsRow)
        root.addView(modelContainer)

        // --- Post-Processing Section ---
        root.addView(sectionHeader("Post-Processing"))
        
        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Cleanup transcript", "Uses an LLM to fix grammar and punctuation", postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        root.addView(postProcessRow)

        promptContainer = vertical(0)
        val ppProviderRow = settingsRow("Cleanup provider", "") {
            chooseProvider("post_processing_provider", "Cleanup provider")
        }
        ppProviderSub = ppProviderRow.findViewWithTag("subtitle")
        promptContainer.addView(ppProviderRow)
        ppModelRow = settingsRow("Cleanup model", "") {
            pickModel(CloudTask.POST_PROCESSING, "Cleanup model")
        }
        ppModelSub = ppModelRow.findViewWithTag("subtitle")
        promptContainer.addView(ppModelRow)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- Settings Section ---
        root.addView(sectionHeader("Settings"))
        
        val keyRow = settingsRow("OpenAI API Key", "Tap to set") { promptApiKey(CloudProvider.OPENAI) }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        val orKeyRow = settingsRow("OpenRouter API Key", "Tap to set") { promptApiKey(CloudProvider.OPENROUTER) }
        orKeyRowSub = orKeyRow.findViewWithTag("subtitle")
        root.addView(orKeyRow)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            clipToPadding = false
            addView(root)
        }
        // targetSdk 35+ draws edge-to-edge: keep content clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ModelDownloader.addListener(downloadListener)
        refresh()
    }

    override fun onPause() {
        ModelDownloader.removeListener(downloadListener)
        super.onPause()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

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

        val row = settingsRow(
            model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, deleteBtn
        )
        refreshCard(model)
        
        return row
    }

    private fun onModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        if (!ModelDownloader.download(this, model)) {
            toast("${model.name} is already downloading")
            return
        }
        refreshCard(model)
    }

    /** Download progress, also for downloads started before this screen was (re)created. */
    private fun onDownloadState(model: Model, state: DownloadState) {
        when (state) {
            is DownloadState.Downloading, is DownloadState.Extracting -> refreshCard(model)
            is DownloadState.Done -> {
                selectModel(model.archive)
                toast("${model.name} ready!")
            }
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
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        val download = ModelDownloader.state(model)

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
                views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
            }
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(androidx.appcompat.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows(); refresh()
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

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val transTarget = prefs().transcriptionTarget()
        val ppTarget = prefs().postProcessingTarget()
        val installedModels = LocalTranscriber.availableModels(this)
        val hasModel = installedModels.isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        cloudContainer.visibility = if (useLocal) View.GONE else View.VISIBLE
        transProviderSub.text = transTarget.provider.label
        transModelSub.text = transTarget.model
        ppProviderSub.text = ppTarget.provider.label
        ppModelSub.text = ppTarget.model
        val threads = prefs().getInt("num_threads", LocalTranscriber.DEFAULT_THREADS)
        threadsRowSub.text = "$threads of ${Runtime.getRuntime().availableProcessors()} cores · more is faster but uses more battery"
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        keyRowSub.text = maskedKey(CloudProvider.OPENAI)
        orKeyRowSub.text = maskedKey(CloudProvider.OPENROUTER)

        val prompt = currentPrompt()
        promptRowSub.text = prompt

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur !in installedModels) {
            (MODEL_CATALOG.map { it.archive }.firstOrNull { it in installedModels } ?: installedModels.firstOrNull())
                ?.let { selectModel(it) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && transTarget.apiKey.isNotBlank()
        val postReady = !usePostProcessing || ppTarget.apiKey.isNotBlank()
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(androidx.appcompat.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
        refreshPromptRows()
    }

    private fun maskedKey(provider: CloudProvider): String {
        val key = prefs().getString(provider.keyPref, "") ?: ""
        return if (key.isBlank()) "Tap to set"
               else if (key.length > 7) "...${key.takeLast(4)}"
               else "...***"
    }

    /**
     * The saved key is never shown: the field starts empty and masks its input, the dialog can't
     * be screenshotted or recorded, and the keyboard is asked not to learn or autofill it.
     */
    private fun promptApiKey(provider: CloudProvider) {
        val saved = prefs().getString(provider.keyPref, "").orEmpty()
        val input = EditText(this).apply {
            hint = if (saved.isBlank()) "Paste your API key" else "Saved key ${maskedKey(provider)} — leave empty to keep"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSingleLine = true
        }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("${provider.label} API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) prefs().edit().putString(provider.keyPref, key).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (saved.isNotBlank()) {
            builder.setNeutralButton("Remove") { _, _ ->
                prefs().edit().remove(provider.keyPref).apply()
                refresh()
            }
        }
        builder.create().apply {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            show()
        }
    }

    private fun chooseThreads() {
        val cores = Runtime.getRuntime().availableProcessors()
        val current = prefs().getInt("num_threads", LocalTranscriber.DEFAULT_THREADS).coerceIn(1, cores)
        val options = (1..cores).map { if (it == LocalTranscriber.DEFAULT_THREADS) "$it (default)" else "$it" }
        android.app.AlertDialog.Builder(this)
            .setTitle("CPU threads for local models")
            .setSingleChoiceItems(options.toTypedArray(), current - 1) { dialog, which ->
                prefs().edit().putInt("num_threads", which + 1).apply()
                dialog.dismiss()
                WhisperAccessibilityService.instance?.reloadModel()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseProvider(prefKey: String, title: String) {
        val providers = CloudProvider.entries
        val current = CloudProvider.from(prefs().getString(prefKey, null))
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(providers.map { it.label }.toTypedArray(), providers.indexOf(current)) { dialog, which ->
                prefs().edit().putString(prefKey, providers[which].prefValue).apply()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Free-text model ID with a filterable list: presets first, replaced by the provider's live
     * model list once loaded (OpenRouter's is public; OpenAI's needs the API key).
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
        when (provider) {
            CloudProvider.OPENROUTER -> OpenRouter.listModels(task.outputModality, onModels)
            CloudProvider.OPENAI ->
                if (target.apiKey.isBlank()) status.text = "Set an OpenAI API key to load all models."
                else OpenAiModels.listModels(target.apiKey, task, onModels)
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

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
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
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
