package com.tom.rv2ide.artificial.agent.compose.components.bubbles

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
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

/** Compose 的只读选择菜单；回调捕获当前消息 ID，不从选中片段反推整条消息。 */
@Composable
internal fun MessageSelection(onQuote: (() -> Unit)?, content: @Composable () -> Unit) {
  var actions by remember { mutableStateOf<SelectionActions?>(null) }
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
          onQuote?.let { quote -> TextButton(onClick = { toolbar.hide(); quote() }) {
            Text(stringResource(R.string.ai_quote_whole_message))
          } }
        }
      }
    }
  }
}
