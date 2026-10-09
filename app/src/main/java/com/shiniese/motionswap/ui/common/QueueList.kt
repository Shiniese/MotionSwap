package com.shiniese.motionswap.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shiniese.motionswap.ui.QueueEntry

/**
 * The pre-flight queue, one card per selected photo.
 *
 * Rows appear as soon as the photos are read and fill in as each verdict lands, so importing a
 * few hundred photos shows progress instead of an empty list that looks like a hang.
 *
 * [modifier] is expected to carry a `weight` from the calling `Column`, which is what bounds
 * this list. A `LazyColumn` measured with infinite max height throws, so the caller must not
 * wrap it in a scrolling parent.
 */
@Composable
fun QueueList(
    entries: List<QueueEntry>,
    modeLabel: String,
    onRemove: (QueueEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "还没有选择图片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.image.uri.toString() }) { entry ->
            QueueRow(entry, modeLabel) { onRemove(entry) }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, modeLabel: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.image.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detailFor(entry, modeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor(entry),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}

private fun detailFor(entry: QueueEntry, modeLabel: String): String =
    if (modeLabel == MODE_MERGE) {
        entry.videoLabel
    } else if (entry.resolving) {
        "正在检测…"
    } else if (entry.probe == null) {
        "待检测"
    } else if (entry.isMotionPhoto) {
        "${entry.formatLabel} 动图 · ${entry.image.sizeBytes / 1024}KB"
    } else {
        "不是动图，将被跳过"
    }

@Composable
private fun detailColor(entry: QueueEntry) = if (entry.probe != null && !entry.isMotionPhoto) {
    MaterialTheme.colorScheme.error
} else {
    MaterialTheme.colorScheme.onSurfaceVariant
}

const val MODE_EXTRACT = "extract"
const val MODE_MERGE = "merge"