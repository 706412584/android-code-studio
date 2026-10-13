/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.cloudbuild

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 云编译界面的纯逻辑：异常翻译、状态文案、workflow 选择与本地文件检查。
 *
 * <p><b>为什么单独成类</b>：这些函数不依赖 Android，可以在 JVM 上单测——
 * 「网络异常翻译成什么话」「默认选哪个 workflow」「本地有没有 workflow」
 * 都是错了用户就看不懂、或选错目标的地方，值得把判断固定下来；
 * 直接写在 Fragment 里则永远测不到。
 *
 * <p>不放进 [CloudBuildService]：服务层是「流程编排」，这里是「文案与展示」，
 * 两者的变更理由不同（前者随 GitHub API 演进，后者随界面文案调整）。
 */
object CloudBuildUiLogic {

  /**
   * 把异常翻译成用户能看懂、能行动的中文说明。
   *
   * <p>分两类：GitHub 的 API 错误（[GitHubApiException]）交给它自己的
   * [GitHubApiException.userMessage]；本地网络错误按「DNS / 超时 / 连接 / TLS」
   * 分别给出可操作的提示——用户看到「网络请求失败」不知道是没网还是被墙，
   * 看到「无法解析 GitHub 域名」才知道要检查网络。
   */
  @JvmStatic
  fun errorMessage(t: Throwable): String {
    if (t is GitHubApiException) {
      // 状态码 0 表示失败发生在本地（没走到 HTTP），此时 userMessage() 的
      // 「HTTP 0」反而误导，直接用原始消息。
      if (t.statusCode <= 0) {
        return t.message.orEmpty().ifEmpty { "操作失败。" }
      }
      return t.userMessage()
    }
    if (t is java.net.UnknownHostException) {
      return "网络不可用：无法解析 GitHub 域名，请检查网络连接（或代理设置）。"
    }
    if (t is java.net.SocketTimeoutException) {
      return "连接 GitHub 超时：网络较慢或被阻断，请稍后重试。"
    }
    if (t is javax.net.ssl.SSLException) {
      return "与 GitHub 的加密连接失败：可能存在代理或网络拦截。"
    }
    if (t is java.net.ConnectException) {
      return "无法连接 GitHub：请检查网络（或代理）是否可用。"
    }
    if (t is java.io.IOException) {
      return "网络请求失败：" + (t.message ?: t.javaClass.simpleName)
    }
    val message = t.message
    return if (message.isNullOrBlank()) "操作失败：" + t.javaClass.simpleName else message
  }

  /** job 进度行文案：「名称：状态」（状态文案由 [WorkflowJob.statusText] 提供，已是中文）。 */
  @JvmStatic
  fun jobLine(job: WorkflowJob): String = job.name + "：" + job.statusText()

  /** 运行标题：name 可能为空，不能显示空白行。 */
  @JvmStatic
  fun runTitle(run: WorkflowRun): String = if (run.name.isEmpty()) "未命名运行" else run.name

  /** 运行状态行：「状态：运行中 · 分支：main」。 */
  @JvmStatic
  fun runStatusLine(run: WorkflowRun): String {
    val branch = if (run.headBranch.isEmpty()) "未知" else run.headBranch
    return "状态：" + run.statusText() + " · 分支：" + branch
  }

  /**
   * 默认选中的 workflow：优先 build.yml / build.yaml（ACS 模板生成的名字），
   * 否则取第一个——空列表返回空串，由调用方决定如何提示。
   */
  @JvmStatic
  fun pickDefaultWorkflow(workflows: List<String>): String {
    if (workflows.isEmpty()) {
      return ""
    }
    return workflows.firstOrNull { it == "build.yml" }
        ?: workflows.firstOrNull { it == "build.yaml" }
        ?: workflows.first()
  }

  /**
   * 本地 `.github/workflows` 下的 yml/yaml 文件名（已排序）。
   *
   * <p>用途：远程仓库没有 workflow 时，用它区分「本地也没写」与「写了但没推」——
   * 两者的修复动作不同（前者要加文件，后者只需 git push），提示必须说准。
   */
  @JvmStatic
  fun localWorkflowFiles(projectDir: File): List<String> {
    val dir = File(projectDir, ".github/workflows")
    if (!dir.isDirectory) {
      return emptyList()
    }
    val files = dir.listFiles() ?: return emptyList()
    return files
        .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
        .map { it.name }
        .sorted()
  }

  // ---------------------------------------------------------------------------
  // 出包目标
  // ---------------------------------------------------------------------------

