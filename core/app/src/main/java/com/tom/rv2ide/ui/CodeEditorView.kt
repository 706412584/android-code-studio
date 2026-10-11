package com.tom.rv2ide.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.isVisible
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.activities.editor.BaseEditorActivity
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.editor.databinding.LayoutCodeEditorBinding
import com.tom.rv2ide.editor.ui.EditorSearchLayout
import com.tom.rv2ide.editor.ui.IDEEditor
import com.tom.rv2ide.editor.ui.IDEEditor.Companion.createInputTypeFlags
import com.tom.rv2ide.editor.ui.cleanupCompletionTooltips
import com.tom.rv2ide.editor.ui.cleanupHoverTooltips
import com.tom.rv2ide.editor.ui.clearDiagnostics
import com.tom.rv2ide.editor.ui.initCompletionTooltips
import com.tom.rv2ide.editor.ui.initDiagnosticHandling
import com.tom.rv2ide.editor.ui.initHoverTooltips
import com.tom.rv2ide.editor.ui.updateEditorDiagnostics
import com.tom.rv2ide.editor.utils.ContentReadWrite.readContent
import com.tom.rv2ide.editor.utils.ContentReadWrite.writeTo
import com.tom.rv2ide.eventbus.events.editor.DocumentExternalChangeEvent
import com.tom.rv2ide.eventbus.events.preferences.PreferenceChangeEvent
import com.tom.rv2ide.lsp.IDELanguageClientImpl
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.lsp.java.JavaLanguageServer
import com.tom.rv2ide.lsp.kotlin.KotlinLanguageServer
import com.tom.rv2ide.lsp.clang.ClangLanguageServer
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.tom.rv2ide.tasks.cancelIfActive
import com.tom.rv2ide.tasks.runOnUiThread
import com.tom.rv2ide.utils.customOrJBMono
import com.tom.rv2ide.artificial.completion.SuggestionView
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import com.tom.rv2ide.managers.PreferenceManager
import android.graphics.Typeface

