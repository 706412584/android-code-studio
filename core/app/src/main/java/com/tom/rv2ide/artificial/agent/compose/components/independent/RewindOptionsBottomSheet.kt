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

package com.tom.rv2ide.artificial.agent.compose.components.independent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.tom.rv2ide.artificial.agent.compose.components.tools.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.RotateCcw

enum class RewindOption {
    RESTORE_CODE_AND_CONVERSATION,
    RESTORE_CONVERSATION,
    RESTORE_CODE,

    /** 撤销最近一次代码恢复，把当时的现场写回去。 */
    UNDO_LAST_RESTORE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewindOptionsBottomSheet(
    promptSnippet: String,
    onOptionSelected: (RewindOption) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.checkpoint_rewind_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (promptSnippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.checkpoint_target_prompt, promptSnippet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            OptionRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.checkpoint_restore_code_and_conversation),
                description = stringResource(R.string.checkpoint_restore_code_and_conversation_desc),
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CODE_AND_CONVERSATION)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.checkpoint_restore_conversation),
                description = stringResource(R.string.checkpoint_restore_conversation_desc),
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CONVERSATION)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.checkpoint_restore_code),
                description = stringResource(R.string.checkpoint_restore_code_desc),
                onClick = {
                    onOptionSelected(RewindOption.RESTORE_CODE)
                    onDismissRequest()
                }
            )

            OptionRow(
                icon = FeatherIcons.RotateCcw,
                title = stringResource(R.string.checkpoint_undo_last_restore),
                description = stringResource(R.string.checkpoint_undo_last_restore_desc),
                onClick = {
                    onOptionSelected(RewindOption.UNDO_LAST_RESTORE)
                    onDismissRequest()
                }
            )

            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = Spacing.sm)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
