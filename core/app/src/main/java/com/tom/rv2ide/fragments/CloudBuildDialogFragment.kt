/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.fragments

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.WindowInsetsCompat.Type.navigationBars
import androidx.core.view.WindowInsetsCompat.Type.statusBars
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import android.view.Gravity
import android.widget.LinearLayout
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.R
import com.tom.rv2ide.cloudbuild.CloudBuildService
import com.tom.rv2ide.cloudbuild.CloudBuildTokenStore
import com.tom.rv2ide.cloudbuild.CloudBuildUiLogic
import com.tom.rv2ide.cloudbuild.GitHubApiException
import com.tom.rv2ide.cloudbuild.WorkflowArtifact
import com.tom.rv2ide.cloudbuild.WorkflowRun
import com.tom.rv2ide.cloudbuild.WorkflowRunPoller
import com.tom.rv2ide.databinding.LayoutCloudBuildDialogBinding
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.doOnApplyWindowInsets
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 云编译底部表单：识别仓库 → 选择 workflow / 分支 → 一键触发 → 看 job 进度 → 看失败原因 / 产物。
 *
 * <p><b>为什么是底部表单而不是独立 Activity</b>：与现有的构建入口（`RunTasksDialogFragment`
 * 的 Gradle 任务选择、`DialogProjectSettings` 的项目备份）形态一致，用户不需要离开当前
 * 编辑器上下文；云编译的等待期通常长达数分钟，独立页面反而会把用户困在一个空转的界面上。
 *
 * <p><b>为什么不用 `awaitCompletion`</b>：那个方法阻塞到终态，期间界面无法响应取消。
 * 本表单改为「触发一次 + 自己按 [POLL_INTERVAL_MS] 轮询 [CloudBuildService.poll]」，
 * 每轮都检查协程是否仍活跃——用户关掉表单或点「停止跟踪」即 [trackingJob] 取消，
 * 不会留下后台轮询（这也是任务要求里明确的一条）。
 *
 * <p><b>线程模型</b>：服务层全部方法都是同步阻塞的（含网络与 sleep），因此每次调用
 * 都包在 [withContext]`(Dispatchers.IO)` 里；协程作用域用 `viewLifecycleOwner.lifecycleScope`，
 * 视图销毁即自动取消（含正在进行中的网络请求所在协程）。
 *
 * <p><b>「取消构建」与「停止跟踪」是两回事</b>：前者调用 GitHub 的 cancel 接口
 * 真正终止远端运行（不可逆，需要确认对话框）；后者只停本地的轮询，构建继续跑。
 * 两者都在运行页，语义差别大，因此按钮分开、并在确认对话框里互相点名，
 * 避免用户把「停止跟踪」当成「取消构建」。
 */
class CloudBuildDialogFragment : BottomSheetDialogFragment() {

  private lateinit var binding: LayoutCloudBuildDialogBinding
  private lateinit var tokenStore: CloudBuildTokenStore

  /** 轮询任务；关闭表单 / 点「停止跟踪」时取消，确保不留后台轮询。 */
  private var trackingJob: Job? = null

  /** 当前会话的服务实例（每次打开界面重新识别仓库后创建）。 */
  private var service: CloudBuildService? = null

  private var currentRun: WorkflowRun? = null

  private var detectedOwner: String = ""
  private var detectedRepo: String = ""

  /** 已拉到的 workflow 列表；「再次构建」回到配置页时直接复用，不重复联网。 */
  private var availableWorkflows: List<String> = emptyList()

  /** 上一次渲染的 job 行内容；用于跳过无变化的视图重建（见 renderSnapshot）。 */
  private var lastJobLines: List<String>? = null

  /** 已拉到的历史 runs（详情页返回历史页时直接复用，不重复联网）。 */
  private var historyRuns: List<WorkflowRun> = emptyList()

  /** 当前所在页面（历史页返回时需要知道「从哪来」）。 */
  private var currentSection: Section = Section.LOADING

  /** 打开历史页之前的页面（「返回」回到这里，而不是重跑识别/触发流程）。 */
  private var sectionBeforeHistory: Section = Section.SETUP

  /** 是否已向 GitHub 发出取消请求（用于状态行反馈，避免被下一轮轮询盖掉）。 */
  private var cancelRequested = false

  companion object {

    const val TAG = "cloud_build_dialog"

    /**
     * 轮询间隔。GitHub API 认证后限额 5000 次/小时，12 秒一轮（每轮 2 个请求）
     * 远低于限额；再密也不会更快看到状态变化——GitHub 的 job 状态本身有延迟。
     */
    private const val POLL_INTERVAL_MS = 12_000L

    /** 历史列表一次拉取的条数：够翻几页，又不会让弹窗拉出一长串。 */
    private const val HISTORY_LIMIT = 20

    /** 下载产物 / 拉日志的临时目录名（放项目内，用户可见、可清理）。 */
    private const val CACHE_DIR_NAME = ".cloudbuild-cache"

    /** 产物落盘目录名（与项目源码分开，避免误入版本控制）。 */
    private const val OUTPUT_DIR_NAME = "cloudbuild-output"
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog =
        object : BottomSheetDialog(requireContext(), theme) {
          override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            findViewById<View>(com.google.android.material.R.id.container)?.apply {
              doOnApplyWindowInsets { view, insets, _, margins ->
                insets.getInsets(statusBars() or navigationBars()).apply {
                  view.updateLayoutParams<MarginLayoutParams> {
                    updateMargins(top = margins.top + top)
                  }
                  binding.scroll.updatePadding(bottom = bottom)
                }
              }
            }
          }
        }
    // 与 RunTasksDialogFragment 一致：占据 70% 屏高，进度列表才有足够空间。
    dialog.behavior.peekHeight = (getWindowHeight() * 0.7).toInt()
    return dialog
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    binding = LayoutCloudBuildDialogBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    tokenStore = CloudBuildTokenStore(requireContext())

