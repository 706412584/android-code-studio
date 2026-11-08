/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.templates

import android.content.Context
import com.blankj.utilcode.util.ActivityUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import org.slf4j.LoggerFactory

/**
 * 告知用户「模板创建失败」，带上失败原因。
 *
 * <p><b>为什么要单独抽出来</b>：模板创建有两条入口（`MainFragment.showCreateProject()`
 * 带 listener、`MainActivity` 走 `SCREEN_TEMPLATE_LIST` 不传 listener），两条都要
 * 能报错。此前两处各写一份、且都不生效——第一份把 `message` 丢掉，第二份压根没有。
 * 抽成一处后，改一处即可覆盖两条路径。
 *
 * <p><b>为什么用弹窗而不是 Flashbar</b>：失败后宿主会 `setScreen(SCREEN_MAIN)`，
 * 用 `TransitionManager` 重建整个视图树，挂在上面的浮条会被一并回收（实测日志
 * `Toast already killed`，提示一闪即没）。对话框是独立窗口，不受视图重建影响；
 * 而且错误信息（如 `FileNotFoundException: /path/...`）较长，弹窗比浮条好读。
 */
object TemplateCreateFailure {

  private val log = LoggerFactory.getLogger(TemplateCreateFailure::class.java)

  /**
   * 弹窗展示失败原因。
   *
   * @param fallbackContext 拿不到顶层 Activity 时的兜底来源；可为 null
   * @param message 失败原因（模板传上来的，通常是异常消息）；空则用通用文案
   */
  @JvmStatic
  @JvmOverloads
  fun show(fallbackContext: Context? = null, message: String?) {
    // 用**顶层 Activity** 而不是调用方的 context：这个回调是异步的（模板 create 是挂起
    // 函数），到达时向导多半已 dismiss、Fragment 可能已 detach，requireContext() 会抛；
    // 而 applicationContext 不能弹对话框（没有窗口令牌）。
    val host = ActivityUtils.getTopActivity() ?: (fallbackContext as? android.app.Activity)
    if (host == null || host.isFinishing) {
      // 拿不到宿主（App 已退到后台等）：退回日志，至少留痕而不是彻底静默。
      log.warn("模板创建失败但无可用 Activity 展示：{}", message)
      return
    }
    val text = resolveMessage(message, host.getString(R.string.template_create_failed))
    MaterialAlertDialogBuilder(host)
        .setTitle(R.string.template_create_failed_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .show()
  }

  /**
   * 决定弹窗正文：有具体原因就用具体原因，否则退回通用文案。
   *
   * <p>抽成纯函数是为了可 JVM 单测——[show] 依赖 Activity 与对话框，测不了；
   * 而「什么情况下用哪条文案」是这里唯一的判断逻辑，也正是曾经出错的地方
   * （原来调用方把 message 直接丢掉，用户只知道失败、不知道为什么）。
   *
   * @param message 模板回调传来的失败原因；可能为 null/空白
   * @param fallback 通用文案（由调用方取字符串资源，避免这里依赖 Context）
   */
  @JvmStatic
  fun resolveMessage(message: String?, fallback: String): String =
      message?.takeIf { it.isNotBlank() } ?: fallback
}
