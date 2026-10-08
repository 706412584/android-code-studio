/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.artificial.agent.compose.compat

import com.tom.rv2ide.ai.tool.ToolSettingsPort

/**
 * `ask_user_question` 的问答模型（源自 Aharou
 * `feature/agent/domain/tool/question/UserQuestionModels.kt`）。
 *
 * <p><b>字段与类型名逐字照 Aharou</b>：询问面板是「保留原样、只改 import」移植的，
 * 任何改名都会让移植 diff 从 import 变成字段改动，那种 diff 最容易改漏。
 *
 * <p><b>同时提供 ACS 侧的双向适配</b>：ACS 的真实契约是
 * `ToolSettingsPort.askUserQuestion(List<Question>): List<String>?`
 * （阻塞式、多选以逗号连接，见 `AskUserQuestionTool.renderAnswers`），
 * 与这里的「列表进、列表出」只差一层形状转换，转换函数就放在旁边，
 * 免得接入层再各写一份 `joinToString`。
 */

/** AI 调用 `ask_user_question` 后挂起等待用户回答的请求。 */
data class PendingUserQuestion(
    /** 本次调用的唯一标识（对应工具调用 id），用于 resolve 时配对。 */
    val id: String,
    /** 本次要问用户的 1-4 个问题。 */
    val questions: List<QuestionItem>
)

/**
 * 单个问题。
 *
 * @param question 完整问题文本，如「该用哪个库做 HTTP 请求？」
 * @param header 短标签（≤12 字符），展示为 Chip/Tag，如「库」「方案」。
 * @param options 2-4 个预设选项（UI 会自动追加一个「其他」选项）。
 * @param multiSelect 是否允许多选。false = 单选 RadioButton，true = 多选 Checkbox。
 */
data class QuestionItem(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false
)

/**
 * 单个选项。
 *
 * @param label 选项标签（简短 1-5 个词），如「OkHttp（推荐）」。
 * @param description 选项说明，解释选它的含义或利弊。
 */
data class QuestionOption(val label: String, val description: String)

/**
 * 用户对一次提问的全部回答。
 *
 * @param answers 每个问题对应一个 [SingleAnswer]。若用户点了「补充」，此列表为空。
 */
data class UserQuestionAnswer(val answers: List<SingleAnswer>)

/**
 * 对单个问题的回答。
 *
 * @param question 原问题文本（便于 AI 对应回答是针对哪个问题的）。
 * @param selected 用户选中的选项 label 列表（单选=1 个元素，多选可能多个）。
 * @param customText 若用户选了「其他」并输入了自定义文本，存于此字段；否则为 null。
 */
data class SingleAnswer(
    val question: String,
    val selected: List<String>,
    val customText: String? = null
)

// ---- ACS 侧适配：ToolSettingsPort.Question <-> 上述模型 ----

/**
 * ACS 的题目 → 面板题目。
 *
 * <p>ACS 把「选项标签」与「选项说明」存成两个**平行列表**（`options` / `optionDescriptions`），
 * 靠下标对应；缺说明时按空串兜底（说明只是补充信息，不该因为没写就整题不显示）。
 */
fun ToolSettingsPort.Question.toQuestionItem(): QuestionItem =
    QuestionItem(
        question = question,
        header = header,
        options =
            options.mapIndexed { index, label ->
              QuestionOption(label = label, description = optionDescriptions.getOrElse(index) { "" })
            },
        multiSelect = multiSelect)

/** ACS 的题目列表 → 面板的待答请求。[id] 用工具调用 id，回答时原样带回。 */
fun List<ToolSettingsPort.Question>.toPendingUserQuestion(id: String): PendingUserQuestion =
    PendingUserQuestion(id = id, questions = map { it.toQuestionItem() })

/**
 * 面板的回答 → ACS 期望的答案列表（每题一项，与题目同序）。
 *
 * <p>两条约定都是照 ACS 现有实现定的：
 * 1. 多选用 `,` 连接 —— `AskUserQuestionTool.renderAnswers` 就是按 `,` split 后逐项比对的；
 * 2. 自定义文本优先 —— 对应 ACS 里用户选「其他」后走自由输入框。
 *
 * <p><b>不补长</b>：`renderAnswers` 对「下标越界」与「空串」给出同一结论（未作答），
 * 所以答得少时不必填占位——两种情况走的是同一条分支。
 *
 * <p>⚠️ 含逗号的 `customText` 会被 `renderAnswers` 拆成多项再比对，最终仍判为「自定义答案」；
 * 结论不受影响，但这是 ACS 既有行为，这里不去改它。
 */
fun UserQuestionAnswer.toAnswerList(): List<String> =
    answers.map { answer ->
      answer.customText?.takeIf { text -> text.isNotBlank() } ?: answer.selected.joinToString(",")
    }