@SuppressLint("ViewConstructor")
class CodeEditorView(context: Context, file: File, selection: Range) :
    LinearLayoutCompat(context), Closeable {

  private var _binding: LayoutCodeEditorBinding? = null
  private var _searchLayout: EditorSearchLayout? = null
  private var _suggestionView: SuggestionView? = null
  private val prefManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager
    
  private val codeEditorScope =
      CoroutineScope(Dispatchers.Default + CoroutineName("CodeEditorView"))

  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
  private val readWriteContext = newSingleThreadContext("CodeEditorView")

  private val binding: LayoutCodeEditorBinding
    get() = checkNotNull(_binding) { "Binding has been destroyed" }

  private val searchLayout: EditorSearchLayout
    get() = checkNotNull(_searchLayout) { "Search layout has been destroyed" }

  val suggestionView: SuggestionView?
    get() = _suggestionView

  private var analysisJob: Job? = null

  /** Get the file of this editor. */
  val file: File?
    get() = editor?.file

  /** Get the [IDEEditor] instance of this editor view. */
  val editor: IDEEditor?
    get() = _binding?.editor

  /**
   * Returns whether the content of the editor has been modified.
   *
   * @see IDEEditor.isModified
   */
  val isModified: Boolean
    get() = editor?.isModified ?: false

  /**
   * 磁盘上有比编辑器更新的内容（该文件被 AI 工具 / git checkout / archive 解压等
   * **编辑器之外**的路径改写，且编辑器里还有用户未保存的改动，故未自动重载）。
   *
   * <p><b>为什么自动保存必须查这个标志</b>：自动保存的判据是「编辑器 buffer 与磁盘不同
   * 就写回」，方向默认是「编辑器赢」。当磁盘的新内容来自外部写入时，这个方向就错了——
   * 用户随手改一笔触发自动保存，会把 buffer（旧内容 + 那一笔）整段写回，**静默覆盖
   * 外部的修改**。置位后自动保存对本次让路（先给用户一个「保存将覆盖外部改动」的机会，
   * 而不是替他决定）。用户下一次编辑（[clearExternalConflict]）即视为已重新取得控制权。
   */
  @Volatile
  var hasExternalDiskChange: Boolean = false
    private set

  /** 用户在编辑器里继续编辑（或手动保存）后，外部冲突视为已由用户接管。 */
  fun clearExternalConflict() {
    hasExternalDiskChange = false
  }

  companion object {
    private val log = LoggerFactory.getLogger(CodeEditorView::class.java)
  }

  init {
    _binding = LayoutCodeEditorBinding.inflate(LayoutInflater.from(context))

    binding.editor.apply {
      isHighlightCurrentBlock = true
      props.autoCompletionOnComposing = true
      dividerWidth = SizeUtils.dp2px(2f).toFloat()
      colorScheme = SchemeAndroidIDE.newInstance(context)
      lineSeparator = LineSeparator.LF
    }

    _searchLayout = EditorSearchLayout(context, binding.editor).apply {
      setOnSearchVisibilityChangeListener { isVisible ->
        val activity = context as? BaseEditorActivity ?: return@setOnSearchVisibilityChangeListener
        activity.onEditorSearchVisibilityChanged(isVisible)
      }
    }

    // Create SuggestionView programmatically
    _suggestionView = SuggestionView(context).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        // Position it at the top-left of the editor with some margin
        val margin = SizeUtils.dp2px(8f)
        setMargins(margin, margin, margin, margin)
      }
      elevation = SizeUtils.dp2px(8f).toFloat()
      visibility = GONE // Hidden by default
    }

    orientation = VERTICAL

    removeAllViews()
    
    // Wrap the editor in a FrameLayout so we can overlay the suggestion view
    val editorContainer = FrameLayout(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
      addView(binding.root)
      _suggestionView?.let { addView(it) }
    }
    
    addView(editorContainer)
    addView(searchLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    readFileAndApplySelection(file, selection)

    // Setup content change listener after initialization
    setupContentChangeListener()
  }

  private fun setupContentChangeListener() {
    binding.editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
        event: io.github.rosemoe.sora.event.ContentChangeEvent,
        _: Any? ->
      // 用户继续编辑 = 重新取得控制权，外部冲突标记随之失效（见 hasExternalDiskChange）。
      hasExternalDiskChange = false
      val editorFile = binding.editor.file
      if (editorFile != null && (editorFile.extension == "kt")) {
        startDiagnosticAnalysis(editorFile)
      }
    }
  }

  /**
   * 该文件在编辑器之外被改写（AI 工具 / git checkout 等），刷新本编辑器。
   *
   * <p>两种处理：
   * <ul>
   *   <li>编辑器无未保存改动 → 直接从磁盘重载（保留光标位置），用户立即看到最新内容；
   *   <li>编辑器有未保存改动 → **不**静默重载（会丢用户的编辑），只标记冲突，
   *       由自动保存让路（见 [hasExternalDiskChange]）。
   * </ul>
   */
  @Subscribe(threadMode = ThreadMode.MAIN)
  @Suppress("unused")
  fun onDocumentExternalChange(event: DocumentExternalChangeEvent) {
    val current = file ?: return
    if (event.changedFile.toFile().absolutePath != current.absolutePath) {
      return
    }
    if (isModified) {
      hasExternalDiskChange = true
      log.info("External change detected while modified, marked conflict: {}", current.name)
      return
    }
    reloadFromDisk()
  }

  /** 从磁盘重新读取内容并保留光标位置；无未保存改动时才应调用。 */
  fun reloadFromDisk() {
    val f = file ?: return
    val editor = binding.editor
    // 用当前光标位置构造一个零长度选区，重载后光标回到原处（而不是跳到文件头）。
    val range = try {
      val cursor = editor.cursor
      if (cursor == null) com.tom.rv2ide.models.Range.NONE
      else
        com.tom.rv2ide.models.Range.pointRange(
            com.tom.rv2ide.models.Position(cursor.leftLine, cursor.leftColumn))
    } catch (e: Throwable) {
      com.tom.rv2ide.models.Range.NONE
    }
    hasExternalDiskChange = false
    readFileAndApplySelection(f, range)
    log.info("Reloaded editor from disk: {}", f.name)
  }

  /** Get the file of this editor. Throws [IllegalStateException] if no file is available. */
  fun requireFile(): File {
    return checkNotNull(file)
  }

  /**
   * Update the file of this editor. This only updates the file reference of the editor and does not
   * resets the content.
   */
  fun updateFile(file: File) {
    val editor = _binding?.editor ?: return
    editor.file = file
    postRead(file)
  }

  /** Called when the editor has been selected and is visible to the user. */
  fun onEditorSelected() {
    _binding?.editor?.onEditorSelected()
        ?: run { log.warn("onEditorSelected() called but no editor instance is available") }
  }

  /** Begins search mode and shows the [search layout][EditorSearchLayout]. */
  fun beginSearch() {
    if (_binding == null || _searchLayout == null) {
      log.warn("Editor layout is null content=$binding, searchLayout=$searchLayout")
      return
    }

    searchLayout.beginSearchMode()
  }

  /** Mark this files as saved. Even if it not saved. */
  fun markAsSaved() {
    editor?.markUnmodified()
  }

  /**
   * Saves the content of the editor to the editor's file.
   *
   * @return Whether the save operation was successfully completed or not. If this method returns
   *   `false`, it means that there was an error saving the file or the content of the file was not
   *   modified and hence the save operation was skipped.
   */
  suspend fun save(): Boolean {
    val file = this.file ?: return false

    if (!isModified && file.exists()) {
      log.info("File was not modified. Skipping save operation for file {}", file.name)
      return false
    }

    val text =
        _binding?.editor?.text
            ?: run {
              log.error(
                  "Failed to save file. Unable to retrieve the content of editor as it is null."
              )
              return false
            }

    withContext(Dispatchers.Main.immediate) {
      withEditingDisabled {
        withContext(readWriteContext) {
          text.writeTo(file, this@CodeEditorView::updateReadWriteProgress)
        }
      }

      _binding?.rwProgress?.isVisible = false
    }

    markUnmodified()
    notifySaved()

    return true
  }

  private fun startDiagnosticAnalysis(file: File) {
    analysisJob?.cancel()

    analysisJob =
        codeEditorScope.launch {
          delay(500)

          val editor = _binding?.editor ?: return@launch
          val languageServer = editor.languageServer

          if (
              languageServer is KotlinLanguageServer &&
                  (file.extension == "kt")
          ) {
            try {
              val result = languageServer.analyze(file.toPath())

              if (result != DiagnosticResult.NO_UPDATE) {
                withContext(Dispatchers.Main) {
                  editor.updateEditorDiagnostics(result.diagnostics)
                }
              }
            } catch (e: Exception) {
              log.error("Failed to analyze file for diagnostics", e)
            }
          }
          
          // Clang lsp
          if (
              languageServer is ClangLanguageServer &&
                  (file.extension == "cpp" || file.extension == "c")
          ) {
            try {
              val result = languageServer.analyze(file.toPath())

              if (result != DiagnosticResult.NO_UPDATE) {
                withContext(Dispatchers.Main) {
                  editor.updateEditorDiagnostics(result.diagnostics)
                }
              }
            } catch (e: Exception) {
              log.error("Failed to analyze file for diagnostics", e)
            }
          }
          
          
        }
  }

  private fun updateReadWriteProgress(progress: Int) {
    val binding = this.binding
    runOnUiThread {
      if (binding.rwProgress.isVisible && (progress < 0 || progress >= 100)) {
        binding.rwProgress.isVisible = false
        return@runOnUiThread
      }

      if (!binding.rwProgress.isVisible) {
        binding.rwProgress.isVisible = true
      }

      binding.rwProgress.progress = progress
    }
  }

  private inline fun <R : Any?> withEditingDisabled(action: () -> R): R {
    return try {
      _binding?.editor?.isEditable = false
      action()
    } finally {
      _binding?.editor?.isEditable = true
    }
  }

  private fun readFileAndApplySelection(file: File, selection: Range) {
    codeEditorScope.launch(Dispatchers.Main.immediate) {
      updateReadWriteProgress(0)

      withEditingDisabled {
        val content =
            withContext(readWriteContext) {
              selection.validate()
              file.readContent(this@CodeEditorView::updateReadWriteProgress)
            }

        initializeContent(content, file, selection)
        _binding?.rwProgress?.isVisible = false
      }
    }
  }

  private fun initializeContent(content: Content, file: File, selection: Range) {
    val ideEditor = binding.editor
    ideEditor.postInLifecycle {
      val args = Bundle().apply { putString(IEditor.KEY_FILE, file.absolutePath) }

      ideEditor.setText(content, args)

      markUnmodified()
      postRead(file)

      ideEditor.validateRange(selection)
      ideEditor.setSelection(selection)

      configureEditorIfNeeded()
    }
  }

  private fun postRead(file: File) {
    binding.editor.setupLanguage(file)
    binding.editor.setLanguageServer(createLanguageServer(file))

    if (IDELanguageClientImpl.isInitialized()) {
      binding.editor.setLanguageClient(IDELanguageClientImpl.getInstance())
    }

    binding.editor.file = file

    if (file.extension == "kt") {
      binding.editor.initDiagnosticHandling()
      startDiagnosticAnalysis(file)
    }
    binding.editor.initCompletionTooltips()
    binding.editor.initHoverTooltips()

    (context as? BaseEditorActivity?)?.refreshSymbolInput()
    (context as? Activity?)?.invalidateOptionsMenu()
  }

  private fun createLanguageServer(file: File): ILanguageServer? {
    if (!file.isFile) {
      return null
    }

    val serverID: String =
        when (file.extension) {
          "java" -> JavaLanguageServer.SERVER_ID
          "xml" -> XMLLanguageServer.SERVER_ID
          "kt" -> KotlinLanguageServer.SERVER_ID
          "cpp", "c" -> ClangLanguageServer.SERVER_ID
          else -> return null
        }
    return ILanguageServerRegistry.getDefault().getServer(serverID)
  }

  private fun configureEditorIfNeeded() {
    onCustomFontPrefChanged()
    onFontSizePrefChanged()
    onFontLigaturesPrefChanged()
    onPrintingFlagsPrefChanged()
    onInputTypePrefChanged()
    onWordwrapPrefChanged()
    onMagnifierPrefChanged()
    onUseIcuPrefChanged()
    onDeleteEmptyLinesPrefChanged()
    onDeleteTabsPrefChanged()
    onStickyScrollEnabeldPrefChanged()
    onPinLineNumbersPrefChanged()
  }

  private fun onMagnifierPrefChanged() {
    binding.editor.getComponent(Magnifier::class.java).isEnabled = EditorPreferences.useMagnifier
  }

  private fun onWordwrapPrefChanged() {
    val enabled = EditorPreferences.wordwrap
    binding.editor.isWordwrap = enabled
  }

  private fun onInputTypePrefChanged() {
      binding.editor.inputType = createInputTypeFlags()
  }

  private fun onPrintingFlagsPrefChanged() {
    var flags = 0
    if (EditorPreferences.drawLeadingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
    }
    if (EditorPreferences.drawTrailingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
    }
    if (EditorPreferences.drawInnerWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_INNER
    }
    if (EditorPreferences.drawEmptyLineWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
    }
    if (EditorPreferences.drawLineBreak) {
      flags = flags or CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    binding.editor.nonPrintablePaintingFlags = flags
  }

  private fun onFontLigaturesPrefChanged() {
    val enabled = EditorPreferences.fontLigatures
    binding.editor.isLigatureEnabled = enabled
  }

  private fun onFontSizePrefChanged() {
    var textSize = EditorPreferences.fontSize
    if (textSize < 6 || textSize > 32) {
      textSize = 14f
    }
    binding.editor.setTextSize(textSize)
  }

  private fun onUseIcuPrefChanged() {
    binding.editor.props.useICULibToSelectWords = EditorPreferences.useIcu
  }

  private fun onCustomFontPrefChanged() {
    val state = EditorPreferences.useCustomFont
    var fontPath = "${com.tom.rv2ide.utils.Environment.HOME}/.androidide/ui/${EditorPreferences.selectedCustomFont}"
    if (fontPath == "${com.tom.rv2ide.utils.Environment.HOME}/.androidide/ui/null") {
      fontPath = "${com.tom.rv2ide.utils.Environment.HOME}/.androidide/ui/jetbrains-mono.ttf"
    }
    val typeface = if (fontPath != null) Typeface.createFromFile(fontPath) else Typeface.MONOSPACE
    
    binding.editor.typefaceText = typeface
    binding.editor.typefaceLineNumber = customOrJBMono(state)
  }

  private fun onDeleteEmptyLinesPrefChanged() {
    binding.editor.props.deleteEmptyLineFast = EditorPreferences.deleteEmptyLines
  }

  private fun onDeleteTabsPrefChanged() {
    binding.editor.props.deleteMultiSpaces = if (EditorPreferences.deleteTabsOnBackspace) -1 else 1
  }

  private fun onStickyScrollEnabeldPrefChanged() {
    binding.editor.props.stickyScroll = EditorPreferences.stickyScrollEnabled
  }

  private fun onPinLineNumbersPrefChanged() {
    binding.editor.setPinLineNumber(EditorPreferences.pinLineNumbers)
  }

  internal fun markUnmodified() {
    binding.editor.markUnmodified()
  }

  internal fun markModified() {
    binding.editor.markModified()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  @Suppress("unused")
  fun onPreferenceChanged(event: PreferenceChangeEvent) {
    if (_binding == null) {
      return
    }

    BaseApplication.getBaseInstance().prefManager
    when (event.key) {
      EditorPreferences.FONT_SIZE -> onFontSizePrefChanged()
      EditorPreferences.FONT_LIGATURES -> onFontLigaturesPrefChanged()

      EditorPreferences.FLAG_LINE_BREAK,
      EditorPreferences.FLAG_WS_INNER,
      EditorPreferences.FLAG_WS_EMPTY_LINE,
      EditorPreferences.FLAG_WS_LEADING,
      EditorPreferences.FLAG_WS_TRAILING -> onPrintingFlagsPrefChanged()
      EditorPreferences.KEYBOARD_SUGGESTIONS -> onInputTypePrefChanged()
    
      EditorPreferences.FLAG_PASSWORD -> onInputTypePrefChanged()
      EditorPreferences.WORD_WRAP -> onWordwrapPrefChanged()
      EditorPreferences.USE_MAGNIFER -> onMagnifierPrefChanged()
      EditorPreferences.USE_ICU -> onUseIcuPrefChanged()
      EditorPreferences.USE_CUSTOM_FONT -> onCustomFontPrefChanged()
      EditorPreferences.DELETE_EMPTY_LINES -> onDeleteEmptyLinesPrefChanged()
      EditorPreferences.DELETE_TABS_ON_BACKSPACE -> onDeleteTabsPrefChanged()
      EditorPreferences.STICKY_SCROLL_ENABLED -> onStickyScrollEnabeldPrefChanged()
      EditorPreferences.PIN_LINE_NUMBERS -> onPinLineNumbersPrefChanged()
    }
  }

  private fun notifySaved() {
    binding.editor.dispatchDocumentSaveEvent()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    EventBus.getDefault().unregister(this)
  }

  override fun close() {
    analysisJob?.cancel()
    codeEditorScope.cancelIfActive("Cancellation was requested")
    _binding?.editor?.apply {
      clearDiagnostics()
      cleanupCompletionTooltips()
      cleanupHoverTooltips()
      notifyClose()
      release()
    }

    readWriteContext.use {}
  }
}