    binding.close.setOnClickListener { dismiss() }
    binding.errorRetry.setOnClickListener { startDetection() }
    binding.errorConfigureToken.setOnClickListener { showTokenDialog() }
    binding.errorCreateRepo.setOnClickListener { confirmCreateRepo() }
    binding.configureToken.setOnClickListener { showTokenDialog() }
    binding.startBuild.setOnClickListener { startBuild() }
    binding.stopTracking.setOnClickListener { stopTracking(showResult = true) }
    binding.buildAgain.setOnClickListener { backToSetup() }
    binding.openRunPage.setOnClickListener { openRunPage() }
    binding.copyLog.setOnClickListener { copyFailureLog() }
    binding.cancelBuild.setOnClickListener { confirmCancelBuild() }
    binding.openHistory.setOnClickListener { showHistory() }
    binding.openHistoryRunning.setOnClickListener { showHistory() }
    binding.openHistoryResult.setOnClickListener { showHistory() }
    binding.historyBack.setOnClickListener { hideHistory() }
    binding.historyRefresh.setOnClickListener { loadHistory() }

    startDetection()
  }

  override fun onDestroyView() {
    // 显式取消（viewLifecycleOwner 的取消是兜底）：轮询协程持着服务层引用，
    // 早一刻释放，早一刻停止网络请求。
    trackingJob?.cancel()
    trackingJob = null
    super.onDestroyView()
  }

  // ---------------------------------------------------------------------------
  // 流程：识别仓库
  // ---------------------------------------------------------------------------

  /** 打开界面即自动识别：仓库 → workflow 列表 → 当前分支。任一步失败给出明确提示。 */
  private fun startDetection() {
    showSection(Section.LOADING)
    binding.loadingText.setText(R.string.cloud_build_detecting_repo)

    val projectDir = IProjectManager.getInstance().projectDirOrNull
    if (projectDir == null) {
      showError(getString(R.string.cloud_build_no_project))
      return
    }

    viewLifecycleOwner.lifecycleScope.launch {
      // 仓库识别读的是本地 .git，很快，但仍不能在主线程做（JGit 是阻塞 IO）。
      val slug = withContext(Dispatchers.IO) { CloudBuildService.detectRepository(projectDir.path) }
      val branch = withContext(Dispatchers.IO) { CloudBuildService.detectBranch(projectDir.path) }

      // 挂起点之后先查活跃性：取消后不得触碰已 detach 的 UI（同 startTracking）。
      if (!isActive) {
        return@launch
      }
      if (slug == null || !slug.isValid) {
        // 建仓需要 token（创建仓库是写操作）。没 token 时只提示配置 token——
        // 露出一个点下去必然失败的按钮比不露更糟。
        showError(
            buildNoRepoMessage(projectDir),
            showTokenEntry = !tokenStore.hasToken(),
            showCreateRepo = tokenStore.hasToken(),
        )
        return@launch
      }

      detectedOwner = slug.owner
      detectedRepo = slug.repo
      binding.branchInput.setText(branch.orEmpty())

      val token = tokenStore.getToken()
      val svc = CloudBuildService(token, slug.owner, slug.repo)
      service = svc

      // workflow 列表要联网；无 token 时 GitHub 对私有仓库返回 404，
      // 但公开仓库仍可读——因此先照常尝试，失败时再按 token 状态给提示。
      try {
        val workflows = withContext(Dispatchers.IO) { svc.client.listWorkflows() }
        // 挂起点之后先查活跃性：取消后不得触碰已 detach 的 UI（同 startTracking）。
        if (!isActive) {
          return@launch
        }
        if (workflows.isEmpty()) {
          showError(buildNoWorkflowMessage(projectDir))
          return@launch
        }
        showSetup(slug.owner + "/" + slug.repo, workflows)
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        if (!isActive) {
          return@launch
        }
        // 鉴权失败时把「配置 Token」按钮一并露出：只说「鉴权失败」而不给入口，
        // 用户还得自己去猜从哪里配。
        showError(CloudBuildUiLogic.errorMessage(t), showTokenEntry = isAuthProblem(t))
      }
    }
  }

  /** 是否是「配置 token 就能解决」的失败（401/403，或无 token 时的 404）。 */
  private fun isAuthProblem(t: Throwable): Boolean {
    if (t !is GitHubApiException) {
      return false
    }
    // 无 token 访问私有仓库时 GitHub 返回 404（不泄露仓库存在性），
    // 这种 404 同样是「配 token 才能解决」。
    return t.isAuthProblem || (t.isNotFound && !tokenStore.hasToken())
  }

  /**
   * 不是 git 仓库 / 没有 GitHub remote 时的提示。
   *
   * <p>区分两种情形：有 `.git` 但 remote 不是 GitHub（提示只支持 GitHub Actions），
   * 与完全没有仓库（提示先建仓库并推送）。只说「无法识别」用户不知道下一步做什么。
   */
  private fun buildNoRepoMessage(projectDir: File): String {
    val hasGit = File(projectDir, ".git").exists()
    return if (hasGit) {
      getString(R.string.cloud_build_no_github_remote)
    } else {
      getString(R.string.cloud_build_not_git_repo)
    }
  }

  /**
   * 远程没有 workflow 时的提示。
   *
   * <p>本地 `.github/workflows` 有文件 → 是「没推」；本地也没有 → 是「没建」。
   * 两者的修复动作完全不同，提示必须说准，否则用户会去推一个不存在的文件。
   */
  private fun buildNoWorkflowMessage(projectDir: File): String {
    val local = CloudBuildUiLogic.localWorkflowFiles(projectDir)
    return if (local.isEmpty()) {
      getString(R.string.cloud_build_no_workflow_local)
    } else {
      getString(R.string.cloud_build_workflow_not_pushed, local.joinToString("、"))
    }
  }

  // ---------------------------------------------------------------------------
  // 流程：触发
  // ---------------------------------------------------------------------------

  private fun startBuild() {
    val svc = service ?: return
    if (!tokenStore.hasToken()) {
      // 触发 workflow 必须要 token（dispatch 是写操作），这里不放行。
      showTokenDialog()
      return
    }

    val workflow = binding.workflowInput.text?.toString()?.trim().orEmpty()
    if (workflow.isEmpty()) {
      flashError(getString(R.string.cloud_build_select_workflow))
      return
    }
    val branch = binding.branchInput.text?.toString()?.trim().orEmpty()
    if (branch.isEmpty()) {
      flashError(getString(R.string.cloud_build_input_branch))
      return
    }

    showSection(Section.LOADING)
    binding.loadingText.setText(R.string.cloud_build_triggering)

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        // runInterruptible 而不是 withContext(IO)：triggerAndAwaitRun 内部是
        // 「最长 60 秒的 Thread.sleep 轮询」，withContext 的取消要等 block 自然
        // 结束才生效——用户关掉表单后仍会跑满 60 秒并持续发请求。runInterruptible
        // 会在取消时中断线程，Thread.sleep 立即抛出，真正停掉等待。
        val handle =
            runInterruptible(Dispatchers.IO) {
              svc.triggerAndAwaitRun(workflow, branch, emptyMap())
            }
        // 挂起点之后先查活跃性：用户可能在等待期间关掉了表单。
        if (!isActive) {
          return@launch
        }
        currentRun = handle.run
        showRunning(handle.run)
        startTracking(handle.run.id, workflow, branch)
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        // 同 startTracking：runInterruptible 中断网络读抛的 InterruptedIOException
        // 会绕过取消重抛，取消后不得触碰已 detach 的 UI（实测崩溃点）。
        if (!isActive) {
          return@launch
        }
        showError(CloudBuildUiLogic.errorMessage(t))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 流程：轮询进度
  // ---------------------------------------------------------------------------

  private fun startTracking(runId: Long, workflow: String, branch: String) {
    trackingJob?.cancel()
    val svc = service ?: return

    trackingJob =
        viewLifecycleOwner.lifecycleScope.launch {
          while (isActive) {
            try {
              // runInterruptible：取消时中断进行中的网络读，不留悬挂连接。
              val snapshot =
                  runInterruptible(Dispatchers.IO) { svc.poll(runId, workflow, branch) }
              if (snapshot != null) {
                renderSnapshot(snapshot)
                if (snapshot.isFinished) {
                  // 终态：拉失败日志或产物，然后退出轮询循环。
                  onRunFinished(snapshot)
                  return@launch
                }
              }
            } catch (t: Throwable) {
              if (t is CancellationException) {
                throw t
              }
              // runInterruptible 中断网络读时抛的是 InterruptedIOException（不是
              // CancellationException），协程被取消时它会走到这里——此时 fragment
              // 已 detach，触碰 UI 会抛「not attached to a context」崩溃（实测）。
              // 因此先查协程是否仍活跃，被取消即静默退出，不做任何 UI 操作。
              if (!isActive) {
                return@launch
              }
              // 轮询失败不终止跟踪：网络抖动一次就放弃会让用户以为构建丢了。
              // 但要在界面上如实显示，而不是静默重试。
              binding.runStatus.text =
                  getString(R.string.cloud_build_poll_failed, CloudBuildUiLogic.errorMessage(t))
            }
            delay(POLL_INTERVAL_MS)
          }
        }
  }

  /** 用户主动停止跟踪：停止轮询但展示当前已知状态（构建仍在 GitHub 上继续跑）。 */
  private fun stopTracking(showResult: Boolean) {
    trackingJob?.cancel()
    trackingJob = null
    if (showResult) {
      val run = currentRun ?: return
      if (run.isFinished) {
        showResult(run)
      } else {
        binding.runStatus.text = getString(R.string.cloud_build_tracking_stopped)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 流程：取消构建（远端不可逆操作）
  // ---------------------------------------------------------------------------

  /**
   * 取消构建的确认对话框。
   *
   * <p>取消是不可逆的远端操作，必须二次确认；文案里点名「停止跟踪」的区别——
   * 两个按钮挨着，用户点错一个就会误杀一次正在跑的构建（或者相反，
   * 以为构建停了其实还在跑）。
   */
  private fun confirmCancelBuild() {
    val run = currentRun ?: return
    if (run.isFinished) {
      // 运行已经结束（可能刚好在这几秒里跑完）：轮询会把界面切到结果页，
      // 这里不需要再发取消请求。
      showResult(run)
      return
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.cloud_build_cancel_confirm_title)
        .setMessage(R.string.cloud_build_cancel_confirm_message)
        .setPositiveButton(R.string.cloud_build_cancel_confirm_yes) { _, _ -> cancelBuild(run.id) }
        .setNegativeButton(R.string.cloud_build_cancel_confirm_no, null)
        .show()
  }

  private fun cancelBuild(runId: Long) {
    val svc = service ?: return
    // 立即给出反馈：cancel 接口是异步的，run 状态要过几秒才变 cancelled。
    binding.cancelBuild.isEnabled = false
    binding.runStatus.text = getString(R.string.cloud_build_cancelling)

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        runInterruptible(Dispatchers.IO) { svc.cancelRun(runId) }
        // 挂起点之后先查活跃性：取消后不得触碰已 detach 的 UI（同 startTracking）。
        if (!isActive) {
          return@launch
        }
        cancelRequested = true
        binding.runStatus.text = getString(R.string.cloud_build_cancel_requested)
        // 若此前已「停止跟踪」，轮询已停——重启它来观察取消结果，否则状态会
        // 永远停在「已请求取消」（取消是异步的，只能靠轮询等到终态）。
        if (trackingJob?.isActive != true) {
          val workflow = binding.workflowInput.text?.toString()?.trim().orEmpty()
          val branch = binding.branchInput.text?.toString()?.trim().orEmpty()
          startTracking(runId, workflow, branch)
        }
        // 不在这里停轮询：状态变化（completed / cancelled）由既有轮询观察，
        // 到终态后走 showResult，逻辑与「构建自然结束」完全同路。
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        // 同 startTracking：取消中断抛的 InterruptedIOException 绕过取消重抛。
        if (!isActive) {
          return@launch
        }
        binding.cancelBuild.isEnabled = true
        flashError(getString(R.string.cloud_build_cancel_failed, CloudBuildUiLogic.errorMessage(t)))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 流程：历史构建
  // ---------------------------------------------------------------------------

  /**
   * 打开历史页。已有缓存时先渲染再后台刷新（stale-while-revalidate）——
   * 每次打开都转圈会让「看一眼上次跑成什么样」这种高频操作变慢，
   * 但只信缓存又会显示过期的状态（刚结束的 run 还是「运行中」）。
   */
  private fun showHistory() {
    if (currentSection != Section.HISTORY) {
      sectionBeforeHistory = currentSection
    }
    showSection(Section.HISTORY)
    if (historyRuns.isNotEmpty()) {
      renderHistory()
    }
    loadHistory()
  }

  /** 从历史页返回：回到进入历史前的页面（通常是配置页或结果页）。 */
  private fun hideHistory() {
    // 跟踪中的 run 在用户翻历史期间结束了：onRunFinished 已经把结果内容填好
    // （只是没切页），此时应落到结果页而不是过时的运行页。
    val trackedFinished = currentRun?.isFinished == true
    when (sectionBeforeHistory) {
      Section.RUNNING -> showSection(if (trackedFinished) Section.RESULT else Section.RUNNING)
      Section.RESULT -> showSection(Section.RESULT)
      else -> showSection(Section.SETUP)
    }
  }

  /**
   * 拉历史 runs。
   *
   * <p>只按 workflow 过滤、不按分支：用户想看的是「这个 workflow 最近跑成什么样」，
   * 加上分支过滤会让切到别的分支时历史变成空（而构建明明跑过）。每行自带分支列，
   * 用户自己就能分辨。
   */
  private fun loadHistory() {
    val svc = service ?: return
    val workflow = binding.workflowInput.text?.toString()?.trim().orEmpty()

    binding.historyLoading.visibility = View.VISIBLE
    binding.historyError.visibility = View.GONE
    binding.historyRefresh.isEnabled = false

    viewLifecycleOwner.lifecycleScope.launch {
      val runs =
          try {
            runInterruptible(Dispatchers.IO) {
              svc.listRuns(workflow.ifEmpty { null }, null, HISTORY_LIMIT)
            }
          } catch (t: Throwable) {
            if (t is CancellationException) {
              throw t
            }
            // 同 startTracking：取消中断抛的 InterruptedIOException 绕过取消重抛。
            if (!isActive) {
              return@launch
            }
            binding.historyLoading.visibility = View.GONE
            binding.historyRefresh.isEnabled = true
            // 失败信息留在历史页内（而不是跳到错误页）：错误页会丢掉「返回」路径，
            // 用户重试的上下文（当前 workflow/分支）全在历史页的入口上。
            binding.historyError.text =
                getString(R.string.cloud_build_history_load_failed, CloudBuildUiLogic.errorMessage(t))
            binding.historyError.visibility = View.VISIBLE
            return@launch
          }

      // 挂起点之后可能已被取消（上面的 catch 只在异常路径检查；成功返回也要查）。
      if (!isActive) {
        return@launch
      }

      historyRuns = runs
      binding.historyLoading.visibility = View.GONE
      binding.historyRefresh.isEnabled = true
      renderHistory()
    }
  }

  private fun renderHistory() {
    val container = binding.historyContainer
    container.removeAllViews()
    binding.historyEmpty.visibility = if (historyRuns.isEmpty()) View.VISIBLE else View.GONE

    val now = System.currentTimeMillis()
    for (run in historyRuns) {
      container.addView(newHistoryRow(run, now))
    }
  }

  /** 一行历史：状态 · 分支 · 时间；点击进入该 run 的详情。 */
  private fun newHistoryRow(run: WorkflowRun, nowMillis: Long): View {
    val row = MaterialTextView(requireContext())
    row.text = CloudBuildUiLogic.historyLine(run, nowMillis)
    row.setTextAppearance(R.style.TextAppearance_Material3_BodyLarge)
    row.setPadding(dp(8), dp(12), dp(8), dp(12))
    // 点击行要有反馈：历史行是「可点的」，没有 ripple 用户会以为只是文字。
    val outValue = android.util.TypedValue()
    requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    row.setBackgroundResource(outValue.resourceId)
    row.setOnClickListener { openHistoryRun(run) }
    return row
  }

  /**
   * 进入某个历史 run 的详情。
   *
   * <p>未结束的 run 重新开始轮询（用户可能关掉过界面，run 还在跑）；
   * 已结束的直接展示结果并拉产物/失败日志——与「构建自然结束」走同一套
   * [showResult] / [loadArtifacts] / [loadFailureLog]，不重复实现。
   */
  private fun openHistoryRun(run: WorkflowRun) {
    // 打开的是另一个 run 的详情：旧的跟踪任务必须停掉，否则它会在后台继续
    // 轮询、并在结束时把界面抢到它自己的结果页（用户在看 B，突然跳出 A 的结果）。
    // 语义与「停止跟踪」一致：只停本地跟踪，A 的构建继续在 GitHub 上跑。
    if (currentRun?.id != run.id) {
      trackingJob?.cancel()
      trackingJob = null
    }
    currentRun = run
    val workflow = binding.workflowInput.text?.toString()?.trim().orEmpty()
    val branch = binding.branchInput.text?.toString()?.trim().orEmpty()
    cancelRequested = false
    if (run.isFinished()) {
      showResult(run)
      if (run.isSuccess) {
        loadArtifacts(run.id)
      } else {
        loadFailureLog(run.id, isCancelledRun = run.conclusion == WorkflowRun.CONCLUSION_CANCELLED)
      }
    } else {
      showRunning(run)
      startTracking(run.id, workflow, branch)
    }
  }

  private fun renderSnapshot(snapshot: WorkflowRunPoller.Snapshot) {
    currentRun = snapshot.run
    binding.runTitle.text = CloudBuildUiLogic.runTitle(snapshot.run)
    binding.runStatus.text =
        if (cancelRequested && !snapshot.run.isFinished()) {
          // GitHub 处理取消有秒级到十几秒的延迟，期间 run 仍是「运行中」——
          // 不带上「已请求取消」的话，用户会以为取消没生效又去点一次。
          CloudBuildUiLogic.runStatusLine(snapshot.run) + " · " +
              getString(R.string.cloud_build_cancel_requested)
        } else {
          CloudBuildUiLogic.runStatusLine(snapshot.run)
        }

    val container = binding.jobContainer
    val lines =
        if (snapshot.jobs.isEmpty()) {
          listOf(getString(R.string.cloud_build_jobs_pending))
        } else {
          snapshot.jobs.map { CloudBuildUiLogic.jobLine(it) }
        }

    // 内容没变就不重建视图：12 秒一次的轮询如果每次都 removeAllViews + addView，
    // 会把用户正在长按选择的文字打断，也造成无谓的闪烁。
    if (lines == lastJobLines) {
      return
    }
    lastJobLines = lines

    // job 数量少（通常 1-4 个），直接重建视图；用 RecyclerView 会引入
    // 一个只有几行的 adapter，反而更重。
    container.removeAllViews()
    if (snapshot.jobs.isEmpty()) {
      container.addView(newJobLine(lines[0]))
      return
    }
    for ((index, job) in snapshot.jobs.withIndex()) {
      container.addView(newJobLine(lines[index], job.isFailed))
    }
  }

  private fun newJobLine(text: String, isFailed: Boolean = false): View {
    val view = MaterialTextView(requireContext())
    view.text = text
    view.setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
    view.setPadding(0, dp(4), 0, dp(4))
    if (isFailed) {
      view.setTextColor(com.google.android.material.color.MaterialColors.getColor(view, R.attr.colorError, 0))
    }
    return view
  }

  // ---------------------------------------------------------------------------
  // 流程：终态处理
  // ---------------------------------------------------------------------------

  private suspend fun onRunFinished(snapshot: WorkflowRunPoller.Snapshot) {
    // 调用点紧跟轮询的挂起点：取消后（含 runInterruptible 的 InterruptedIOException
    // 路径）不得进入 UI 渲染。
    if (!currentCoroutineContext().isActive) {
      return
    }
    val run = snapshot.run
    currentRun = run
    // 用户在历史页时不要把界面拽走：结果内容照常准备好（下面照常调用），
    // 但保持历史页可见；返回时 hideHistory 会按 run 的终态落到结果页。
    val switchSection = currentSection != Section.HISTORY
    if (run.isSuccess) {
      showResult(run, switchToSection = switchSection)
      loadArtifacts(run.id)
    } else {
      // 失败 / 取消：先展示结果骨架，再异步补上失败日志。
      showResult(run, switchToSection = switchSection)
      loadFailureLog(run.id, isCancelledRun = run.conclusion == WorkflowRun.CONCLUSION_CANCELLED)
    }
  }

  /**
   * 拉失败日志（可能下载几十 MB 的日志 zip，慢，必须在 IO 上且允许取消）。
   *
   * <p>[isCancelledRun] 为 true 时标题改成「运行日志」：被取消的 run 没有
   * 「失败原因」可言，套用失败标题会让用户去找一个不存在的错误。
   */
  private fun loadFailureLog(runId: Long, isCancelledRun: Boolean = false) {
    val svc = service ?: return
    val projectDir = IProjectManager.getInstance().projectDirOrNull ?: return

    binding.failureTitle.setText(
        if (isCancelledRun) R.string.cloud_build_cancelled_log_title else R.string.cloud_build_failure_title
    )
    binding.failureGroup.visibility = View.VISIBLE
    binding.failureLog.text = getString(R.string.cloud_build_fetching_log)

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        val result =
            runInterruptible(Dispatchers.IO) {
              val cacheDir = File(projectDir, CACHE_DIR_NAME).apply { mkdirs() }
              svc.fetchFailureLog(runId, cacheDir)
            }
        binding.failureLog.text = result.text
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        // 同 startTracking：取消中断抛的 InterruptedIOException 绕过重抛，先查活跃性。
        if (!isActive) {
          return@launch
        }
        binding.failureLog.text = CloudBuildUiLogic.errorMessage(t)
      }
    }
  }

  /** 列出产物并提供下载按钮。 */
  private fun loadArtifacts(runId: Long) {
    val svc = service ?: return
    val projectDir = IProjectManager.getInstance().projectDirOrNull ?: return

    binding.artifactGroup.visibility = View.VISIBLE
    binding.artifactContainer.removeAllViews()

    viewLifecycleOwner.lifecycleScope.launch {
      val artifacts =
          try {
            runInterruptible(Dispatchers.IO) { svc.client.listArtifacts(runId) }
          } catch (t: Throwable) {
            if (t is CancellationException) {
              throw t
            }
            // 同 startTracking：取消中断抛的 InterruptedIOException 绕过重抛。
            if (!isActive) {
              return@launch
            }
            binding.artifactContainer.addView(
                newJobLine(getString(R.string.cloud_build_artifact_list_failed, CloudBuildUiLogic.errorMessage(t)))
            )
            return@launch
          }

      // 挂起点之后可能已被取消（上面的 catch 只在异常路径检查；成功返回也要查）。
      if (!isActive) {
        return@launch
      }

      if (artifacts.isEmpty()) {
        binding.artifactContainer.addView(newJobLine(getString(R.string.cloud_build_no_artifacts)))
        return@launch
      }

      for (artifact in artifacts) {
        binding.artifactContainer.addView(newArtifactRow(artifact, runId, projectDir))
      }
    }
  }

  /** 一行产物：名称 + 大小 + 下载按钮（过期产物禁用并标注）。 */
  private fun newArtifactRow(artifact: WorkflowArtifact, runId: Long, projectDir: File): View {
    val row = LinearLayout(requireContext())
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, dp(4), 0, dp(4))

    val label = MaterialTextView(requireContext())
    label.text =
        if (artifact.expired) {
          getString(R.string.cloud_build_artifact_expired, artifact.name)
        } else {
          getString(R.string.cloud_build_artifact_size, artifact.name, artifact.sizeText())
        }
    label.setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
    row.addView(
        label,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )

    val button = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    button.text = getString(R.string.cloud_build_download)
    button.isEnabled = !artifact.expired
    button.setOnClickListener { downloadArtifact(artifact, runId, projectDir, button, label) }
    row.addView(button)

    return row
  }

  /** 下载产物到项目目录，按钮文案承载进度（服务层无进度回调，只能显示进行中）。 */
  private fun downloadArtifact(
      artifact: WorkflowArtifact,
      runId: Long,
      projectDir: File,
      button: MaterialButton,
      label: MaterialTextView,
  ) {
    val svc = service ?: return
    button.isEnabled = false
    button.setText(R.string.cloud_build_downloading)

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        val saved =
            runInterruptible(Dispatchers.IO) {
              val outDir = File(projectDir, OUTPUT_DIR_NAME).apply { mkdirs() }
              svc.downloadArtifact(runId, artifact.name, outDir)
            }
        label.text = getString(R.string.cloud_build_downloaded_to, saved.absolutePath)
        button.setText(R.string.cloud_build_downloaded)
        flashSuccess(getString(R.string.cloud_build_downloaded_to, saved.absolutePath))
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        // 同 startTracking：取消中断抛的 InterruptedIOException 绕过重抛。
        if (!isActive) {
          return@launch
        }
        button.isEnabled = true
        button.setText(R.string.cloud_build_download)
        flashError(CloudBuildUiLogic.errorMessage(t))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 一键建仓并推送（方案 A）
  // ---------------------------------------------------------------------------

  /**
   * 建仓前的确认对话框。
   *
   * <p>建仓是**写操作**（在用户账号下创建仓库、推送源码），必须确认。文案说清
   * 「私有」——用户对「推到 GitHub」的第一反应往往是「我的代码要被公开了？」，
   * 明确私有能消除这个顾虑（真要公开是显式选择，本流程不提供）。
   */
  private fun confirmCreateRepo() {
    val projectDir = IProjectManager.getInstance().projectDirOrNull ?: return
    val repoName = CloudBuildService.deriveRepoName(projectDir.name)
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.cloud_build_create_repo_confirm_title)
        .setMessage(getString(R.string.cloud_build_create_repo_confirm_message, repoName))
        .setPositiveButton(R.string.cloud_build_create_repo) { _, _ -> createRepoAndPush() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** 执行建仓 + 提交 + 推送，完成后重新走识别流程。 */
  private fun createRepoAndPush() {
    val projectDir = IProjectManager.getInstance().projectDirOrNull ?: return
    val token = tokenStore.getToken()

    showSection(Section.LOADING)
    binding.loadingText.setText(R.string.cloud_build_create_repo_working)

    viewLifecycleOwner.lifecycleScope.launch {
      try {
        // 建仓 + 推送是长操作（网络 + JGit），必须 runInterruptible——
        // withContext 的取消要等 block 结束，用户关掉表单后会继续跑。
        val result =
            runInterruptible(Dispatchers.IO) {
              // owner/repo 未定（这正是「没有 remote」的情形）：用一个占位
              // 服务实例走建仓——createRepositoryAndPush 只用 client 的
              // token/API 能力，owner 由响应决定。
              val svc = CloudBuildService(token, "", "")
              svc.createRepositoryAndPush(projectDir.path, null, true)
            }
        // 挂起点之后先查活跃性（同 startTracking）。
        if (!isActive) {
          return@launch
        }
        flashSuccess(
            getString(
                R.string.cloud_build_create_repo_done,
                result.repo.owner + "/" + result.repo.repo,
            )
        )
        // 推送完成：重新识别（此时 remote 已在，识别会成功并进入配置页）。
        startDetection()
      } catch (t: Throwable) {
        if (t is CancellationException) {
          throw t
        }
        if (!isActive) {
          return@launch
        }
        val message =
            if (t is GitHubApiException) t.userMessage() else CloudBuildUiLogic.errorMessage(t)
        showError(
            getString(R.string.cloud_build_create_repo_failed, message),
            showTokenEntry = t is GitHubApiException && t.isAuthProblem,
            showCreateRepo = true,
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Token 配置
  // ---------------------------------------------------------------------------

  /**
   * Token 配置对话框。
   *
   * <p>密码输入框（`textPassword` + 眼睛切换）；说明里写清所需 scope——
   * 缺 `workflow` scope 的 token 能读仓库但 dispatch 会被 403 拒绝，
   * 而错误信息只说「拒绝了请求」，用户很难自己联想到是 scope 问题。
   */
  private fun showTokenDialog() {
    val context = requireContext()
    val inputLayout =
        TextInputLayout(context).apply {
          boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
          hint = getString(R.string.cloud_build_token_hint)
          endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
          setPadding(dp(24), dp(8), dp(24), 0)
        }
    val input =
        TextInputEditText(context).apply {
          inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
          setText(tokenStore.getToken())
          maxLines = 1
        }
    inputLayout.addView(input)

    val container = LinearLayout(context)
    container.orientation = LinearLayout.VERTICAL
    container.addView(inputLayout)

    val help = MaterialTextView(context)
    help.text = getString(R.string.cloud_build_token_help)
    help.setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
    help.setPadding(dp(24), dp(4), dp(24), 0)
    container.addView(help)

    if (!tokenStore.isPersistent()) {
      val warn = MaterialTextView(context)
      warn.text = getString(R.string.cloud_build_token_not_persistent)
      warn.setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
      warn.setPadding(dp(24), dp(8), dp(24), 0)
      container.addView(warn)
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.cloud_build_token_title)
        .setView(container)
        .setPositiveButton(R.string.cloud_build_token_save) { _, _ ->
          val previous = tokenStore.getToken()
          tokenStore.saveToken(input.text?.toString().orEmpty())
          // token 变了，重建服务实例；已识别的仓库信息保留。
          service = CloudBuildService(tokenStore.getToken(), detectedOwner, detectedRepo)
          updateTokenStatus()
          flashSuccess(getString(R.string.cloud_build_token_saved))

          // 从「鉴权失败」的错误页配好 token 后自动重试一次——否则用户还要
          // 自己点「重试」，而错误页的下一步本来就是配置 token。
          if (tokenStore.getToken() != previous && binding.sectionError.visibility == View.VISIBLE) {
            startDetection()
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun updateTokenStatus() {
    binding.tokenStatus.text =
        if (tokenStore.hasToken()) {
          getString(R.string.cloud_build_token_configured)
        } else {
          getString(R.string.cloud_build_token_missing)
        }
  }

  // ---------------------------------------------------------------------------
  // 界面状态切换
  // ---------------------------------------------------------------------------

  private enum class Section {
    LOADING,
    ERROR,
    SETUP,
    RUNNING,
    RESULT,
    HISTORY,
  }

  private fun showSection(section: Section) {
    currentSection = section
    TransitionManager.beginDelayedTransition(
        binding.root,
        MaterialSharedAxis(MaterialSharedAxis.Y, true),
    )
    binding.sectionLoading.visibility = if (section == Section.LOADING) View.VISIBLE else View.GONE
    binding.sectionError.visibility = if (section == Section.ERROR) View.VISIBLE else View.GONE
    binding.sectionSetup.visibility = if (section == Section.SETUP) View.VISIBLE else View.GONE
    binding.sectionRunning.visibility = if (section == Section.RUNNING) View.VISIBLE else View.GONE
    binding.sectionResult.visibility = if (section == Section.RESULT) View.VISIBLE else View.GONE
    binding.sectionHistory.visibility = if (section == Section.HISTORY) View.VISIBLE else View.GONE
  }

  /**
   * 错误页。`showTokenEntry` 为 true 时额外露出「配置 Token」按钮——
   * 鉴权类失败只有配 token 才能解决，把入口放在错误信息旁边是最短的修复路径。
   */
  private fun showError(
      message: String,
      showTokenEntry: Boolean = false,
      showCreateRepo: Boolean = false,
  ) {
    binding.errorText.text = message
    binding.errorConfigureToken.visibility = if (showTokenEntry) View.VISIBLE else View.GONE
    // 「没有 GitHub 远程仓库」时提供一键建仓——重试改变不了没有 remote 的事实，
    // 建仓推送才是用户真正的下一步。
    binding.errorCreateRepo.visibility = if (showCreateRepo) View.VISIBLE else View.GONE
    showSection(Section.ERROR)
  }

  private fun showSetup(repoFullName: String, workflows: List<String>) {
    availableWorkflows = workflows
    binding.repoText.text = getString(R.string.cloud_build_repo, repoFullName)
    updateTokenStatus()

    // ExposedDropdownMenu 的下拉项：直接给数组，点击即填入输入框。
    binding.workflowInput.setSimpleItems(workflows.toTypedArray())
    val defaultWorkflow = CloudBuildUiLogic.pickDefaultWorkflow(workflows)
    binding.workflowInput.setText(defaultWorkflow, false)

    showSection(Section.SETUP)
  }

  /**
   * 「再次构建」：回到配置页而不重新识别仓库。
   *
   * <p>不重跑 [startDetection] 的理由：仓库与 workflow 列表在本次会话内不会变，
   * 重跑要多两次网络请求，且用户可能已改过分支输入——回配置页应保留那些输入。
   */
  private fun backToSetup() {
    trackingJob?.cancel()
    trackingJob = null
    val repoFullName =
        if (detectedOwner.isEmpty()) "" else detectedOwner + "/" + detectedRepo
    showSetup(repoFullName, availableWorkflows)
  }

  private fun showRunning(run: WorkflowRun) {
    currentRun = run
    // 每次进入运行页都重置按钮与取消标记：上一轮的取消状态不能带到这一轮
    // （例如取消失败后按钮要恢复可点；历史页点进另一个 run 时同样如此）。
    cancelRequested = false
    binding.cancelBuild.isEnabled = true
    binding.runTitle.text = CloudBuildUiLogic.runTitle(run)
    binding.runStatus.text = CloudBuildUiLogic.runStatusLine(run)
    // 记下这个占位行，让首轮「job 尚未出现」的快照不会重复渲染同一内容。
    val pending = getString(R.string.cloud_build_jobs_pending)
    lastJobLines = listOf(pending)
    binding.jobContainer.removeAllViews()
    binding.jobContainer.addView(newJobLine(pending))
    showSection(Section.RUNNING)
  }

  private fun showResult(run: WorkflowRun, switchToSection: Boolean = true) {
    binding.resultTitle.text = CloudBuildUiLogic.runTitle(run)
    binding.resultStatus.text = CloudBuildUiLogic.runStatusLine(run)
    // 每次进入结果页先清掉上一次的失败日志 / 产物，避免「上次的日志」被误读成本次的。
    binding.failureGroup.visibility = View.GONE
    binding.artifactGroup.visibility = View.GONE
    binding.failureLog.text = ""
    binding.artifactContainer.removeAllViews()
    if (switchToSection) {
      showSection(Section.RESULT)
    }
  }

  // ---------------------------------------------------------------------------
  // 杂项
  // ---------------------------------------------------------------------------

  private fun openRunPage() {
    val url = currentRun?.htmlUrl.orEmpty()
    if (url.isEmpty()) {
      flashError(getString(R.string.cloud_build_no_run_page))
      return
    }
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (t: Throwable) {
      flashError(getString(R.string.cloud_build_open_page_failed))
    }
  }

  private fun copyFailureLog() {
    val text = binding.failureLog.text?.toString().orEmpty()
    if (text.isEmpty()) {
      return
    }
    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("cloudbuild-log", text))
    flashSuccess(getString(R.string.cloud_build_log_copied))
  }

  private fun dp(value: Int): Int =
      (value * resources.displayMetrics.density).toInt()

  private fun getWindowHeight(): Int {
    val height =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          activity?.windowManager?.currentWindowMetrics?.bounds?.height()!!
        } else {
          val displayMetrics = DisplayMetrics()
          activity?.windowManager?.defaultDisplay?.getMetrics(displayMetrics)
          displayMetrics.heightPixels
        }
    return height
  }
}
