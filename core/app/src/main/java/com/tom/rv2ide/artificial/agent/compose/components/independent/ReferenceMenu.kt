package com.tom.rv2ide.artificial.agent.compose.components.independent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.ReferenceCandidate
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Grid
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun referenceKindLabel(kind: String): String = stringResource(when (kind) {
  "session" -> R.string.ai_reference_session
  "file" -> R.string.ai_reference_file
  "agent" -> R.string.ai_reference_agent
  "skill" -> R.string.ai_reference_skill
  else -> R.string.ai_reference_plugin
})

internal fun referenceKindIcon(kind: String): ImageVector = when (kind) {
  "session" -> FeatherIcons.MessageSquare
  "file" -> FeatherIcons.FileText
  "agent" -> FeatherIcons.Cpu
  "skill" -> FeatherIcons.Zap
  // 消息引用：与「会话」同用对话图标——它引用的就是一段对话内容。
  "quote" -> FeatherIcons.MessageSquare
  else -> FeatherIcons.Grid
}

@Composable
internal fun ReferenceChips(
    value: TextFieldValue,
    references: List<ComposerReference>,
    onRemove: (ComposerReference) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (references.isEmpty()) return
  Row(
      modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    references.forEach { reference ->
      Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(referenceKindIcon(reference.kind), contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(Modifier.width(Spacing.xs))
          Text(
              text = reference.token,
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.widthIn(max = 180.dp),
          )
          IconButton(
              enabled = value.composition == null,
              onClick = { onRemove(reference) },
              modifier = Modifier.size(36.dp),
          ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.ai_reference_remove, reference.label),
                modifier = Modifier.size(14.dp),
            )
          }
        }
      }
    }
  }
}

internal fun referenceTrigger(value: TextFieldValue, references: List<ComposerReference>): TextRange? {
  if (value.composition != null || !value.selection.collapsed) return null
  val cursor = value.selection.end
  if (references.any { cursor > it.start && cursor <= it.end }) return null
  val prefix = value.text.take(cursor)
  val token = trailingAtToken(prefix) ?: return null
  return TextRange(cursor - token.length, cursor)
}

@Composable
internal fun referenceTransformation(references: List<ComposerReference>): VisualTransformation {
  val color = MaterialTheme.colorScheme.primary
  val background = MaterialTheme.colorScheme.primaryContainer
  return VisualTransformation { text ->
    // 消息引用（kind=quote）只靠输入框上方的胶囊表达，正文里不显示它的 token；
    // 但 token 仍是发送时展开成引用块的载体，所以只从**显示**里移除、底层文本不动。
    // 隐藏后可见文本变短，其余 @ 提及的高亮与光标要靠 OffsetsMapping 换算。
    val quoteRanges = references
        .filter { it.kind == "quote" && it.start >= 0 && it.end <= text.length && it.start < it.end }
        .map { it.start until it.end }
    val mapping = HiddenTextMapping(quoteRanges)

    val styled = buildAnnotatedString {
      // 逐段追加未隐藏的文本，跳过 quote 区间；@ 提及用原始坐标加高亮，追加时按
      // 已跳过的字符数换算到可见坐标，保证样式落在正确位置。
      var cursor = 0
      for (range in quoteRanges.sortedBy { it.first }) {
        append(text, cursor, range.first)
        cursor = range.last + 1
      }
      append(text, cursor, text.length)
      references.forEach {
        if (it.kind != "quote" && it.start >= 0 && it.end <= text.length && it.start < it.end) {
          val vs = mapping.toVisible(it.start)
          val ve = mapping.toVisible(it.end)
          if (vs < ve) {
            addStyle(SpanStyle(color = color, background = background, fontWeight = FontWeight.SemiBold), vs, ve)
          }
        }
      }
    }
    TransformedText(
        styled,
        object : OffsetMapping {
          override fun originalToTransformed(offset: Int): Int = mapping.toVisible(offset)
          override fun transformedToOriginal(offset: Int): Int = mapping.toOriginal(offset)
        },
    )
  }
}

@Composable
internal fun ReferenceMenu(
    value: TextFieldValue,
    references: List<ComposerReference>,
    kind: String?,
    provider: suspend (String, String?) -> List<ReferenceCandidate>,
    onSelect: (ComposerReference, Int, Int) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp = 280.dp,
) {
  val trigger = referenceTrigger(value, references)
  var dismissed by remember(value.text, value.selection, provider) { mutableStateOf(false) }
  if (value.composition != null || (kind == null && (trigger == null || dismissed))) return
  val query = if (trigger != null) value.text.substring(trigger.start + 1, trigger.end) else ""
  var selectedKind by remember(kind, trigger?.start) { mutableStateOf(kind) }
  var candidates by remember(query, selectedKind) { mutableStateOf<List<ReferenceCandidate>>(emptyList()) }
  var loading by remember(query, selectedKind) { mutableStateOf(true) }
  var failed by remember(query, selectedKind) { mutableStateOf(false) }
  val currentProvider by rememberUpdatedState(provider)
  LaunchedEffect(query, selectedKind) {
    delay(160)
    try {
      candidates = withContext(Dispatchers.IO) { currentProvider(query, selectedKind) }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      failed = true
    } finally {
      loading = false
    }
  }
  Surface(
      modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
      shape = RoundedCornerShape(Radius.lg),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.heightIn(max = maxHeight).padding(Spacing.sm)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.ai_reference_choose),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = Spacing.xs),
        )
        IconButton(onClick = { dismissed = true; onDismiss() }, modifier = Modifier.size(36.dp)) {
          Icon(
              FeatherIcons.X,
              contentDescription = stringResource(android.R.string.cancel),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
          )
        }
      }
      Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        (listOf<String?>(null) + listOf("session", "file", "agent", "skill", "plugin")).forEach { category ->
          FilterChip(
              selected = selectedKind == category,
              onClick = { selectedKind = category },
              label = { Text(category?.let { referenceKindLabel(it) } ?: stringResource(R.string.ai_reference_all)) },
              leadingIcon = category?.let { { Icon(referenceKindIcon(it), null, Modifier.size(14.dp)) } },
              shape = RoundedCornerShape(50),
          )
        }
      }
      when {
        loading || failed || candidates.isEmpty() -> Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
          if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
          else Text(
              stringResource(if (failed) R.string.ai_reference_load_failed else R.string.ai_reference_empty),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        else -> LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
          items(candidates, key = { it.reference.kind + ":" + it.reference.id }) { item ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).clickable {
                  val range = trigger ?: value.selection
                  onSelect(item.reference, range.min, range.max)
                }.padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                  shape = RoundedCornerShape(Radius.sm),
                  color = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Icon(
                    referenceKindIcon(item.reference.kind), null,
                    Modifier.padding(Spacing.sm).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
              Spacer(Modifier.width(Spacing.sm))
              Column(Modifier.weight(1f)) {
                Text(
                    "@${item.reference.label}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${referenceKindLabel(item.reference.kind)} · ${item.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
              }
              Icon(FeatherIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}
