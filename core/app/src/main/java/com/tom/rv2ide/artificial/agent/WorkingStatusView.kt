/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.tom.rv2ide.resources.R.string

/**
 * 运行状态条：转动的点阵 + 一行「此刻在做什么」。
 *
 * <p><b>为什么需要它</b>：模型首字节可能要等好几秒（推理模型更久），而此前的界面在等待
 * 期间只有一个静态的「正在运行 xxx…」文字。静态文字无法区分「在工作」和「卡死了」，
 * 用户只能干等或误以为程序无响应。一个持续运动的指示器是「还活着」最直接的信号。
 *
 * <p><b>为什么文案是外部传入的（[setAction]）而不是只有两种固定文案</b>：
 * 只区分「思考中 / 处理中」时，一次运行里几十次工具调用在界面上没有任何差别，
 * 用户看不出 agent 是在读文件、在改代码，还是卡在一条命令上。参考项目（cc-haha）
 * 的做法是把最新动作当成一行实时播报（"Reading src/a.ts"、"Running npm test"），
 * 这里照做——宿主在 TOOL_STARTED 时按工具名与参数拼出这条文案。
 *
 * <p>没有显式动作时的兜底仍是两种固定文案，区分依据是 [bind] 的 `thinking`：
 * 有推理内容且正文还是空 → 模型在思考；否则 → 在输出正文。这个区分有实际意义：
 * 推理模型思考半分钟是正常的，用户看到「思考中」不会以为出了问题。
 *
 * <p><b>2026-10-10 换 Compose 实现</b>：原先是自定义 `View` + Canvas 手绘点阵与文字
 * （3×3 点阵 + 逐帧亮度循环）。换的动机与渲染层整体迁移一致——手绘 View 的颜色
 * 写死在代码里、不跟随 Compose 主题，与面板其余部分（全部 Compose 化）并排时
 * 色调与字体对不上，正是用户反馈的「看起来不是一个界面」。
 *
 * <p><b>为什么是 FrameLayout 包一个 ComposeView 而不是直接继承 ComposeView</b>：
 * `ComposeView` 是 final 类，不能继承（kapt 直接报「无法从最终 ComposeView 进行继承」）。
 * 用组合代替继承，对外 API 与 XML 用法都不变——宿主仍写
 * `<com.tom.rv2ide.artificial.agent.WorkingStatusView .../>`，9 处调用点零改动。
 */
class WorkingStatusView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {

  /** 当前动作文案；null 表示回退到「思考中/处理中」。 */
  private var actionText by mutableStateOf<CharSequence?>(null)

  /** 是否处于「模型在推理」阶段（无动作文案时决定显示哪条固定文案）。 */
  private var thinking by mutableStateOf(false)

  /**
   * 是否正在工作。
   *
   * <p>不工作时整条状态条由宿主隐藏（`isVisible = running`），但 Compose 侧的
   * 无限动画若不停会持续请求帧——视图不可见时白耗电。因此这里把「是否工作」
   * 也作为状态传进组合，false 时干脆不进入 Compose 子树。
   */
  private var working by mutableStateOf(false)

  private val composeView =
      ComposeView(context).apply {
        layoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setContent {
          if (working) {
            com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme {
              com.tom.rv2ide.artificial.agent.compose.components.independent.WorkingStatusBar(
                  action = actionText,
                  thinking = thinking,
              )
            }
          }
        }
      }

  init {
    addView(composeView)
  }

  /** 设置「思考中」还是「处理中」；无动作文案时决定显示哪条。 */
  fun bind(isThinking: Boolean) {
    thinking = isThinking
    // 无障碍：文字虽由 Compose 绘制（语义树里有），视图级也标一份——
    // 与旧实现的 publishLabel 同义，保证 uiautomator dump 能断言这条状态。
    contentDescription = currentLabel()
  }

  /**
   * 设置当前动作（例如「正在读取 app/build.gradle」）。
   *
   * <p>传 null 回退到「思考中/处理中」。**不在这里做省略**：截断交给 Compose 的
   * TextOverflow（可用宽度只有在测量时才知道）。
   */
  fun setAction(text: CharSequence?) {
    actionText = text?.takeIf { it.isNotBlank() }
    contentDescription = currentLabel()
  }

  /** 开始工作：点阵开始转。 */
  fun startWorking() {
    working = true
    contentDescription = currentLabel()
  }

  /** 结束工作：停动画并清掉动作文案。 */
  fun stopWorking() {
    working = false
    // 动作文案随运行结束一起清掉：留着它会让下一条消息的工具文案在还没开始时
    // 就先显示出来，看起来像「刚点发送就已经在读文件了」。
    actionText = null
    contentDescription = currentLabel()
  }

  /**
   * 当前应显示的文案。
   *
   * <p>与 [com.tom.rv2ide.artificial.agent.compose.components.independent.WorkingStatusBar]
   * 内部的取值逻辑保持一致（同样优先 actionText）。两处都要：语义树来自 Compose 组件，
   * 而视图级 contentDescription 来自这里——自动化断言通常读后者。
   */
  private fun currentLabel(): CharSequence =
      actionText
          ?: context.getString(
              if (thinking) string.ai_assistant_thinking_running else string.ai_assistant_working)
}
