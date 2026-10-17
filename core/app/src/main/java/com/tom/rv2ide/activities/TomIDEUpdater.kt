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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.resources.R
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONObject

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class TomIDEUpdater(private val context: Context) {

  companion object {
    private const val TAG = "TomIDEUpdater"

    /**
     * 更新清单地址（常规版）。
     *
     * <p>指向**本仓库**而非上游 AndroidCSOfficial：本 fork 的版本号与发布产物由自己维护，
     * 拉上游清单会拿别人的 versionCode 与下载地址来比对，结果是「版本永远落后」
     * 或「下载到别人的包」。改成自己的仓库后，只要 `updater.json` 里的 versionCode 与
     * `PROJECT_CONFIG_KT_BASE_VERSION_CODE` 同步，就不会误报。
     */
    private const val UPDATE_JSON_URL =
        "https://raw.githubusercontent.com/706412584/android-code-studio/refs/heads/dev/updater.json"

    /**
     * 更新清单地址（CN 版，Gitee）。
     *
     * <p>CN 版（`-PcnMirror=true`）的整包与补丁都托管在 Gitee——GitHub raw / Release
     * 在国内不可达，检查 GitHub 清单只会静默失败。CN 清单由
     * `tools/publish-cn-apk.ps1` 在上传分卷后生成并推送到 acs-mirror 仓库根目录
     * （raw 文件，无需发版即可更新）。
     *
     * <p>CN 清单的 `variants[*].apkUrl` 指向**分卷**（Gitee 单文件 100MB 上限，
     * 141MB 的 release APK 必须切卷），因此 [ArchVariant] 带 `parts` 字段；
     * 常规清单不含 `parts`，走单文件路径，行为不变。
     */
    private const val UPDATE_JSON_URL_CN =
        "https://gitee.com/wu-yongchengsvip_admin/acs-mirror/raw/master/updater-cn.json"

    private const val DOWNLOAD_NOTIFICATION_ID = 1001

    /** 偏好键：用户点「跳过此版本」后记下的 versionCode。 */
    private const val PREF_SKIPPED_VERSION = "updater_skipped_version_code"

    /**
     * 更新说明的最大显示高度（dp）。超出滚动。
     *
     * <p>发布说明几十行是常态，不限高会把弹窗顶满整屏（用户反馈）。
     * 280dp 约 12~15 行，够扫一眼要点，长内容滚动看。
     */
    private const val MAX_CHANGELOG_HEIGHT_DP = 280
  }

  /**
   * 一个架构变体的更新信息。
   *
   * <p>[parts] 非空时 [apkUrl] 无效，必须按 [parts] 顺序下载各分卷再拼接（CN 版，
   * Gitee 单文件 100MB 上限）；为空时走 [apkUrl] 单文件下载（常规版）。
   * [sha256] 是**拼接后整包**的哈希（CN 清单提供；常规清单没有，跳过校验）。
   */
  data class ArchVariant(
      val versionCode: Int,
      val versionName: String,
      val apkUrl: String,
      val parts: List<String> = emptyList(),
      val sha256: String? = null,
  )

  /**
   * 热更新补丁信息（`updater.json` 的可选 `patch` 块）。
   *
   * <p><b>[baseVersionCode] 是判定补丁能否应用的关键</b>：补丁是「上一版本 → 本版本」的
   * 增量，只能装在生成它时所用的那个版本上。SDK 的 `validatePatchFormat` 只校验包名、
   * **不校验 base 版本**，装了不匹配的补丁会静默注入错误 dex（补丁里的类替换掉当前版本
   * 的同名类，行为不可预期）。因此 [isPatchApplicable] 要求 `baseVersionCode == 当前安装版本`。
   *
   * <p>[versionCode] 是「装了这个补丁后达到的版本」，与整包版本同一坐标系。
   */
  data class PatchInfo(
      val versionCode: Int,
      val versionName: String,
      val baseVersionCode: Int,
      val patchUrl: String,
      val patchMd5: String?,
      val patchSha256: String?,
      val description: String?,
  )

  data class UpdateInfo(
      val baseVersionCode: Int,
      val baseVersionName: String,
      val variants: Map<String, ArchVariant>,
      val changelogUrl: String,
      val patch: PatchInfo?,
  )

  private var downloadJob: Job? = null
  private var progressDialog: androidx.appcompat.app.AlertDialog? = null
  private var progressIndicator: LinearProgressIndicator? = null
  private var progressText: TextView? = null

  /**
   * 启动时的静默检查。
   *
   * @param onNoUpdate 没有可用更新（或检查失败）时回调；用于「等更新弹窗处理完
   *     再打开上次项目」的启动时序。有更新时不回调——由弹窗按钮决定后续动作。
   *
   * <p><b>为什么需要这个回调</b>：MainActivity 的 onCreate 会立刻
   * {@code openLastProject()} 跳到 EditorActivity，而本方法是异步网络请求，
   * 弹窗要等它返回后才显示——那时 MainActivity 已被覆盖，弹窗挂在不可见的
   * Activity 上，用户永远看不到「有新版本」（实测：r13 → 清单 1035 时静默无提示）。
   */
  fun checkForUpdates(onNoUpdate: (() -> Unit)? = null) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val updateInfo = fetchUpdateInfo() ?: return@launch.also { notifyNoUpdate(onNoUpdate) }

        // 已应用补丁的版本达到清单目标时，两个分支都不再提示：
        // - 补丁分支：见 isPatchApplicable 的「尚未应用」检查；
        // - 整包分支：APK versionCode 不会因 DEX 注入而变（仍是旧值），
        //   isUpdateAvailable 会误判「有新版本」——实测用户打完 r07 补丁后
        //   重启，整包「可用更新」弹窗照常出现（版本号对比的固有局限）。
        // 用户实际跑的已是目标版本代码，这里直接返回。
        val appliedVersion = appliedPatchVersion()
        if (appliedVersion != null && appliedVersion >= updateInfo.baseVersionName) {
          Log.d(TAG, "已应用补丁 $appliedVersion ≥ 清单目标 ${updateInfo.baseVersionName}，无需提示")
          notifyNoUpdate(onNoUpdate)
          return@launch
        }

        // 补丁优先：热更新是小体积增量，能打补丁就不必让用户下 150MB 整包。
        // 但补丁只对**生成它时所用的那个版本**有效，所以 isPatchApplicable 要求
        // 补丁的 baseVersionCode == 当前安装版本。不满足就落回整包分支——
        // 否则落后多个版本的用户会被反复推一个 base 不匹配的补丁，永远装不上也升不了级。
        val patch = updateInfo.patch
        if (patch != null && isPatchApplicable(patch)) {
          // 拉 changelog 给用户看更新内容（与整包弹窗同源；失败时返回占位文案不阻塞）。
          val changelog = fetchChangelog(updateInfo.changelogUrl)
          withContext(Dispatchers.Main) { showPatchDialog(patch, changelog) }
          return@launch
        }

        if (isUpdateAvailable(updateInfo)) {
          val changelog = fetchChangelog(updateInfo.changelogUrl)
          withContext(Dispatchers.Main) { showUpdateDialog(updateInfo, changelog, onNoUpdate) }
        } else {
          notifyNoUpdate(onNoUpdate)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error checking for updates", e)
        notifyNoUpdate(onNoUpdate)
      }
    }
  }

  /** 只执行一次的 Runnable：弹窗按钮与 dismiss 监听会重复触发同一个回调。 */
  private class OnceRunnable(private val action: (() -> Unit)?) {
    private val fired = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run() {
      if (action != null && fired.compareAndSet(false, true)) {
        action.invoke()
      }
    }
  }

  /** 在主线程回调「无需等待更新弹窗」。 */
  private fun notifyNoUpdate(onNoUpdate: (() -> Unit)?) {
    if (onNoUpdate == null) {
      return
    }
    CoroutineScope(Dispatchers.Main).launch { onNoUpdate() }
  }

  /**
   * 手动检查更新（设置页入口）。
   *
   * <p>与启动时的 [checkForUpdates] 的区别：那个是静默的——没有更新就不打扰用户。
   * 手动点击**必须有反馈**，否则「点了没反应」会被当成功能坏了。因此在
   * 「已是最新 / 检查失败」两种情况下各弹一个短提示；有更新时复用 [checkForUpdates]
   * 的弹窗路径。提示用 Toast 而不是对话框，避免与更新弹窗叠在一起。
   */
  fun checkForUpdatesManually() {
    CoroutineScope(Dispatchers.IO).launch {
      val updateInfo =
          try {
            fetchUpdateInfo()
          } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            null
          }
      if (updateInfo == null) {
        showToastOnMain(R.string.updater_check_failed)
        return@launch
      }

      // 已应用补丁达到清单目标：与 checkForUpdates 的早退分支一致，视为已是最新。
      // 不处理这一支会「点了没反应」——checkForUpdates() 在该分支静默返回。
      val appliedVersion = appliedPatchVersion()
      if (appliedVersion != null && appliedVersion >= updateInfo.baseVersionName) {
        showToastOnMain(R.string.updater_no_updates_available)
        return@launch
      }

      val patch = updateInfo.patch
      if ((patch != null && isPatchApplicable(patch)) || isUpdateAvailable(updateInfo)) {
        // 有可用更新：走与启动检查相同的弹窗（内部会拉 changelog 并显示）。
        checkForUpdates()
        return@launch
      }

      showToastOnMain(R.string.updater_no_updates_available)
    }
  }

  private suspend fun showToastOnMain(resId: Int) {
    withContext(Dispatchers.Main) {
      android.widget.Toast.makeText(context, context.getString(resId), android.widget.Toast.LENGTH_SHORT)
          .show()
    }
  }

  /**
   * 已应用补丁的版本名（如 `1.0.0+gh.r07`）；未应用补丁时返回 null。
   *
   * <p>用版本名而不是 versionCode：SDK 的 `getPatchedVersionCode()` 只在内存维护，
   * 重启后从 prefs 恢复拿不到值（实测）；`appliedPatchInfo.patchVersion` 是持久化的。
   */
  private fun appliedPatchVersion(): String? {
    val helper = com.orange.update.HotUpdateHelper.getInstance(context)
    return if (helper.hasAppliedPatch()) helper.appliedPatchInfo?.patchVersion else null
  }

  /**
   * 补丁是否适用于当前安装。
   *
   * <p>三个条件缺一不可：
   * 1. **base 版本匹配**：`patch.baseVersionCode == 当前 versionCode`。补丁是「上一版本 →
   *    本版本」的增量，base 不匹配时装上去会静默注入错误 dex（SDK 不校验这项）。
   * 2. **补丁版本更高**：`patch.versionCode > 当前 versionCode`，否则说明已经打过这个补丁。
   * 3. **该补丁尚未应用**：热更新是 DEX 注入，**不改 APK 的 versionCode**——已应用补丁的
   *    用户 APK 仍是旧 versionCode，条件 1/2 依然成立，不做此检查每次启动都会重复弹窗
   *    （实测：应用 r07 补丁并重启后，重启即再次弹出「发现热更新补丁」）。
   *
   * <p>清单未声明 `baseVersionCode`（旧清单）时判为不适用，落回整包更新——宁可多下整包，
   * 也不要赌一个 base 未知的补丁。
   */
  private fun isPatchApplicable(patch: PatchInfo): Boolean {
    if (patch.versionCode <= 0 || patch.baseVersionCode <= 0) return false
    val current =
        try {
          context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
          return false
        }
    if (patch.versionCode == skippedVersionCode()) return false

    // 已应用过补丁：按**版本名**比对（SDK 的 PatchInfo.patchVersion 就是补丁声明的
    // patchVersion，落盘在 patch_storage_prefs.xml）。
    //
    // 不能用 getPatchedVersionCode()：SDK 只在内存中维护它，重启后从 prefs 恢复时
    // 拿不到值（实测重启后返回空 → 判定失效 → 重复弹窗）；而 patchVersion 是持久化的。
    val applied = com.orange.update.HotUpdateHelper.getInstance(context)
    if (applied.hasAppliedPatch()) {
      val appliedVersion = applied.appliedPatchInfo?.patchVersion
      if (!appliedVersion.isNullOrBlank() && appliedVersion == patch.versionName) {
        Log.d(TAG, "补丁 ${patch.versionName} 已应用（appliedPatchInfo），跳过提示")
        return false
      }
    }

    return patch.baseVersionCode == current && patch.versionCode > current
  }

  /**
   * 构建「信息头 + 限高可滚动更新内容 + 可选脚注」的对话框视图。
   *
   * <p>更新内容（changelog）长度不可控——一个版本的发布说明几十行是常态，
   * 直接 setMessage 会把弹窗顶得非常高、在窄屏上盖满整屏（用户反馈）。
   * 这里把正文限高 [MAX_CHANGELOG_HEIGHT_DP]，超出部分滚动查看。
   */
  private fun buildInfoDialogView(header: String, changelog: String, footer: String?): View {
    val density = context.resources.displayMetrics.density
    val hPad = (24 * density).toInt()
    val layout =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(hPad, (16 * density).toInt(), hPad, 0)
        }

    layout.addView(
        TextView(context).apply {
          text = header
          textSize = 14f
        }
    )

    if (changelog.isNotBlank()) {
      val changelogView =
          TextView(context).apply {
            text = changelog
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, 0)
          }
      // 用匿名子类在 onMeasure 里限高：ScrollView 的 maxHeight 无现成 API，
      // 布局参数只能给固定值（会撑不满短内容），AT_MOST 才是「内容短则贴合、
      // 内容长则封顶」的正确语义。
      val scroll =
          object : android.widget.ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
              val maxHeight = (MAX_CHANGELOG_HEIGHT_DP * resources.displayMetrics.density).toInt()
              super.onMeasure(
                  widthMeasureSpec,
                  View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
              )
            }
          }
      scroll.addView(changelogView)
      layout.addView(scroll)
    }

    if (!footer.isNullOrBlank()) {
      layout.addView(
          TextView(context).apply {
            text = footer
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, 0)
          }
      )
    }
    return layout
  }

  /**
   * 提示有热更新补丁可用。
   *
   * <p>更新内容来自清单的 `changelog`（与整包弹窗同一来源）——**不在清单里塞
   * description**：changelog 是每个版本发布时都有的 markdown 文件，而 description
   * 要靠发布脚本手动填，实测两个字段不同步时用户只看到一句「新版本可用」，
   * 完全不知道更新了什么（用户反馈）。
   *
   * <p>changelog 拉取失败（离线/文件缺失）不阻塞弹窗——补丁本身仍可下载应用，
   * 只是没有说明文字。
   */
  private fun showPatchDialog(patch: PatchInfo, changelog: String) {
    val header = context.getString(R.string.updater_new_version_available, patch.versionName)
    // changelog 拉不到时退回清单的 description（可为空），都没有则只有头部与脚注。
    val body = changelog.ifBlank { patch.description ?: "" }
    val footer = "热更新：下载后无需重装 APK，重启应用即可生效。"

    MaterialAlertDialogBuilder(context)
        .setTitle("发现热更新补丁")
        .setView(buildInfoDialogView(header, body, footer))
        .setPositiveButton("下载并应用") { _, _ -> applyPatch(patch) }
        .setNegativeButton(context.getString(R.string.updater_skip_version)) { _, _ ->
          rememberSkippedVersion(patch.versionCode)
        }
        .setCancelable(true)
        .show()
  }

  /** 下载补丁并交给 [HotUpdateManager] 应用。 */
  private fun applyPatch(patch: PatchInfo) {
    showProgressDialog()
    CoroutineScope(Dispatchers.IO).launch {
      com.tom.rv2ide.hotupdate.HotUpdateManager.downloadAndApply(
          context,
          patch.patchUrl,
          patch.patchSha256,
          patch.baseVersionCode,
          // 阶段进度回调（下载字节进度 / 校验 / 应用百分比）→ 更新进度对话框。
          // 没有它时进度条从 0% 直接跳到成功——用户不知道在下载还是卡住了（用户反馈）。
          onStage = { fraction, message ->
            CoroutineScope(Dispatchers.Main).launch {
              updateProgress((fraction * 100).toInt().coerceIn(0, 100), message)
            }
          },
      ) { ok, detail ->
        CoroutineScope(Dispatchers.Main).launch {
          hideProgressDialog()
          if (ok) {
            MaterialAlertDialogBuilder(context)
                .setTitle("热更新已应用")
                .setMessage("补丁版本：$detail\n\n重启应用后生效。")
                .setPositiveButton("立即重启") { _, _ -> restartApp() }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
          } else {
            MaterialAlertDialogBuilder(context)
                .setTitle("热更新失败")
                .setMessage(detail)
                .setPositiveButton("确定", null)
                .show()
          }
        }
      }
    }
  }

  /**
   * 重启应用。
   *
   * <p>补丁的 DEX 注入发生在 `Application.attachBaseContext`，只有新进程才会加载它，
   * 因此必须真正重启进程而不是回到某个 Activity。
   *
   * <p>先启动再杀进程：用 `killProcess` 而不是 `System.exit(0)`——后者会走 Runtime 的
   * 关闭钩子，在 Android 上可能拖住甚至挂死主线程；`killProcess` 直接终止进程，
   * 与用户从最近任务划掉应用等价。
   */
  private fun restartApp() {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
  }

  private suspend fun fetchUpdateInfo(): UpdateInfo? {
    return withContext(Dispatchers.IO) {
      // CN 版读 Gitee 清单（整包与补丁均托管 Gitee）；常规版读 GitHub 清单。
      val manifestUrl = if (BuildConfig.CN_MIRROR) UPDATE_JSON_URL_CN else UPDATE_JSON_URL
      try {
        val url = URL(manifestUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
          val response = connection.inputStream.bufferedReader().use { it.readText() }
          parseUpdateInfo(response)
        } else {
          Log.e(TAG, context.getString(R.string.updater_http_error, responseCode))
          null
        }
      } catch (e: Exception) {
        Log.e(TAG, context.getString(R.string.updater_fetch_error), e)
        null
      }
    }
  }

  /**
   * 展开清单 URL 里的占位符。
   *
   * <p>`updater.json` 里写的是 `{baseUrl}/android-code-studio-…-{versionName}.apk`，
   * 而 [ArchVariant.apkUrl] 会被直接交给 `URL()` 去下载。**不展开就会拿这个字面量
   * 当网址请求**，更新功能整个失效——而且失败发生在下载阶段，用户只看到「下载失败」，
   * 不会知道是清单格式的问题。
   *
   * <p>展开放在解析里而不是使用处：apkUrl 有两个消费点（下载、在浏览器打开），
   * 在使用处各展开一次必然漏掉一个。
   *
   * <p>只做纯字符串替换，不做 URL 校验——校验留给 `URL()` 抛异常，那样错误信息里
   * 会带上实际的值，比在这里抛一个笼统的「格式错误」更好排查。
   */
  private fun expandUrl(template: String, baseUrl: String, versionName: String): String =
      template.replace("{baseUrl}", baseUrl).replace("{versionName}", versionName)

  private fun parseUpdateInfo(jsonString: String): UpdateInfo? {
    return try {
      val jsonObject = JSONObject(jsonString)
      val variantsJson = jsonObject.getJSONObject("variants")
      val variants = mutableMapOf<String, ArchVariant>()

      // baseUrl 自身也含占位符（`…/download/v{versionName}`），先展开它，
      // 再用展开后的结果去展开各变体的 apkUrl。
      val rawBaseUrl = jsonObject.optString("baseUrl", "")
      val baseVersionName = jsonObject.getString("baseVersionName")
      val baseUrl = if (rawBaseUrl.isEmpty()) "" else expandUrl(rawBaseUrl, "", baseVersionName)

      for (key in variantsJson.keys()) {
        val variantJson = variantsJson.getJSONObject(key)
        val variantVersionName = variantJson.getString("versionName")

        // CN 清单的分卷数组（可选）：非空时 apkUrl 无效，按 parts 顺序下载拼接。
        val partsJson = variantJson.optJSONArray("parts")
        val parts =
            if (partsJson != null && partsJson.length() > 0) {
              (0 until partsJson.length()).map { i ->
                expandUrl(partsJson.getString(i), baseUrl, variantVersionName)
              }
            } else {
              emptyList()
            }

        variants[key] =
            ArchVariant(
                versionCode = variantJson.getInt("versionCode"),
                versionName = variantVersionName,
                // apkUrl 用 optString：分卷变体（CN）可以不带 apkUrl，缺失时保持空串
                // （下载走 parts 分支，不读这个字段）；用 getString 会因缺字段把整个
                // 清单解析搞挂。
                apkUrl =
                    expandUrl(
                        variantJson.optString("apkUrl", ""),
                        baseUrl,
                        variantVersionName,
                    ),
                parts = parts,
                // CN 清单提供整包 sha256（拼接后校验）；常规清单没有，保持 null。
                sha256 = variantJson.optString("sha256", null)?.takeIf { it.isNotBlank() },
            )
      }

      UpdateInfo(
          baseVersionCode = jsonObject.getInt("baseVersionCode"),
          baseVersionName = baseVersionName,
          variants = variants,
          changelogUrl = jsonObject.getString("changelog"),
          patch = parsePatch(jsonObject, baseUrl, baseVersionName),
      )
    } catch (e: Exception) {
      Log.e(TAG, context.getString(R.string.updater_parse_error), e)
      null
    }
  }

  /**
   * 解析可选的 `patch` 块。
   *
   * <p>缺字段或块不存在时返回 null——补丁是**可选**能力，旧清单（没有 patch 块）
   * 必须继续可用，否则一旦清单漏写补丁字段，整个更新检查都会失效。
   */
  private fun parsePatch(
      jsonObject: JSONObject,
      baseUrl: String,
      baseVersionName: String,
  ): PatchInfo? {
    val p = jsonObject.optJSONObject("patch") ?: return null
    val patchVersionName = p.optString("versionName", baseVersionName)
    val rawUrl = p.optString("patchUrl", "")
    if (rawUrl.isEmpty()) return null

    return PatchInfo(
        versionCode = p.optInt("versionCode", -1),
        versionName = patchVersionName,
        baseVersionCode = p.optInt("baseVersionCode", -1),
        patchUrl = expandUrl(rawUrl, baseUrl, patchVersionName),
        patchMd5 = p.optString("md5", null),
        patchSha256 = p.optString("sha256", null),
        description = p.optString("description", null),
    )
  }

  private suspend fun fetchChangelog(changelogUrl: String): String {
    return withContext(Dispatchers.IO) {
      try {
        val url = URL(changelogUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
          val markdown = connection.inputStream.bufferedReader().use { it.readText() }
          parseMarkdownToPlainText(markdown)
        } else {
          context.getString(R.string.updater_changelog_failed)
        }
      } catch (e: Exception) {
        Log.e(TAG, context.getString(R.string.updater_changelog_error), e)
        context.getString(R.string.updater_changelog_failed)
      }
    }
  }

  private fun parseMarkdownToPlainText(markdown: String): String {
    var text = markdown

    // Remove code blocks
    text = text.replace(Regex("```[\\s\\S]*?```"), "")
    text = text.replace(Regex("`[^`]*`"), "")

    // Convert headers
    text = text.replace(Regex("^#{1,6}\\s*(.*)$", RegexOption.MULTILINE), "$1")

    // Convert bold and italic
    text = text.replace(Regex("\\*\\*\\*([^*]*)\\*\\*\\*"), "$1")
    text = text.replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
    text = text.replace(Regex("\\*([^*]*)\\*"), "$1")
    text = text.replace(Regex("___([^_]*)___"), "$1")
    text = text.replace(Regex("__([^_]*)__"), "$1")
    text = text.replace(Regex("_([^_]*)_"), "$1")

    // Convert links
    text = text.replace(Regex("\\[([^\\]]*)\\]\\([^\\)]*\\)"), "$1")

    // Convert lists
    text = text.replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "• ")
    text = text.replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "• ")

    // Clean up extra whitespace
    text = text.replace(Regex("\\n\\s*\\n"), "\n\n")
    text = text.trim()

    return text
  }

  /** 用户已选择跳过的版本号；未跳过时返回 -1。 */
  private fun skippedVersionCode(): Int =
      context
          .getSharedPreferences("com.tom.rv2ide_preferences", Context.MODE_PRIVATE)
          .getInt(PREF_SKIPPED_VERSION, -1)

  /** 记下「跳过此版本」。 */
  private fun rememberSkippedVersion(versionCode: Int) {
    context
        .getSharedPreferences("com.tom.rv2ide_preferences", Context.MODE_PRIVATE)
        .edit()
        .putInt(PREF_SKIPPED_VERSION, versionCode)
        .apply()
  }

  private fun isUpdateAvailable(updateInfo: UpdateInfo): Boolean {
    return try {
      val currentVersionCode =
          context.packageManager.getPackageInfo(context.packageName, 0).versionCode

      val currentArch = getDeviceArchitecture()
      val availableVariant =
          updateInfo.variants[currentArch]
              ?: updateInfo.variants["armeabi-v7a"] // Fallback for arm64 devices

      if (availableVariant != null) {
        Log.d(
            TAG,
            context.getString(
                R.string.updater_version_check_log,
                currentVersionCode,
                availableVariant.versionCode,
                currentArch,
            ),
        )
        // 用户点过「跳过此版本」就不再提示同一个版本——否则每次启动都弹同一个窗，
        // 用户只能反复点「稍后」，弹窗就变成了噪声。
        if (availableVariant.versionCode == skippedVersionCode()) {
          Log.d(TAG, "Skipping version ${availableVariant.versionCode} (user skipped it)")
          return false
        }
        availableVariant.versionCode > currentVersionCode
      } else {
        Log.w(TAG, context.getString(R.string.updater_no_compatible_variant, currentArch))
        false
      }
    } catch (e: PackageManager.NameNotFoundException) {
      Log.e(TAG, context.getString(R.string.updater_package_not_found), e)
      false
    }
  }

  private fun getDeviceArchitecture(): String {
    val supportedAbis =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          Build.SUPPORTED_ABIS
        } else {
          @Suppress("DEPRECATION")
          arrayOf(Build.CPU_ABI, Build.CPU_ABI2).filterNotNull().toTypedArray()
        }

    Log.d(TAG, context.getString(R.string.updater_supported_abis, supportedAbis.contentToString()))

    // Prioritize 64-bit architecture
    for (abi in supportedAbis) {
      when (abi) {
        "arm64-v8a" -> return "arm64-v8a"
        "x86_64" -> return "x86_64"
      }
    }

    // Fallback to 32-bit architectures
    for (abi in supportedAbis) {
      when (abi) {
        "armeabi-v7a",
        "armeabi" -> return "armeabi-v7a"
        "x86" -> return "x86"
      }
    }

    // Default fallback
    Log.w(TAG, context.getString(R.string.updater_unknown_arch_fallback))
    return "armeabi-v7a"
  }

  private fun getVariantForCurrentArchitecture(updateInfo: UpdateInfo): ArchVariant? {
    val currentArch = getDeviceArchitecture()
    Log.d(TAG, context.getString(R.string.updater_device_arch, currentArch))
    Log.d(
        TAG,
        context.getString(R.string.updater_available_variants, updateInfo.variants.keys.toString()),
    )

    // Try to get the exact architecture match
    var variant = updateInfo.variants[currentArch]

    // If no exact match, try fallbacks
    if (variant == null) {
      Log.d(TAG, context.getString(R.string.updater_trying_fallbacks, currentArch))
      when (currentArch) {
        "arm64-v8a" -> {
          // arm64 can run armeabi-v7a
          variant = updateInfo.variants["armeabi-v7a"]
          if (variant != null) {
            Log.d(TAG, context.getString(R.string.updater_using_arm32_fallback))
          }
        }
        "x86_64" -> {
          // x86_64 can run x86
          variant = updateInfo.variants["x86"]
          if (variant != null) {
            Log.d(TAG, context.getString(R.string.updater_using_x86_fallback))
          }
        }
      }
    }

    // If still no match, try to get any available variant
    if (variant == null && updateInfo.variants.isNotEmpty()) {
      Log.w(TAG, context.getString(R.string.updater_using_first_variant))
      variant = updateInfo.variants.values.first()
    }

    return variant
  }

  private fun showUpdateDialog(
      updateInfo: UpdateInfo,
      changelog: String,
      onDismissed: (() -> Unit)? = null,
  ) {
    val currentArch = getDeviceArchitecture()
    val availableVariant = getVariantForCurrentArchitecture(updateInfo)

    // 按钮回调与 setOnDismissListener 都会触发：用一次性闸门保证
    // 「继续启动流程」只执行一次（否则会打开两次上次项目）。
    val notifyOnce = OnceRunnable(onDismissed)

    if (availableVariant == null) {
      notifyOnce.run()
      return
    }

    val availableArchs = updateInfo.variants.keys.joinToString(", ")

    val header =
        buildString {
          append(
              context.getString(
                  R.string.updater_new_version_available,
                  availableVariant.versionName,
              )
          )
          append("\n")
          append(context.getString(R.string.updater_current_architecture, currentArch))
          append("\n")
          append(
              context.getString(R.string.updater_target_version_code, availableVariant.versionCode)
          )
          append("\n")
          append(context.getString(R.string.updater_available_variants, availableArchs))
        }

    MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.updater_update_available))
        // changelog 限高可滚动（同补丁弹窗）：发布说明几十行是常态，
        // setMessage 会把弹窗顶得非常高。
        .setView(buildInfoDialogView(header, changelog, null))
        .setPositiveButton(context.getString(R.string.updater_download)) { _, _ ->
          // 下载不需要等：用户已明确要看进度，直接进下载弹窗。
          notifyOnce.run()
          downloadAndInstall(availableVariant)
        }
        .setNeutralButton(context.getString(R.string.updater_view_in_browser)) { _, _ ->
          // 分卷没有单一可打开的 URL，用第一分卷兜底（CN 用户也可以直接看浏览器下载）。
          notifyOnce.run()
          openInBrowser(availableVariant.parts.firstOrNull() ?: availableVariant.apkUrl)
        }
        // 「稍后」与「跳过此版本」的区别很重要：稍后下次启动还会弹，
        // 跳过则记下版本号不再提示，直到出现更新的版本。只给「稍后」时，
        // 用户面对同一个不想装的版本只能每次启动都点一遍，弹窗就成了噪声。
        .setNegativeButton(context.getString(R.string.updater_skip_version)) { _, _ ->
          rememberSkippedVersion(availableVariant.versionCode)
          notifyOnce.run()
        }
        .setOnDismissListener { notifyOnce.run() }
        .setCancelable(true)
        .show()
  }

  private fun downloadAndInstall(variant: ArchVariant) {
    if (!hasInstallPermission()) {
      requestInstallPermission()
      return
    }

    showProgressDialog()

    downloadJob =
        CoroutineScope(Dispatchers.IO).launch {
          try {
            val apkFile =
                if (variant.parts.isNotEmpty()) downloadApkFromParts(variant)
                else downloadApk(variant.apkUrl)
            withContext(Dispatchers.Main) {
              hideProgressDialog()
              if (apkFile != null) {
                installApk(apkFile)
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.updater_download_error), e)
            withContext(Dispatchers.Main) {
              hideProgressDialog()
            }
          }
        }
  }

  /**
   * 分卷下载（CN 版）：按 [ArchVariant.parts] 顺序下载各分卷到临时文件，再按序拼接到
   * `update.apk`，最后校验整包 sha256（清单提供时）。
   *
   * <p>与 `AcsProvider.downloadParts` 同一机制（Gitee 单文件 100MB 上限，141MB 的
   * release APK 必须切卷）。进度按已下载字节累计显示。
   *
   * <p>任一分卷失败或哈希不匹配：删除全部中间文件并返回 null——半成品 APK 若被当成
   * 完整包安装，用户会装上一个损坏的应用。
   */
  private suspend fun downloadApkFromParts(variant: ArchVariant): File? {
    return withContext(Dispatchers.IO) {
      val partsDir = File(context.cacheDir, "update-parts")
      val apkFile = File(context.getExternalFilesDir(null), "update.apk")
      try {
        partsDir.deleteRecursively()
        partsDir.mkdirs()

        val totalParts = variant.parts.size
        val partFiles = mutableListOf<File>()
        // 跨分卷的累计字节与总长：进度条必须单调递增，不能每卷归零。
        // 总长先预读（HEAD），否则边下边算分母会让进度从 100% 跳回 67%。
        val progressState = PartProgress(probePartSizes(variant.parts))
        variant.parts.forEachIndexed { index, partUrl ->
          val partFile = File(partsDir, "part.$index")
          downloadToFile(partUrl, partFile, index, totalParts, progressState)
          partFiles += partFile
        }

        // 顺序拼接：Gitee 附件按文件名路由已实测可用（各分卷内容不同），
        // 因此这里按 manifest 声明的顺序写入即可。
        FileOutputStream(apkFile).use { out ->
          for (part in partFiles) {
            part.inputStream().use { it.copyTo(out, 8192) }
          }
        }

        // 整包 sha256 校验（清单提供时）。Gitee 分卷下载链路比单文件多一层拼接，
        // 校验能挡住下载损坏 / 分卷错序。
        val expected = variant.sha256
        if (!expected.isNullOrBlank()) {
          val actual = sha256(apkFile)
          if (!actual.equals(expected, ignoreCase = true)) {
            Log.e(TAG, "CN APK 校验失败：期望 $expected，实际 $actual")
            apkFile.delete()
            return@withContext null
          }
        }

        apkFile
      } catch (e: Exception) {
        Log.e(TAG, context.getString(R.string.updater_download_apk_error), e)
        apkFile.delete()
        null
      } finally {
        partsDir.deleteRecursively()
      }
    }
  }

  /**
   * 跨分卷的进度累加器。
   *
   * <p>分卷是 Gitee 100MB 单文件限制的产物，用户视角仍是「下载一个 APK」，
   * 所以进度必须跨卷连续。每卷各自从 0 开始会表现为进度条反复重置。
   *
   * <p>总长在下载前由 [probePartSizes] 预先取得——不能边下边算分母：
   * 那会让分母随分卷推进而变化，第 1 卷下完是 100%，第 2 卷开始又变成 67%。
   */
  private class PartProgress(val totalBytes: Long) {
    var completedBytes = 0L
    var lastPercent = -1

    /** 已下载字节 / 总长（总长未知时返回 null）。 */
    fun fraction(doneInPart: Long): Int? {
      if (totalBytes <= 0) {
        return null
      }
      return (((completedBytes + doneInPart) * 100) / totalBytes).toInt().coerceIn(0, 100)
    }
  }

  /**
   * 预读各分卷的 Content-Length（只读响应头，不下载正文）。
   *
   * <p>拿不到长度（服务端不给 Content-Length）时返回 0，调用方退化为
   * 「不确定进度」——进度条不会倒退，只是不精确。
   */
  private fun probePartSizes(partUrls: List<String>): Long {
    var sum = 0L
    for (partUrl in partUrls) {
      val connection = URL(partUrl).openConnection() as HttpURLConnection
      connection.requestMethod = "HEAD"
      connection.connectTimeout = 30000
      connection.readTimeout = 30000
      try {
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
          val length = connection.contentLengthLong
          if (length <= 0) {
            // 任一分卷长度未知就无法给出准确总长，整体退化为不确定进度。
            return 0L
          }
          sum += length
        }
      } catch (e: Exception) {
        Log.w(TAG, "预读分卷大小失败：$partUrl", e)
      } finally {
        connection.disconnect()
      }
    }
    return sum
  }

  /** 下载单个分卷到 [dest]；[index]/[total] 用于进度文案（`分卷 i/N`）。 */
  private suspend fun downloadToFile(
      urlStr: String,
      dest: File,
      index: Int,
      total: Int,
      progress: PartProgress,
  ) {
    val url = URL(urlStr)
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 30000
    connection.readTimeout = 30000
    try {
      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        throw IllegalStateException("HTTP $responseCode（分卷 ${index + 1}/$total）")
      }
      val label = "下载分卷 ${index + 1}/$total"
      // 保留已显示的百分比：同一卷开始时不归零。
      withContext(Dispatchers.Main) {
        updateProgress(progress.lastPercent.coerceAtLeast(0), "$label…")
      }

      val buffer = ByteArray(8192)
      var doneInPart = 0L
      connection.inputStream.use { input ->
        FileOutputStream(dest).use { output ->
          while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
            doneInPart += n
            val percent = progress.fraction(doneInPart) ?: continue
            if (percent != progress.lastPercent) {
              progress.lastPercent = percent
              val doneMB = ((progress.completedBytes + doneInPart) / (1024 * 1024)).toInt()
              val totalMB = (progress.totalBytes / (1024 * 1024)).toInt()
              withContext(Dispatchers.Main) {
                updateProgress(
                    percent,
                    context.getString(R.string.updater_progress_format, doneMB, totalMB) +
                        "（$label）",
                )
              }
            }
          }
        }
      }
      progress.completedBytes += doneInPart
    } finally {
      connection.disconnect()
    }
  }

  /** 计算文件的 SHA-256（小写十六进制）。 */
  private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(8192)
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private suspend fun downloadApk(apkUrl: String): File? {
    return withContext(Dispatchers.IO) {
      try {
        val url = URL(apkUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 30000

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
          Log.e(TAG, context.getString(R.string.updater_http_error, responseCode))
          return@withContext null
        }

        val contentLength = connection.contentLengthLong
        val inputStream = connection.inputStream

        // Create temp file
        val apkFile = File(context.getExternalFilesDir(null), "update.apk")
        val outputStream = FileOutputStream(apkFile)

        val buffer = ByteArray(8192)
        var totalBytes = 0L
        var bytesRead: Int
        // 只在百分比真正变化时才切主线程：141MB 按 8KB 分块约 1.8 万次，
        // 每块都 post 一次主线程任务会让进度条本身卡顿。
        var lastPercent = -1

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          totalBytes += bytesRead

          if (contentLength > 0) {
            // 必须用 Long 运算：totalBytes * 100 在 141MB 的包上约 148 亿，
            // 远超 Int 上限（21.4 亿）。用 Int 会回绕成负数，进度条被夹回 0，
            // 整个下载过程中反复「归零」——用户看到的就是进度条无限重置。
            val progress = ((totalBytes * 100) / contentLength).toInt().coerceIn(0, 100)
            if (progress != lastPercent) {
              lastPercent = progress
              val progressMB = (totalBytes / (1024 * 1024)).toInt()
              val totalMB = (contentLength / (1024 * 1024)).toInt()

              withContext(Dispatchers.Main) {
                updateProgress(
                    progress,
                    context.getString(R.string.updater_progress_format, progressMB, totalMB),
                )
              }
            }
          }
        }

        inputStream.close()
        outputStream.close()

        apkFile
      } catch (e: Exception) {
        Log.e(TAG, context.getString(R.string.updater_download_apk_error), e)
        null
      }
    }
  }

  private fun installApk(apkFile: File) {
    try {
      val intent = Intent(Intent.ACTION_VIEW)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val apkUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } else {
        intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
      }

      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, context.getString(R.string.updater_install_error), e)
    }
  }

  private fun hasInstallPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  private fun requestInstallPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
      intent.data = Uri.parse("package:${context.packageName}")

      if (context is Activity) {
        context.startActivityForResult(intent, 1234)
      } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
      }

    }
  }

  private fun showProgressDialog() {
    val dialogView =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(64, 32, 64, 32)
        }

    val titleText =
        TextView(context).apply {
          text = context.getString(R.string.updater_downloading_update)
          textSize = 18f
          setPadding(0, 0, 0, 24)
        }

    progressIndicator =
        LinearProgressIndicator(context).apply {
          isIndeterminate = false
          max = 100
          progress = 0
        }

    progressText =
        TextView(context).apply {
          text = context.getString(R.string.updater_preparing_download)
          setPadding(0, 16, 0, 0)
        }

    dialogView.addView(titleText)
    dialogView.addView(progressIndicator)
    dialogView.addView(progressText)

    progressDialog =
        MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setNegativeButton(context.getString(R.string.updater_cancel)) { _, _ ->
              cancelDownload()
            }
            .setCancelable(false)
            .create()

    progressDialog?.show()
  }

  private fun updateProgress(progress: Int, text: String) {
    progressIndicator?.progress = progress
    progressText?.text = text
  }

  private fun hideProgressDialog() {
    progressDialog?.dismiss()
    progressDialog = null
    progressIndicator = null
    progressText = null
  }

  private fun openInBrowser(url: String) {
    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, context.getString(R.string.updater_browser_error), e)
    }
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    hideProgressDialog()
  }
}
