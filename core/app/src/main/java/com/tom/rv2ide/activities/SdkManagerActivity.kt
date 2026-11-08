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

    // 后端不可用（未装 sdkmanager / cmdline-tools 目录权限异常）时**明确告知**，
    // 而不是让界面空着——实测踩过：目录不可读时 exists() 也是 false，用户会误以为没装。
    val reason = SdkManagerInstaller.unavailableReason()
    if (reason != null) {
      binding.statusText.visibility = View.VISIBLE
      binding.statusText.text = reason
    } else {
      loadAvailable()
    }

    buildCards()
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

    card.availableChips.removeAllViews()
    val availableNow = available
    if (availableNow == null) {
      card.componentHint.visibility = View.VISIBLE
      card.componentHint.setText(R.string.sdk_loading_available)
      return
    }

    val installedSet = installed.toSet()
    // 只列「比已装最新还新」的（含未装的更高版本）——老版本没有升级价值，列出来只是噪声
    // （实测 platforms 会列出 API 7…35，一屏都放不下，而项目 minSdk 是 21）。
    val all = component.availableOf(availableNow).filterNot { it in installedSet }
    val latestInstalled = installed.lastOrNull()
    val toInstall =
        if (latestInstalled == null) {
          all
        } else {
          all.filter { sdkVersionGreaterThan(it, latestInstalled) }
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

  /** 装一个组件，输出实时显示在底部面板。 */
  private fun startInstall(component: Component, version: String) {
    val fullId = packageId(component, version)

    binding.outputCard.visibility = View.VISIBLE
    binding.outputTitle.text = getString(R.string.sdk_installing, fullId)
    binding.outputText.text = ""
    binding.scrollView.smoothScrollTo(0, binding.outputCard.top)

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
      // 装完重新读磁盘刷新卡片。
      for (c in components) {
        cards[c.id]?.let { bindCard(it, c) }
      }
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