  /**
   * workflow 文件名 → 出包目标候选（界面上让用户勾选）。
   *
   * <p>ACS 的三个跨平台模板各生成一个 workflow，其 job 名即目标名：
   * `flutter.yml` → android/web/ios/windows/macos/linux；`reactnative.yml`
   * 或 RN 模板的 workflow → android/ios/web；`multiplatform.yml` →
   * android/desktop/web/ios。识别不出来（用户自己写的 workflow）时返回
   * 空列表——此时不显示目标选择，照旧发空 inputs（`targets` 未定义时
   * GitHub 会忽略它，旧 workflow 不受影响）。
   *
   * <p>按文件名而不是解析 YAML：设备端解析 YAML 需要引入解析库，而这三个
   * 文件名是模板生成时固定的（README 与 workflow 的 name: 都对应）。
   */
  @JvmStatic
  fun targetsForWorkflow(workflowFile: String): List<String> {
    val name = workflowFile.lowercase(Locale.ROOT)
    return when {
      name == "flutter.yml" || name == "flutter.yaml" ->
          listOf("android", "web", "ios", "windows", "macos", "linux")
      name == "reactnative.yml" || name == "reactnative.yaml" || name == "react-native.yml" ->
          listOf("android", "ios", "web")
      name == "multiplatform.yml" || name == "multiplatform.yaml" ->
          listOf("android", "desktop", "web", "ios")
      else -> emptyList()
    }
  }

  /**
   * 勾选状态 → `targets` input 值。
   *
   * <p>全选（或一个都没勾）→ `all`：不勾选时发 `all` 而不是空串，
   * 因为空串在 `contains(targets, 'android')` 判断下**什么都不出**——
   * 用户忘了勾选却拿到「全部跳过」的空运行是最费解的结果。
   * 全选发 `all` 同样走 `targets == 'all'` 分支，语义最明确。
   */
  @JvmStatic
  fun targetsInputValue(available: List<String>, selected: Collection<String>): String {
    if (available.isEmpty()) {
      return ""
    }
    val picked = available.filter { selected.contains(it) }
    if (picked.isEmpty() || picked.size == available.size) {
      return "all"
    }
    return picked.joinToString(",")
  }

  // ---------------------------------------------------------------------------
  // 历史列表
  // ---------------------------------------------------------------------------

  /** 超过一周的历史显示本地日期；`Locale.ROOT` 避免不同区域设置下格式漂移。 */
  private val HISTORY_DATE_FORMAT =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
          .withZone(ZoneId.systemDefault())

  /** 历史行文案：「状态 · 分支 · 时间」，例如「成功 · dev · 3 小时前」。 */
  @JvmStatic
  fun historyLine(run: WorkflowRun, nowMillis: Long): String {
    val branch = if (run.headBranch.isEmpty()) "未知" else run.headBranch
    return run.statusText() + " · " + branch + " · " + relativeTime(run.createdAt, nowMillis)
  }

  /**
   * ISO 8601 时间 → 相对时间文案（「刚刚 / N 分钟前 / N 小时前 / N 天前」，
   * 超过 7 天显示本地日期）。
   *
   * <p>`nowMillis` 由调用方传入而不是内部取 `System.currentTimeMillis()`：
   * 时间文案是「错了用户就觉得列表坏了」的地方（例如显示「-3 分钟前」），
   * 参数化才能把边界固定进测试。
   *
   * <p>解析失败（GitHub 理论上不会，但不能假设）时原样返回输入字符串——
   * 显示一个原始时间戳好过显示空白或崩溃。
   */
  @JvmStatic
  fun relativeTime(isoTime: String, nowMillis: Long): String {
    if (isoTime.isBlank()) {
      return "未知时间"
    }
    val createdMillis =
        try {
          Instant.parse(isoTime).toEpochMilli()
        } catch (e: DateTimeParseException) {
          return isoTime
        }
    val diffMs = nowMillis - createdMillis
    // 设备时钟可能比 GitHub 快，导致 diff 为负——显示「-3 分钟前」比显示「刚刚」更糟。
    if (diffMs < 60_000L) {
      return "刚刚"
    }
    val minutes = diffMs / 60_000L
    if (minutes < 60L) {
      return minutes.toString() + " 分钟前"
    }
    val hours = minutes / 60L
    if (hours < 24L) {
      return hours.toString() + " 小时前"
    }
    val days = hours / 24L
    if (days < 7L) {
      return days.toString() + " 天前"
    }
    // 一周以上：相对时间不再直观（「23 天前」还要用户自己换算），改显示日期。
    return HISTORY_DATE_FORMAT.format(Instant.ofEpochMilli(createdMillis))
  }
}
