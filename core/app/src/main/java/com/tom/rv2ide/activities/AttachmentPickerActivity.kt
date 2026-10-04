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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 附件选择「蹦床」Activity。
 *
 * <p><b>为什么需要它</b>：应用外悬浮形态下，助手的宿主是 [com.tom.rv2ide.services
 * .AssistantOverlayService]，而 Service 不是 [androidx.fragment.app.FragmentActivity]，
 * 无法注册 `ActivityResultLauncher`——[com.tom.rv2ide.artificial.agent.AssistantInputFeatures]
 * 里 `context as? FragmentActivity ?: return` 会静默失效（按钮点了没反应，且不报错）。
 *
 * <p><b>为什么不是「让 Service 自己 startActivityForResult」</b>：Android 10 (SDK29) 起
 * 后台进程启动 Activity 受限制，Service 直接拉起系统选择器可能被拦。持有
 * `SYSTEM_ALERT_WINDOW` 权限的应用属于**豁免**之列（见
 * https://developer.android.com/guide/components/activities/background-starts 的豁免清单），
 * 但豁免的前提是从一个「有界面/可见」的组件发起。本 Activity 由用户点击悬浮窗内的
 * 附件按钮触发，属于用户主动行为，最稳妥。
 *
 * <p><b>为什么是「透明蹦床」而不是直接复用系统选择器</b>：系统选择器（
 * `ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT`）的结果只能回给发起它的 Activity。
 * 蹦床作为一个真正的 Activity 发起选择、拿到结果，再经 [setResult] 原样回投给发起方——
 * 这样发起方（可能是 Service 里的悬浮窗）不必自己扮演 Activity，也不必依赖已废弃的
 * `onActivityResult` 回调路径。
 *
 * <p><b>安全</b>：本 Activity **必须** `android:exported="false"`（见 Manifest）。
 * 若被导出，任意应用都能拉起它、甚至传入任意 pickIntent 借本应用的
 * `SYSTEM_ALERT_WINDOW` 豁免拉起界面。manifest 与代码两处都要守住这条。
 *
 * <p><b>为什么继承 [ComponentActivity] 而不是 [androidx.appcompat.app.AppCompatActivity]</b>：
 * 前者不要求 AppCompat 主题，因而可以直接套用框架自带的
 * `@android:style/Theme.Translucent.NoTitleBar`，不必为蹦床新增一个主题资源。
 * `registerForActivityResult` 由 `ComponentActivity` 提供，能力足够。
 */
class AttachmentPickerActivity : ComponentActivity() {

  /**
   * 发起真正的系统选择器。
   *
   * <p>注册必须在 `onCreate` 完成前完成（属性初始化即满足）。回调里把结果
   * **原样**回传给发起方：`resultCode` 与 `data`（含 `clipData`）都要带回去，
   * 因为多选时系统只把结果放进 `clipData`、`data` 为 null——这里不做任何裁剪，
   * 让发起方（`AssistantOverlayService`）去决定怎么解析，避免两处各写一份解析逻辑。
   */
  private val picker =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        forward(result.resultCode, result.data)
        finish()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 屏幕旋转等导致的重建：不重复拉起选择器。进行中的请求由 ActivityResultRegistry
    // 自身经 saved state 恢复并回投，这里直接返回即可。
    if (savedInstanceState != null) {
      return
    }

    val pickIntent = pickIntent()
    if (pickIntent == null) {
      // 没有待发起的选择意图（被误拉起、或被外部伪造）。不抛异常，直接取消收场。
      forward(Activity.RESULT_CANCELED, null)
      finish()
      return
    }

    try {
      picker.launch(pickIntent)
    } catch (e: android.content.ActivityNotFoundException) {
      // 设备上没有能处理该选择意图的 Activity。回一个「取消」，由发起方提示用户，
      // 而不是让蹦床崩溃（崩溃信息对用户毫无意义）。
      forward(Activity.RESULT_CANCELED, null)
      finish()
    }
  }

  /**
   * 把结果经**显式 Intent**（action + 明确组件）回传给 [AssistantOverlayService]。
   *
   * <p>为什么用显式 Intent 而不是 `setResult`/`onActivityResult`：发起方是 Service，
   * 不是 Activity，没有 `onActivityResult` 可用；而全局广播会把这个结果暴露给任何
   * 注册了同一 action 的应用。显式指向本应用的 Service 组件最安全。
   *
   * <p>为什么用 `startService` 而不是 `startForegroundService`：目标 Service 已在运行
   * （前台服务），这里只是投递一次数据，不需要承担「5 秒内必须 startForeground」的义务。
   */
  private fun forward(resultCode: Int, data: Intent?) {
    val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
    val target =
        Intent(this, com.tom.rv2ide.services.AssistantOverlayService::class.java)
            .setAction(com.tom.rv2ide.services.AssistantOverlayService.ACTION_ATTACHMENT_RESULT)
            .putExtra(
                com.tom.rv2ide.services.AssistantOverlayService.EXTRA_ATTACHMENT_REQUEST_CODE,
                requestCode,
            )
            .putExtra(
                com.tom.rv2ide.services.AssistantOverlayService.EXTRA_ATTACHMENT_RESULT_CODE,
                resultCode,
            )
            .putExtra(
                com.tom.rv2ide.services.AssistantOverlayService.EXTRA_ATTACHMENT_DATA,
                data,
            )
    startService(target)
  }

  private fun pickIntent(): Intent? =
      @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PICK_INTENT)

  companion object {

    /**
     * 待发起的系统选择意图（`ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT`）。
     *
     * <p>用带包名前缀的字符串而不是短名：Activity 间传参若用裸键名，容易与系统或
     * 其它库塞进同一 Intent 的额外数据撞名。
     */
    const val EXTRA_PICK_INTENT = "com.tom.rv2ide.activities.extra.PICK_INTENT"

    /** 发起方登记的回调编号；结果按它路由回正确的回调。 */
    const val EXTRA_REQUEST_CODE = "com.tom.rv2ide.activities.extra.REQUEST_CODE"

    /**
     * 构造拉起蹦床的 Intent。
     *
     * <p>**不带** `FLAG_ACTIVITY_NEW_TASK`：蹦床应当叠在调用它的宿主窗口之上、
     * 且共享调用方的任务栈，这样选择器返回时能正确回投结果。是否加该 flag 由调用方
     * 按自身上下文决定（Service 上下文必须加，Activity 上下文不能加）。
     *
     * @param context 用于解析目标组件；不要求是 Activity。
     * @param pickIntent 真正的系统选择意图，由蹦床代为发起。
     */
    fun newIntent(context: Context, pickIntent: Intent): Intent =
        Intent(context, AttachmentPickerActivity::class.java).putExtra(EXTRA_PICK_INTENT, pickIntent)
  }
}
