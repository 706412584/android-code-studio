/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.databinding.ActivitySdkManagerBinding
import com.tom.rv2ide.databinding.LayoutSdkComponentCardBinding
import com.tom.rv2ide.handlers.system.installer.SdkManagerInstaller
import com.tom.rv2ide.preferences.internal.BuildPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * **SDK 管理器**：一处管理各 SDK 组件（build-tools / platforms）。
 *
 * <p>为什么是独立界面而不是设置里的单选弹窗：版本项动辄十几个（build-tools 从 33 到 37），
 * Material 单选弹窗既长又难扫，且弹窗只能"选一个"，无法表现"已装多个版本共存"。
 *
 * <p>后端是官方 `sdkmanager`（见 [SdkManagerInstaller]）：直连 Google 仓库，能装
 * build-tools 至 37、platforms 至 android-36——AndroidIDE 镜像给不了这些（其 build-tools
 * 最高 35.0.1，而 AGP 9 要求 ≥36）。
 */
class SdkManagerActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivitySdkManagerBinding? = null
  private val binding: ActivitySdkManagerBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private val installer = SdkManagerInstaller()
  private val scope = CoroutineScope(Dispatchers.Main)

  /** 每个组件一张卡：组件 id → 其卡片 binding（装完刷新用）。 */
  private val cards = LinkedHashMap<String, LayoutSdkComponentCardBinding>()

  /** `sdkmanager --list` 结果；null 表示还没拉到（或失败）。 */
  private var available: SdkManagerInstaller.AvailableSdk? = null

  /** 已展开「更多」的组件 id（默认折叠，只显示一排）。 */
  private val expandedCards = mutableSetOf<String>()

  /**
   * 组件定义：id →（标题、说明、可用列表取值、已装列表取值）。
   *
   * <p>全部组件都由同一个官方 `sdkmanager` 后端管理，因此收在一处。platform-tools 无版本号
   * （就一个包），用占位 "latest" 表示，安装时用无版本的包名。
   */
  private val components =
      listOf(
          Component(
              id = "build-tools",
              titleRes = R.string.idepref_build_tools_title,
              descRes = R.string.sdk_component_build_tools_desc,
              installed = { SdkManagerInstaller.installedBuildTools() },
              availableOf = { it.buildTools },
          ),
          Component(
              id = "platforms",
              titleRes = R.string.idepref_platform_title,
              descRes = R.string.sdk_component_platforms_desc,
              installed = { SdkManagerInstaller.installedPlatforms().map { it.removePrefix("android-") } },
              availableOf = { it.platforms },
          ),
          Component(
              id = "ndk",
              titleRes = R.string.sdk_component_ndk_title,
              descRes = R.string.sdk_component_ndk_desc,
              installed = { SdkManagerInstaller.installedNdk() },
              availableOf = { it.ndk },
          ),
          Component(
              id = "cmake",
              titleRes = R.string.sdk_component_cmake_title,
              descRes = R.string.sdk_component_cmake_desc,
              installed = { SdkManagerInstaller.installedCmake() },
              availableOf = { it.cmake },
          ),
          // platform-tools 无版本号：装了显示 "latest"，未装则可点"安装"。
          Component(
              id = "platform-tools",
              titleRes = R.string.sdk_component_platform_tools_title,
              descRes = R.string.sdk_component_platform_tools_desc,
              installed = {
                if (SdkManagerInstaller.isPlatformToolsInstalled()) listOf("latest") else emptyList()
              },
              availableOf = { it.platformTools },
          ),
      )

  override fun bindLayout(): View {
    _binding = ActivitySdkManagerBinding.inflate(layoutInflater)
    return _binding!!.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    binding.fixPermsButton.setOnClickListener { fixPermissions() }
    // 整行可点切换日志折叠；装/卸过程中也能手动收起。
    binding.outputHeader.setOnClickListener { toggleOutput() }

    // 后端不可用（未装 sdkmanager / cmdline-tools 目录权限异常）时**明确告知**，
    // 而不是让界面空着——实测踩过：目录不可读时 exists() 也是 false，用户会误以为没装。
    // 目录不可写（备份还原留下旧 uid）时给「修复权限」入口，否则用户装什么都只看到
    // 卡在 10% 加一句看不懂的 sdkmanager 警告。
    if (SdkManagerInstaller.unavailableReason() == null) {
      loadAvailable()
    }

    buildCards()
    refreshStatusAndCards()
  }

  private fun buildCards() {
    val inflater = LayoutInflater.from(this)
    for (component in components) {
      val card = LayoutSdkComponentCardBinding.inflate(inflater, binding.componentContainer, false)
      card.componentTitle.setText(component.titleRes)
      card.componentDescription.setText(component.descRes)
      bindCard(card, component)
      binding.componentContainer.addView(card.root)
      cards[component.id] = card
    }
  }

  /** 刷新一张卡：已装版本 + 可安装 chip 组。 */
  private fun bindCard(
      card: LayoutSdkComponentCardBinding,
      component: Component,
  ) {
    val installed = component.installed()
    card.componentInstalled.text =
        if (installed.isEmpty()) {
          getString(R.string.idepref_sdk_not_installed)
        } else {
          getString(R.string.idepref_sdk_installed, installed.joinToString(", "))
        }

    // 已装版本各给一个可卸载的 chip（点即弹确认）。platform-tools 是单包，
    // 卸载后组件会整块消失，风险最高，但同样是合法需求（省几十 MB），故也允许。
    card.installedChips.removeAllViews()
    for (version in installed) {
      val chip =
          Chip(this).apply {
            text = version
            isCheckable = false
            isCloseIconVisible = true
            // chip 本身仍可点（与可装 chip 一致：点 = 动作），动作是卸载而非安装。
            setOnClickListener { confirmUninstall(component, version) }
            setOnCloseIconClickListener { confirmUninstall(component, version) }
          }
      card.installedChips.addView(chip)
    }

    bindDefaultVersion(card, component, installed)

    card.availableChips.removeAllViews()
    val availableNow = available
    if (availableNow == null) {
      card.componentHint.visibility = View.VISIBLE
      card.componentHint.setText(R.string.sdk_loading_available)
      return
    }

    val installedSet = installed.toSet()
    val all = component.availableOf(availableNow).filterNot { it in installedSet }
    val latestInstalled = installed.lastOrNull()
    val toInstall =
        when {
          // **platforms 是并列目标，不按「比已装最新还新」过滤**。那条规则是为
          // build-tools 设计的（同一工具链，新版本装好后旧版本就没用了），套到平台上
          // 是错的：不同项目各自钉不同 compileSdk，装 37.2 不代表 36.1 没用了。
          //
          // 实测 bug：装了 37.2 之后 `latestInstalled` = "37.2"，于是
          // `sdkVersionGreaterThan(x, "37.2")` 把 36.1 / 37.0 / 37.1 全部滤掉，
          // 它们的 chip 直接消失，用户没有任何入口装回中间版本。
          //
          // 但也不能全列：官方仓库里 platforms 从 **android-2** 起就有，全列出会让
          // 一手屏都放不下（用户实测反馈「从 7 显示到 37.2」）。按
          // [MIN_USEFUL_PLATFORM_API] 卡掉早已无用的老版本。
          component.id == "platforms" ->
              all.filter { (it.substringBefore('.').toIntOrNull() ?: 0) >= MIN_USEFUL_PLATFORM_API }
          latestInstalled == null -> all
          else -> all.filter { sdkVersionGreaterThan(it, latestInstalled) }
        }
    if (toInstall.isEmpty()) {
      card.componentHint.visibility = View.VISIBLE
      card.componentHint.setText(R.string.sdk_all_installed)
      return
    }
    card.componentHint.visibility = View.GONE

    // 默认只显示一排（按需展开）——用户明确要求：全展开会把一屏塞满。
    val expanded = expandedCards.contains(component.id)
    val shown = if (expanded) toInstall else toInstall.take(VISIBLE_CHIPS)
    for (version in shown) {
      val chip =
          Chip(this).apply {
            // platform-tools 的"版本"是占位 latest，对用户没有信息量，改显示动作词。
            text =
                if (component.id == "platform-tools") getString(R.string.sdk_platform_tools_action)
                else version
            isCheckable = false
            // 点击**先确认再装**：安装要下几十 MB，误触代价高（实测误点一个就直接开装）。
            setOnClickListener { confirmInstall(component, version) }
          }
      card.availableChips.addView(chip)
    }
    if (toInstall.size > VISIBLE_CHIPS) {
      val more =
          Chip(this).apply {
            text =
                if (expanded) {
                  getString(R.string.sdk_show_less)
                } else {
                  getString(R.string.sdk_show_more, toInstall.size - VISIBLE_CHIPS)
                }
            isCheckable = false
            setOnClickListener {
              if (expanded) expandedCards.remove(component.id) else expandedCards.add(component.id)
              bindCard(card, component)
            }
          }
      card.availableChips.addView(more)
    }
  }

  /**
   * 版本号 → sdkmanager 包名。
   *
   * <p>两个特例：platforms 的版本是纯数字（`android-36` 的 36），要补 `android-` 前缀；
   * platform-tools 无版本后缀，就是单一包名。
   */
  private fun packageId(component: Component, version: String): String =
      when (component.id) {
        "platforms" -> "platforms;android-$version"
        "platform-tools" -> "platform-tools"
        else -> "${component.id};$version"
      }

  /**
   * 「新建项目时的默认版本」那一行（仅 build-tools 卡）。
   *
   * <p><b>为什么只给 build-tools</b>：它是**设备级**工具链——同一设备所有项目共享
   * `$ANDROID_HOME/build-tools/`，让每个项目在向导里各选一次既重复又容易选出互相
   * 冲突的版本。compileSdk 则相反，是项目级（写进各自的 build.gradle），
   * 所以那个放在新建向导里选。
   *
   * <p>不设时显示「自动」= 生成的 build.gradle 里不出现 `buildToolsVersion`，
   * 由 AGP 挑它要求的最低版本（Google 推荐做法，多数项目够用）。
   */
  private fun bindDefaultVersion(
      card: LayoutSdkComponentCardBinding,
      component: Component,
      installed: List<String>,
  ) {
    if (component.id != "build-tools" || installed.isEmpty()) {
      card.defaultVersionText.visibility = View.GONE
      return
    }
    card.defaultVersionText.visibility = View.VISIBLE
    val current = BuildPreferences.defaultBuildToolsVersion
    card.defaultVersionText.text =
        if (current.isBlank()) {
          getString(R.string.sdk_default_version_auto)
        } else {
          getString(R.string.sdk_default_version, current)
        }
    card.defaultVersionText.setOnClickListener { chooseDefaultVersion(installed) }
  }

  /** 选「新建项目时的默认 build-tools 版本」（含「不指定」）。 */
  private fun chooseDefaultVersion(installed: List<String>) {
    val autoLabel = getString(R.string.sdk_default_version_auto)
    val items = (listOf(autoLabel) + installed).toTypedArray()
    val currentIdx = installed.indexOf(BuildPreferences.defaultBuildToolsVersion)
    // 当前是「自动」（或存的版本已卸载）时选中第一项。
    val checked = if (currentIdx >= 0) currentIdx + 1 else 0
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.sdk_default_version_title)
        .setSingleChoiceItems(items, checked) { dialog, which ->
          BuildPreferences.defaultBuildToolsVersion =
              if (which <= 0) "" else installed[which - 1]
          dialog.dismiss()
          // 重画所有卡：默认版本行要跟着更新。
          for (c in components) {
            cards[c.id]?.let { bindCard(it, c) }
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 二次确认后才真正安装。 */
  private fun confirmInstall(component: Component, version: String) {
    val fullId = packageId(component, version)
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.sdk_install_confirm_title, fullId))
        .setMessage(R.string.sdk_install_confirm_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> startInstall(component, version) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * 二次确认后才卸载。
   *
   * <p>卸载是**破坏性且不可逆**的（SDK 目录在 app 私有空间，用户删不掉也恢复不了），
   * 因此这里比安装更强调后果：确认文案会点名包名，并说明需要重新下载才能恢复。
   */
  private fun confirmUninstall(component: Component, version: String) {
    val fullId = packageId(component, version)
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.sdk_uninstall_confirm_title, fullId))
        .setMessage(R.string.sdk_uninstall_confirm_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> startUninstall(component, version) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 版本号比较（按月分段数字比，避免 "9" > "10" 这类字符串排序错误）。 */
  private fun sdkVersionGreaterThan(a: String, b: String): Boolean {
    val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
      val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
      if (c != 0) return c > 0
    }
    return false
  }

  private fun loadAvailable() {
    scope.launch {
      val result =
          withContextIo {
            runCatching { installer.listAvailable() }.getOrNull()
          }
      available = result
      // 重画所有卡（拿到可用列表后 chip 才出来）。
      for (component in components) {
        cards[component.id]?.let { bindCard(it, component) }
      }
      if (result == null) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.setText(R.string.sdk_list_failed)
      }
    }
  }

  /**
   * 打开输出面板并开始一次操作（安装/卸载/修复权限共用）。
   *
   * <p>**每次操作开始时自动展开**（`setOutputExpanded(true)`）：用户此刻要看进度；
   * 操作结束后由调用方折叠——完成后的日志只剩一行结果，留着 120dp 的空白没意义。
   * 期间用户可随时手动折叠/展开（见 `toggleOutput`）。
   */
  private fun beginOutput(titleRes: Int, vararg titleArgs: Any) {
    binding.outputCard.visibility = View.VISIBLE
    binding.outputTitle.text = getString(titleRes, *titleArgs)
    binding.outputText.text = ""
    setOutputExpanded(true)
    binding.scrollView.smoothScrollTo(0, binding.outputCard.top)
  }

  /** 折叠/展开输出正文。 */
  private fun setOutputExpanded(expanded: Boolean) {
    binding.outputScroll.visibility = if (expanded) View.VISIBLE else View.GONE
    binding.outputToggleIcon.setImageResource(
        if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
  }

  private fun toggleOutput() {
    setOutputExpanded(binding.outputScroll.visibility != View.VISIBLE)
  }

  /** 装一个组件，输出实时显示在底部面板。 */
  private fun startInstall(component: Component, version: String) {
    val fullId = packageId(component, version)
    beginOutput(R.string.sdk_installing, fullId)

    scope.launch {
      val ok =
          withContextIo {
            installer.install(fullId) { line ->
              scope.launch {
                binding.outputText.append("$line\n")
                binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
              }
            }
          }
      binding.outputTitle.text =
          getString(if (ok) R.string.sdk_install_ok else R.string.sdk_install_failed, fullId)
      // 成功才折叠：失败时日志里有 sdkmanager 的原因，折叠掉等于让用户自己再点开找。
      setOutputExpanded(!ok)
      // 装完重新读磁盘刷新卡片。
      for (c in components) {
        cards[c.id]?.let { bindCard(it, c) }
      }
    }
  }

  /** 卸载一个组件，输出实时显示在底部面板。 */
  private fun startUninstall(component: Component, version: String) {
    val fullId = packageId(component, version)
    beginOutput(R.string.sdk_uninstalling, fullId)

    scope.launch {
      val ok =
          withContextIo {
            installer.uninstall(fullId) { line ->
              scope.launch {
                binding.outputText.append("$line\n")
                binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
              }
            }
          }
      binding.outputTitle.text =
          getString(if (ok) R.string.sdk_uninstall_ok else R.string.sdk_uninstall_failed, fullId)
      setOutputExpanded(!ok)
      for (c in components) {
        cards[c.id]?.let { bindCard(it, c) }
      }
    }
  }

  /**
   * 一键修复 SDK 目录权限（需 root）。
   *
   * <p>仅在检测到目录不可写时提供——成因是设备数据从备份还原后 SDK 目录仍属旧 uid
   * （见 `SdkManagerInstaller.writeBlockedReason`）。正常安装的设备用不到这条。
   */
  private fun fixPermissions() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.sdk_fix_perms_confirm_title)
        .setMessage(R.string.sdk_fix_perms_confirm_message)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          beginOutput(R.string.sdk_fix_perms_running)
          scope.launch {
            val ok =
                withContextIo {
                  installer.fixPermissions { line ->
                    scope.launch {
                      binding.outputText.append("$line\n")
                      binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                  }
                }
            binding.outputTitle.setText(
                if (ok) R.string.sdk_fix_perms_ok else R.string.sdk_fix_perms_failed)
            // 出错时保留展开——失败原因就在日志里，折叠掉用户得自己再点开。
            setOutputExpanded(!ok)
            refreshStatusAndCards()
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 重读「后端是否可用」并整体刷新：修复权限后、或要恢复错误提示时调用。 */
  private fun refreshStatusAndCards() {
    val reason = SdkManagerInstaller.unavailableReason()
    val blocked = SdkManagerInstaller.writeBlockedReason()
    // 状态区同时承担两件事：后端不可用（致命）与目录不可写（可修复）。
    val message = reason ?: blocked
    if (message != null) {
      binding.statusText.visibility = View.VISIBLE
      binding.statusText.text = message
    } else {
      binding.statusText.visibility = View.GONE
    }
    // 只有「目录不可写」才值得提供修复入口；后端不可用给 root 也没用。
    binding.fixPermsButton.visibility =
        if (reason == null && blocked != null) View.VISIBLE else View.GONE
    for (c in components) {
      cards[c.id]?.let { bindCard(it, c) }
    }
  }

  private suspend fun <T> withContextIo(block: () -> T): T =
      kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }

  private companion object {
    /** 折叠态显示的 chip 数（一排左右）。 */
    const val VISIBLE_CHIPS = 4

    /**
     * platforms 卡里最低展示到哪个 API。
     *
     * <p><b>为什么要有下限</b>：官方仓库的 platforms 从 `android-2` 起就有几十个，
     * 全部列出会把整屏塞满（用户实测反馈「从 7 显示到 37.2」），而那些版本对
     * 现在的项目毫无意义——向导里的 minSdk 默认就是 21。
     *
     * <p>取 21（Android 5.0）而不是 26（本应用自己的 minSdk）：这是**给用户项目**
     * 选 compileSdk 的界面，用户项目可以比 IDE 自身支持更老的设备，21 是常见底线。
     */
    const val MIN_USEFUL_PLATFORM_API = 21
  }

  /** 一个 SDK 组件的描述。 */
  private data class Component(
      val id: String,
      val titleRes: Int,
      val descRes: Int,
      /** 已安装版本（读磁盘）。 */
      val installed: () -> List<String>,
      /** 从 `sdkmanager --list` 结果里取"可安装"列表。 */
      val availableOf: (SdkManagerInstaller.AvailableSdk) -> List<String>,
  )
}
