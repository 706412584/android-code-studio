package com.tom.rv2ide.templates

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment as AndroidEnvironment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.provider.DocumentsContractCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.androidcodestudio.project.manager.builder.LanguageType
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.FolderPickerActivity
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.databinding.DialogAtcWizardBinding
import com.tom.rv2ide.templates.android.Template
import com.tom.rv2ide.templates.android.TemplateOptions
import com.tom.rv2ide.templates.android.TemplateRegistry
import com.tom.rv2ide.templates.android.etc.NativeCpp.Check
import com.tom.rv2ide.templates.preferences.Options
import com.tom.rv2ide.templates.preferences.WizardPreferences
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AtcWizardDialog : BottomSheetDialogFragment() {

  private var listener: AtcInterface.TemplateCreationListener? = null
  private var selectedTemplate: Template? = null
  private var _binding: DialogAtcWizardBinding? = null
  private val binding
    get() = _binding!!

  fun init(listener: AtcInterface.TemplateCreationListener?) {
    this.listener = listener
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = BottomSheetDialog(requireContext(), theme)
    val ctx = requireContext()

    _binding =
        DataBindingUtil.inflate(LayoutInflater.from(ctx), R.layout.dialog_atc_wizard, null, false)

    setupSwitches()
    setupInputs(ctx)
    setupTemplatesGrid(ctx)
    setupButtons(ctx)

    dialog.setContentView(binding.root)
    return dialog
  }

  private fun setupSwitches() {
    with(binding) {
      useCMakeSwitch.visibility = View.GONE
      useCMakeSwitch.isChecked = Options.OPT_BUILD_SYSTEM_USE_CMAKE
      useKtsSwitch.isChecked = Options.OPT_USE_GRADLE_KTS

      useCMakeSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_BUILD_SYSTEM_USE_CMAKE = isChecked
        if (isChecked) validateAndSelectCMake()
      }

      useKtsSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_USE_GRADLE_KTS = isChecked
      }

      ndkVersionButton.visibility = View.GONE
      ndkVersionButton.setOnClickListener { showNdkVersionPicker(requireContext()) }
    }
  }

  private fun setupInputs(ctx: Context) {
    val lastSaveLocation = WizardPreferences.getLastSaveLocation(ctx)
    binding.saveLocationInput.setText(lastSaveLocation ?: Environment.PROJECTS_DIR.absolutePath)

    binding.projectNameInput.addTextChangedListener(
        SimpleTextWatcher {
          updatePackageNameFromProject(it)
          validateProjectName()
        }
    )

    binding.saveLocationInput.addTextChangedListener(SimpleTextWatcher { validateProjectName() })

    binding.saveLocationLayout.setEndIconOnClickListener {
      (activity as? FragmentActivity)?.let { act ->
        FolderPickerActivity.onFolderPicked = { uriStr ->
          val path = SafResolver.resolveToPath(ctx, uriStr)
          binding.saveLocationInput.setText(path)
          WizardPreferences.setLastSaveLocation(ctx, path)
        }
        act.startActivity(Intent(act, FolderPickerActivity::class.java))
      }
    }

    setupDropdowns(ctx)
  }

  private fun setupDropdowns(ctx: Context) {
    val languageItems = arrayOf(ctx.getString(R.string.kotlin), ctx.getString(R.string.java))
    binding.languageInput.apply {
      setSimpleItems(languageItems)
      setText(languageItems[0], false)
      setOnClickListener { showDropDown() }
    }

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val defIdx = sdkValues.indexOfFirst { it.api == 21 }.coerceAtLeast(0)
    Options.OPT_MIN_SDK = sdkValues.getOrNull(defIdx)?.api ?: 21
    binding.minSdkInput.apply {
      setSimpleItems(minSdkDisplay)
      setText(minSdkDisplay[defIdx], false)
      setOnClickListener { showDropDown() }
      setOnItemClickListener { _, _, position, _ ->
        Options.OPT_MIN_SDK = sdkValues.getOrNull(position)?.api ?: 21
      }
    }

    val nativeLangValues = arrayOf("C++", "C")
    binding.nativeLanguageInput.apply {
      setSimpleItems(nativeLangValues)
      setText(nativeLangValues[0], false)
      setOnClickListener { showDropDown() }
      setOnItemClickListener { _, _, position, _ ->
        Options.OPT_NATIVE_LANGUAGE = if (position == 1) "c" else "cpp"
      }
    }
  }

  private fun setupTemplatesGrid(ctx: Context) {
    val templates = TemplateRegistry.getAllTemplates()
    binding.templatesGrid.layoutManager = GridLayoutManager(ctx, 2)
    binding.templatesGrid.adapter =
        TemplateAdapter(ctx, templates) { template ->
          selectedTemplate = template
          template.configureOptions()

          if (template.javaClass.simpleName == "NativeCpp") {
            validateNativeTemplate(ctx)
          } else {
            proceedToOptionsPage(ctx)
          }
        }
  }

  private fun setupButtons(ctx: Context) {
    binding.backButton.setOnClickListener {
      binding.root.post {
        SheetTransitions.slide(
            binding.wizardContainer,
            binding.pageOptions,
            binding.pageTemplates,
            MaterialSharedAxis.X,
            false,
        )
        binding.backButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
      }
    }

    binding.createButton.setOnClickListener { createProject(ctx) }
  }

  /**
   * 校验 CMake 可用性并让用户选版本。
   *
   * <p>在后台线程做校验：`validateCMakeVersion` 会**真的执行** `cmake --version`
   * 并最多等 10 秒，而本方法由 `setOnCheckedChangeListener` 在 UI 线程触发。
   * 设备上每个版本都要 fork 一次进程，多个版本串行下来主线程会明显卡住。
   */
  private fun validateAndSelectCMake() {
    val cmakeVersions = Check.getAllCMakeVersions()
    if (cmakeVersions.isEmpty()) {
      showAlert(
          "CMake Not Found",
          "No CMake installation found. Please install CMake from IDE Settings.",
      ) {
        startActivity(Intent(requireContext(), IDEConfigurations::class.java))
      }
      binding.useCMakeSwitch.isChecked = false
      return
    }

    val ctx = requireContext()
    CoroutineScope(Dispatchers.IO).launch {
      // 每个版本只校验一次，结果缓存下来。
      //
      // 不缓存的话，下面的版本列表（N 次）与 defaultIndex 的 indexOfFirst（再来 N 次）
      // 会把同一批二进制重复 fork 一遍；用户点某项时还会再校验一次。
      // 每项 10 秒超时，最坏情况是 UI 冻结几十秒。
      val runnable =
          cmakeVersions.associateWith { Check.validateCMakeVersion(it) != null }
      withContext(Dispatchers.Main) {
        showCMakeVersionPicker(ctx, cmakeVersions, runnable)
      }
    }
  }

  private fun validateNativeTemplate(ctx: Context) {
    val progressDialog =
        showProgress("Checking NDK...", "Please wait while we validate your NDK installation.")

    CoroutineScope(Dispatchers.IO).launch {
      val hasNdk = Check.isAtLeastOneInstalled()
      val highestNdk = if (hasNdk) Check.getHighestNdkVersion() else null
      val isValid = highestNdk?.let { Check.validateNdkVersion(it) } ?: false

      // CMake 也一并校验。
      //
      // **必须在这里拦住**：模板生成的 build.gradle 写的是
      // `version = Options.OPT_CMAKE_VERSION ?: getHighestRunnableCMakeVersion()`，
      // 两者都为 null 时 writer 干脆不写 `version` 这一行，AGP 于是自动挑
      // 「已注册的最高版本」——而那个版本很可能是跑不起来的 x86-64（见
      // NativeChecks.getHighestRunnableCMakeVersion 的说明）。结果是用户拿到一个
      // 必然构建失败的项目，且错误信息（not executable: 64-bit ELF file）
      // 看起来像 NDK/SDK 版本冲突，排查方向被完全带偏。
      //
      // 只有在确实要用 CMake 时才校验；ndk-build 路径不涉及 CMake。
      val cmakeOk =
          !Options.OPT_BUILD_SYSTEM_USE_CMAKE ||
              Options.OPT_CMAKE_VERSION != null ||
              Check.getHighestRunnableCMakeVersion() != null

      withContext(Dispatchers.Main) {
        progressDialog.dismiss()

        when {
          !hasNdk -> showNdkError(ctx, getString(R.string.error_ndk_not_found_or_incompatible))
          !isValid ->
              showNdkError(
                  ctx,
                  "The highest NDK version found ($highestNdk) is invalid or corrupted.",
              )
          !cmakeOk -> showCmakeError(ctx)
          else -> {
            Options.OPT_SELECTED_NDK_VERSION = highestNdk
            proceedToOptionsPage(ctx)
          }
        }
      }
    }
  }

  /**
   * 没有可运行的 CMake 时的提示。
   *
   * <p>把「装了但架构不对」的版本号列出来。实测 Android SDK 官方仓库的 CMake
   * **只有 x86-64**，arm64 设备上装了也跑不了；只说「未找到 CMake」会让用户
   * 以为自己没装，于是反复重装同一个跑不起来的版本。
   */
  private fun showCmakeError(ctx: Context) {
    val broken = Check.getBrokenCMakeVersions()
    val detail =
        if (broken.isEmpty()) {
          "No CMake installation found."
        } else {
          "已安装的 CMake（${broken.joinToString(", ")}）无法在本设备运行——" +
              "架构不匹配。Android SDK 官方仓库的 CMake 只有 x86-64 构建，" +
              "arm64 设备需要安装本应用提供的 arm64 版本。"
        }
    showAlert(getString(R.string.native_error_title), detail) {
      startActivity(Intent(ctx, IDEConfigurations::class.java))
    }
  }

  private fun showNdkError(ctx: Context, message: String) {
    showAlert(getString(R.string.native_error_title), message) {
      startActivity(Intent(ctx, IDEConfigurations::class.java))
    }
  }

  private fun proceedToOptionsPage(ctx: Context) {
    binding.root.post {
      // 项目名与包名后缀都要**先净化再拼**。
      //
      // 模板 displayName 里可能含标识符不允许的字符：「Native C++」直接去掉空格得到
      // `MyNativeC++`，而 `+` 不在项目名允许的字符集里，于是打开这一页的瞬间
      // 「Project name must start with a letter and contain only letters…」就报错，
      // 创建按钮永远点不动——模板等于不可用。
      //
      // 净化规则：只保留字母与数字（项目名）／只保留字母、数字与点（包名）。
      // 不做「把 + 替换成 p」这类映射：那会让包名与模板名看起来毫无关系，
      // 用户改起来更困惑，不如直接丢掉非法字符。
      val rawName = selectedTemplate?.displayName ?: ""
      // 只保留字母数字：空格与 + 一并丢掉（包名各段本来也不能含空格）。
      val templateName = "My" + rawName.filter { it.isLetterOrDigit() }
      val packageSuffix = "my" + rawName.lowercase().filter { it.isLetterOrDigit() }

      binding.projectNameInput.setText(templateName)
      binding.packageNameInput.setText("com.example.$packageSuffix")

      val isNative = Options.OPT_IS_NATIVE_CPP
      binding.useCMakeSwitch.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.nativeLanguageInputLayout.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.text = "NDK: ${Options.OPT_SELECTED_NDK_VERSION ?: "Auto"}"

      SheetTransitions.slide(
          binding.wizardContainer,
          binding.pageTemplates,
          binding.pageOptions,
          MaterialSharedAxis.X,
          true,
      )
      binding.backButton.visibility = View.VISIBLE
      binding.createButton.visibility = View.VISIBLE
    }
  }

  private fun createProject(ctx: Context) {
    val proj =
        binding.projectNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectedTemplate?.displayName?.replace(" ", "")
            ?: "MyProject"
    val pkg =
        binding.packageNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: "com.example.${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase() ?: "myproject"}"

    var lang =
        if (binding.languageInput.text?.toString()?.lowercase()?.startsWith("java") == true)
            LanguageType.JAVA
        else LanguageType.KOTLIN

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val selectedIdx = minSdkDisplay.indexOf(binding.minSdkInput.text?.toString()).coerceAtLeast(0)
    val sdkApi = Options.OPT_MIN_SDK ?: 21
    
    val savePath =
        binding.saveLocationInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: Environment.PROJECTS_DIR.absolutePath
    val projectDir = File(savePath, proj)

    if (projectDir.exists()) {
      showAlert(
          "Project Already Exists",
          "A project named '$proj' already exists at this location.",
      )
      return
    }

    if (
        Options.OPT_IS_NATIVE_GAME_ACTIVITY == true &&
            WizardPreferences.getLastSaveLocation(ctx) != Environment.AT_ACSHOME_PROJECTS.toString()
    ) {
      requireAcsHomeProjectsDir()
      return
    }

    WizardPreferences.setLastSaveLocation(ctx, savePath)
    WizardPreferences.addRecentProject(ctx, projectDir.absolutePath)

    selectedTemplate?.let { t ->
      if (t.javaClass.simpleName.contains("Compose", ignoreCase = true)) {
        lang = LanguageType.KOTLIN
      }

      CoroutineScope(Dispatchers.Main).launch {
        try {
          t.create(
              ctx,
              listener,
              TemplateOptions(proj, pkg, lang, sdkApi, Options.OPT_USE_GRADLE_KTS, File(savePath)),
          )
        } catch (e: Exception) {
          listener?.onTemplateCreated(false, "Error: ${e.message}")
        }
      }
    } ?: listener?.onCreationCancelled()

    dismiss()
  }

  private fun requireAcsHomeProjectsDir() {
    showAlert(
        "Invalid Save Location",
        "Game projects must be saved in the Android Code Studio home directory to work correctly.",
        "Automatically switch",
    ) {
      binding.saveLocationInput.setText(Environment.AT_ACSHOME_PROJECTS.toString())
      WizardPreferences.setLastSaveLocation(
          requireContext(),
          Environment.AT_ACSHOME_PROJECTS.toString(),
      )
    }
  }

  private fun validateProjectName() {
    val projectName = binding.projectNameInput.text?.toString()?.trim().orEmpty()
    val saveLocation = binding.saveLocationInput.text?.toString()?.trim().orEmpty()

    if (projectName.isEmpty()) {
      binding.projectNameLayout.error = null
      return
    }

    val projectDir = File(saveLocation, projectName)

    when {
      projectDir.exists() -> {
        binding.projectNameLayout.error = "A project with this name already exists at this location"
        binding.createButton.isEnabled = false
      }
      !projectName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$")) -> {
        binding.projectNameLayout.error =
            "Project name must start with a letter and contain only letters, numbers, and underscores"
        binding.createButton.isEnabled = false
      }
      else -> {
        binding.projectNameLayout.error = null
        binding.createButton.isEnabled = true
      }
    }
  }

  private fun updatePackageNameFromProject(projectName: CharSequence?) {
    val current = binding.packageNameInput.text?.toString()?.trim().orEmpty()
    if (current.isNotEmpty() && current.contains('.')) {
      val segs = current.split('.').toMutableList()
      segs[segs.lastIndex] =
          projectName
              ?.toString()
              ?.trim()
              ?.lowercase()
              ?.replace("[^a-zA-Z0-9_]".toRegex(), "")
              ?.ifEmpty { "app" } ?: "app"
      binding.packageNameInput.setText(segs.joinToString("."))
    }
  }

  private fun showNdkVersionPicker(ctx: Context) {
    val versions = Check.getAllNdkVersions()
    if (versions.isEmpty()) {
      showAlert("No NDK Found", "No NDK versions are installed.")
      return
    }

    val versionLabels =
        versions.map { "$it ${if (Check.validateNdkVersion(it)) "✓" else "✗"}" }.toTypedArray()
    val currentIndex = versions.indexOf(Options.OPT_SELECTED_NDK_VERSION).coerceAtLeast(0)

    MaterialAlertDialogBuilder(ctx)
        .setTitle("Select NDK Version")
        .setSingleChoiceItems(versionLabels, currentIndex) { dialog, which ->
          val selectedVersion = versions[which]
          if (Check.validateNdkVersion(selectedVersion)) {
            Options.OPT_SELECTED_NDK_VERSION = selectedVersion
            binding.ndkVersionButton.text = "NDK: $selectedVersion"
            dialog.dismiss()
          } else {
            Toast.makeText(ctx, "Invalid NDK: $selectedVersion", Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton("Cancel", null)
        .show()
  }

  /**
   * 让用户挑一个 CMake 版本。
   *
   * <p>列表里标出每个版本**能否在本设备运行**（✓/✗）。这个标记不是装饰：Android SDK
   * 仓库里的 CMake 只有 x86-64 构建，设备是 arm64 时它们全都跑不起来——实测
   * `sdkmanager` 装的 3.22.1 与 3.31.6 都是 x86-64，只有 ACS 自己
   * `android-cmake` 仓库提供的 arm64 版本可用。不标出来的话，用户会挑一个版本号
   * 更高、看起来更新的版本，然后在构建时收到 `not executable: 64-bit ELF file`。
   *
   * <p>选中的版本写进 [Options.OPT_CMAKE_VERSION]，由模板生成到 build.gradle 的
   * `cmake { version '…' }`。跑不起来的版本直接拒绝，不写进项目。
   */
  private fun showCMakeVersionPicker(
      ctx: Context,
      versions: List<String>,
      runnable: Map<String, Boolean>,
  ) {
    val versionLabels =
        versions.map { "$it ${if (runnable[it] == true) "✓" else "✗"}" }.toTypedArray()

    // 默认落在第一个能跑的版本上，而不是永远第一项——第一项可能正是坏的那个。
    val defaultIndex = versions.indexOfFirst { runnable[it] == true }.coerceAtLeast(0)

    MaterialAlertDialogBuilder(ctx)
        .setTitle("Select CMake Version")
        .setSingleChoiceItems(versionLabels, defaultIndex) { dialog, which ->
          val selectedVersion = versions[which]
          if (runnable[selectedVersion] == true) {
            Options.OPT_CMAKE_VERSION = selectedVersion
            Toast.makeText(ctx, "CMake $selectedVersion selected", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
          } else {
            // 不关闭对话框：用户可以就地改选一个能跑的，而不是退出去重来。
            Toast.makeText(
                    ctx,
                    "CMake $selectedVersion 无法在本设备运行（架构不匹配），请选带 ✓ 的版本",
                    Toast.LENGTH_LONG)
                .show()
          }
        }
        .setNegativeButton("Cancel") { _, _ -> binding.useCMakeSwitch.isChecked = false }
        .show()
  }

  private fun showAlert(
      title: String,
      message: String,
      positiveText: String = "OK",
      onPositive: (() -> Unit)? = null,
  ) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
        .setNegativeButton("Cancel", null)
        .show()
  }

  private fun showProgress(title: String, message: String) =
      MaterialAlertDialogBuilder(requireContext())
          .setTitle(title)
          .setMessage(message)
          .setCancelable(false)
          .create()
          .apply { show() }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

class TemplateAdapter(
    private val ctx: Context,
    private val templates: List<Template>,
    private val onTemplateClick: (Template) -> Unit,
) : RecyclerView.Adapter<TemplateVH>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateVH {
    val displayMetrics = ctx.resources.displayMetrics
    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    android.util.Log.d("TemplateAdapter", "Screen Width DP: $screenWidthDp")
    
    val card =
        MaterialCardView(ctx).apply {
          layoutParams =
              ViewGroup.MarginLayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                  )
                  .apply { setMargins(8.dp, 8.dp, 8.dp, 8.dp) }
          radius = 20.dp.toFloat()
          isClickable = true
          isFocusable = true
          strokeWidth = 0
          elevation = 1.dp.toFloat()
        }

    val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val title =
        TextView(ctx).apply {
          textSize = 14f
          setPadding(12.dp, 12.dp, 12.dp, 8.dp)
          gravity = android.view.Gravity.CENTER
        }
    
    val image =
        ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP
          
          if (screenWidthDp >= 600) {
            android.util.Log.d("TemplateAdapter", "Using small size for large screen")
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).apply {
              gravity = android.view.Gravity.CENTER
            }
          } else {
            android.util.Log.d("TemplateAdapter", "Using normal size")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160.dp)
          }
        }

    layout.addView(title)
    layout.addView(image)
    card.addView(layout)
    return TemplateVH(card, title, image)
  }

  override fun onBindViewHolder(holder: TemplateVH, position: Int) {
    val template = templates[position]
    holder.title.text = template.displayName

    val resId =
        ctx.resources.getIdentifier(
            template.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
            "drawable",
            ctx.packageName,
        )
    holder.image.setImageResource(if (resId != 0) resId else android.R.drawable.ic_menu_gallery)
    holder.card.setOnClickListener { onTemplateClick(template) }
  }

  override fun getItemCount() = templates.size
}

