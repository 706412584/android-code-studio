package com.tom.rv2ide.artificial.agent.compose.components.bubbles

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.tom.rv2ide.R

private data class SelectionActions(
    val rect: Rect,
    val copy: (() -> Unit)?,
    val selectAll: (() -> Unit)?,
)

/**
 * Compose 的只读选择菜单。
 *
 * <p>「引用」取的是**当前选中文字**（与 cc-haha 的 chat-selection 语义一致）：长按出现的菜单本身
 * 就以存在选区为前提，因此这里不再引用整条消息——未选中任何内容时由上层回退整条。
 */
@Composable
internal fun MessageSelection(onQuote: ((String?) -> Unit)?, content: @Composable () -> Unit) {
  var actions by remember { mutableStateOf<SelectionActions?>(null) }
  val clipboard = LocalClipboardManager.current
  val toolbar = remember {
    object : TextToolbar {
      override val status: TextToolbarStatus
        get() = if (actions == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
      override fun hide() { actions = null }
      override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?,
          onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        actions = SelectionActions(rect, onCopyRequested, onSelectAllRequested)
      }
    }
  }
  CompositionLocalProvider(LocalTextToolbar provides toolbar) {
    SelectionContainer(content = content)
  }
  actions?.let { current ->
    val position = remember(current.rect) {
      object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
            current.rect.left.toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (current.rect.top.toInt() - popupContentSize.height).coerceIn(0,
                (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
      }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { toolbar.hide() }) {
      Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp) {
        Row {
          current.copy?.let { copy -> TextButton(onClick = { copy(); toolbar.hide() }) {
            Text(stringResource(android.R.string.copy))
          } }
          current.selectAll?.let { selectAll -> TextButton(onClick = selectAll) {
            Text(stringResource(android.R.string.selectAll))
          } }
          onQuote?.let { quote -> TextButton(onClick = {
            val selected = readSelectedText(clipboard, current.copy)
            toolbar.hide()
            quote(selected)
          }) {
            Text(stringResource(R.string.ai_quote_selection))
          } }
        }
      }
    }
  }
}

/**
 * 读取选区文字。
 *
 * <p>Compose 的 [TextToolbar] 只提供「复制」回调（内部把当前选区写进剪贴板），**没有**直接暴露
 * 选中文本的 API。因此这里先记下原剪贴板内容、触发一次复制、读回文本，再把原内容还原——引用
 * 不应该顺带改掉用户的剪贴板。读不到（宿主不在前台而被系统拒绝等）时返回 null，由上层回退到
 * 引用整条消息。
 */
private fun readSelectedText(clipboard: ClipboardManager, copy: (() -> Unit)?): String? {
  if (copy == null) {
    return null
  }
  val previous = runCatching { clipboard.getText() }.getOrNull()
  if (runCatching { copy() }.isFailure) {
    return null
  }
  val selected = runCatching { clipboard.getText()?.text }.getOrNull()?.trim()
  if (previous != null && previous.text.isNotEmpty()) {
    runCatching { clipboard.setText(previous) }
  }
  return selected?.takeIf { it.isNotEmpty() }
}
