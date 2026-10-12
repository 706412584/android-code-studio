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
}