class TemplateVH(val card: MaterialCardView, val title: TextView, val image: ImageView) :
    RecyclerView.ViewHolder(card)

class SimpleTextWatcher(private val afterChanged: (CharSequence?) -> Unit) :
    android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

  override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s)
}

private val Int.dp: Int
  get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

internal object SafResolver {
  private const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
  private const val ANDROIDIDE_DOCS_AUTHORITY = "com.tom.rv2ide.documents"

  fun resolveToPath(context: Context, uriStr: String): String {
    return try {
      val uri = Uri.parse(uriStr)
      val docUri =
          DocumentsContractCompat.buildDocumentUriUsingTree(
              uri,
              DocumentsContractCompat.getTreeDocumentId(uri)!!,
          ) ?: return Environment.PROJECTS_DIR.absolutePath

      val docId =
          DocumentsContractCompat.getDocumentId(docUri)
              ?: return Environment.PROJECTS_DIR.absolutePath

      when (docUri.authority) {
        ANDROIDIDE_DOCS_AUTHORITY -> docId
        ANDROID_DOCS_AUTHORITY -> {
          val split = docId.split(':')
          if (split.size != 2) return Environment.PROJECTS_DIR.absolutePath

          if (split[0] == "primary") {
            File(AndroidEnvironment.getExternalStorageDirectory(), split[1]).absolutePath
          } else {
            "/storage/${split[0]}/${split[1]}"
          }
        }
        else -> Environment.PROJECTS_DIR.absolutePath
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Environment.PROJECTS_DIR.absolutePath
    }
  }
}
