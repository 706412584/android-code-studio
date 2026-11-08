/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.app.configuration.IJdkDistributionProvider
import com.tom.rv2ide.databinding.ActivityJdkManagerBinding
import com.tom.rv2ide.databinding.JdkManagerCardBinding
import com.tom.rv2ide.models.JdkDistribution
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.utils.JdkInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **JDK 管理器**：一处管理 JDK 的安装、卸载与切换。
 *
 * <p>为什么独立成页而不是设置里那个单选弹窗：单选弹窗只能「选一个」，而这里还要能
 * 装 / 卸（走 Termux 的 apt）。此前设置里只有 [com.tom.rv2ide.preferences.GradleJDKVersionPreference]，
 * 且「只有一个 JDK 时禁用」——用户看到一个灰条目却不知道原因。
 *
 * <p><b>与 SDK 管理器的根本差异</b>（改动前务必理解，别照搬）：
 * <ul>
 *   <li>SDK 组件有通用安装器 `sdkmanager`，能下到任意目录；**JDK 没有**，是 Termux 的 apt 包，
 *       固定在 `$PREFIX/lib/jvm/`——也正是 `JdkUtils` 唯一扫描的位置。
 *   <li>SDK 卸载不会伤到 IDE；**JDK 卸载会**——IDE 自身就跑在 `Environment.JAVA_HOME`
 *       指向的那个 JDK 上。因此这里**禁止卸载当前在用的 JDK**（见 [uninstall]）。
 * </ul>
 */
class JdkManagerActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityJdkManagerBinding? = null
  private val binding: ActivityJdkManagerBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private val installer = JdkInstaller()
  private val scope = CoroutineScope(Dispatchers.Main)

  /** 版本 → 卡片 binding（刷新用）。 */
  private val cards = LinkedHashMap<String, JdkManagerCardBinding>()

  /** 磁盘上真实存在的 JDK；键是 javaHome 绝对路径，来源是 `JdkUtils` 扫描结果。 */
  private var installed: Map<String, JdkDistribution> = emptyMap()

  override fun bindLayout(): View {
    _binding = ActivityJdkManagerBinding.inflate(layoutInflater)
    return _binding!!.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    binding.outputHeader.setOnClickListener {
      setOutputExpanded(binding.outputScroll.visibility != View.VISIBLE)
    }
    buildCards()
    refresh()
  }

  /** 候选版本 = apt 源里有的（固定几个），不随已装状态变化。 */
  private fun buildCards() {
    val inflater = LayoutInflater.from(this)
    for ((version, _) in JdkInstaller.AVAILABLE) {
      val card = JdkManagerCardBinding.inflate(inflater, binding.jdkContainer, false)
      card.jdkUseButton.setOnClickListener { use(version) }
      card.jdkInstallButton.setOnClickListener { confirmInstall(version) }
      card.jdkUninstallButton.setOnClickListener { confirmUninstall(version) }
      binding.jdkContainer.addView(card.root)
      cards[version] = card
    }
  }

  /** 重新读磁盘上的 JDK（来源与设置页一致，避免两处判断不同），刷新所有卡。 */
  private fun refresh() {
    scope.launch {
      val dists =
          withContext(Dispatchers.IO) {
            runCatching {
                  val provider = IJdkDistributionProvider.getInstance()
                  provider.loadDistributions()
                  provider.installedDistributions
                }
                .getOrDefault(emptyList())
          }
      // 键用**目录名**（`java-21-openjdk`），不用完整路径，也不用 javaVersion：
      // - javaVersion 是 `17.0.16` 这类完整版本号（见 JdkUtils.readDistFromProps），对不上 "17"/"21"。
      // - 完整路径比对会被软链前缀坑到：`java.home` 返回 `/data/data/...`，而
      //   `Environment.PREFIX` 可能是 `/data/user/0/...`——两者指向同一目录却不是同一字符串。
      //   实测就因为这个，扫到了 JDK 却仍显示「未安装」。
      installed = dists.associateBy { File(it.javaHome).name }

      val currentHome = BuildPreferences.javaHome
      for ((version, _) in JdkInstaller.AVAILABLE) {
        val card = cards[version] ?: continue
        val dir = installer.jvmDir(version)
        val dist = installed[dir?.name]
        // 「使用中」按**目录名**判：同 reason，路径字符串不可比。
        // 偏好里存的路径与 java.home 出自同一处（BuildPreferences.javaHome = dist.javaHome），
        // 所以同样只取最后一段比较。
        val inUse = dist != null && File(currentHome).name == dir?.name

        card.jdkTitle.text = getString(R.string.jdk_title_format, version)
        card.jdkPath.text = dist?.javaHome ?: dir?.absolutePath.orEmpty()
        card.jdkPath.visibility = if (dist != null) View.VISIBLE else View.GONE

        card.jdkStatus.text =
            when {
              dist == null -> getString(R.string.jdk_not_installed)
              inUse ->
                  getString(R.string.jdk_installed_label) +
                      " · " +
                      getString(R.string.jdk_current_label)
              else -> getString(R.string.jdk_installed_label)
            }

        card.jdkUseButton.visibility = if (dist != null && !inUse) View.VISIBLE else View.GONE
        card.jdkInstallButton.visibility = if (dist == null) View.VISIBLE else View.GONE
        // 禁止卸载使用中的 JDK：IDE 自身跑在它上面（见类注释）。
        card.jdkUninstallButton.visibility =
            if (dist != null && !inUse) View.VISIBLE else View.GONE
      }
    }
  }

  /** 切换当前使用的 JDK。 */
  private fun use(version: String) {
    val dir = installer.jvmDir(version) ?: return
    BuildPreferences.javaHome = dir.absolutePath
    Toast.makeText(this, getString(R.string.jdk_use_ok, version), Toast.LENGTH_LONG).show()
    refresh()
  }

  private fun confirmInstall(version: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.jdk_install_confirm_title, version))
        .setMessage(R.string.jdk_install_confirm_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> install(version) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun confirmUninstall(version: String) {
    // 双保险：按钮已按状态隐藏，但状态可能刚变（如另开一处改了 javaHome），
    // 卸载正在使用的 JDK 会直接把 IDE 弄崩，这里再挡一次。
    val dirName = installer.jvmDir(version)?.name
    if (dirName != null && File(BuildPreferences.javaHome).name == dirName) {
      Toast.makeText(this, R.string.jdk_cannot_uninstall_current, Toast.LENGTH_LONG).show()
      return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.jdk_uninstall_confirm_title, version))
        .setMessage(R.string.jdk_uninstall_confirm_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> uninstall(version) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun install(version: String) {
    beginOutput(R.string.jdk_installing, version)
    scope.launch {
      val ok = withContext(Dispatchers.IO) { runCatching { installer.install(version) { append(it) } }.getOrDefault(false) }
      endOutput(if (ok) R.string.jdk_install_ok else R.string.jdk_install_failed, version, ok)
      refresh()
    }
  }

  private fun uninstall(version: String) {
    beginOutput(R.string.jdk_uninstalling, version)
    scope.launch {
      val ok = withContext(Dispatchers.IO) { runCatching { installer.uninstall(version) { append(it) } }.getOrDefault(false) }
      endOutput(if (ok) R.string.jdk_uninstall_ok else R.string.jdk_uninstall_failed, version, ok)
      refresh()
    }
  }

  // ---- 输出面板（与 SDK 管理器同款：操作时展开、成功即收起、失败保留、可手动切换）----

  private fun beginOutput(titleRes: Int, vararg args: Any) {
    binding.outputCard.visibility = View.VISIBLE
    binding.outputTitle.text = getString(titleRes, *args)
    binding.outputText.text = ""
    setOutputExpanded(true)
    binding.scrollView.smoothScrollTo(0, binding.outputCard.top)
  }

  private fun endOutput(titleRes: Int, version: String, ok: Boolean) {
    binding.outputTitle.text = getString(titleRes, version)
    // 成功才折叠：失败原因在日志正文里，折叠掉用户得自己再点开找。
    setOutputExpanded(!ok)
  }

  private fun append(line: String) {
    scope.launch {
      binding.outputText.append("$line\n")
      binding.outputScroll.post { binding.outputScroll.fullScroll(View.FOCUS_DOWN) }
    }
  }

  private fun setOutputExpanded(expanded: Boolean) {
    binding.outputScroll.visibility = if (expanded) View.VISIBLE else View.GONE
    binding.outputToggleIcon.setImageResource(
        if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }
}
