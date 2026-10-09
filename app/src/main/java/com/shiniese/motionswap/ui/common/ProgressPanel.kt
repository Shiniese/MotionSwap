package com.shiniese.motionswap.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiniese.motionswap.task.ItemStatus
import com.shiniese.motionswap.task.TaskItem
import com.shiniese.motionswap.task.TaskState

/** Shows batch progress, per-item status, and a cancel affordance. */
@Composable
fun ProgressPanel(
    state: TaskState.Running,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.completed} / ${state.total}",
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }

        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )

        Text(
            "正在处理 ${state.currentName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Keyed by index, not by name. `LazyColumn` throws on a duplicate key, and album
        // import routinely yields the same file name in several rolls, so name is not unique.
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(top = 8.dp),
        ) {
            itemsIndexed(state.items) { index, item -> TaskRow(item, index) }
        }
    }
}

/** One status row; color alone never carries the meaning, the label does too. */
@Composable
private fun TaskRow(item: TaskItem, index: Int) {
    val color = when (item.status) {
        ItemStatus.DONE -> MaterialTheme.colorScheme.primary
        ItemStatus.FAILED -> MaterialTheme.colorScheme.error
        ItemStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        ItemStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        ItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#${index + 1} ${item.status.label()}",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun ItemStatus.label(): String = when (this) {
    ItemStatus.PENDING -> "等待"
    ItemStatus.RUNNING -> "进行"
    ItemStatus.DONE -> "完成"
    ItemStatus.FAILED -> "失败"
    ItemStatus.SKIPPED -> "跳过"
}