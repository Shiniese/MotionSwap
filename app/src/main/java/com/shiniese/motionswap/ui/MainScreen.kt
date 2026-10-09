package com.shiniese.motionswap.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shiniese.motionswap.core.motion.ExtractTarget
import com.shiniese.motionswap.core.motion.MotionFormat
import com.shiniese.motionswap.task.TaskState
import com.shiniese.motionswap.ui.common.MODE_EXTRACT
import com.shiniese.motionswap.ui.common.MODE_MERGE
import com.shiniese.motionswap.ui.common.ProgressPanel
import com.shiniese.motionswap.ui.common.QueueList
import com.shiniese.motionswap.ui.picker.AlbumPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MotionSwapViewModel = viewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val directory by viewModel.outputDirectory.collectAsStateWithLifecycle()
    val format by viewModel.targetFormat.collectAsStateWithLifecycle()
    val extractTarget by viewModel.extractTarget.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val extractState by viewModel.extractRunner.state.collectAsStateWithLifecycle()
    val mergeState by viewModel.mergeRunner.state.collectAsStateWithLifecycle()

    val taskState = if (mode == Mode.EXTRACT) extractState else mergeState
    val running = taskState as? TaskState.Running

    var showAlbums by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaStore.getPickImagesMaxLimit())
    ) { uris ->
        viewModel.addFromPicker(uris)
    }

    // Runs `action` once the reads *this path* needs are granted, or explains what is missing.
    //
    // The requirement set is per path, not per mode: the picker in extract mode needs nothing
    // (its URIs are already granted), while merge needs the video collection *and* the picked
    // image's folder. The picker previously requested nothing at all, which is why it could
    // never pair a video — every row just read "未找到同名视频" with no indication why.
    var pendingPermission by remember { mutableStateOf<Pair<List<String>, () -> Unit>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val request = pendingPermission
        pendingPermission = null
        if (request == null) return@rememberLauncherForActivityResult
        val (requested, action) = request
        val unmet = requested.filterNot { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (unmet.isEmpty()) {
            action()
        } else {
            viewModel.notify(
                "缺少 ${unmet.joinToString("、") { it.substringAfterLast('.') }}，" +
                    "无法查找同名视频"
            )
        }
    }

    fun launchPicked() {
        pickerLauncher.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    fun openPicker() {
        val required = requiredMediaPermissions(context, merging = mode == Mode.MERGE)
        if (required.isEmpty()) launchPicked()
        else {
            pendingPermission = required to ::launchPicked
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    fun openAlbums() {
        val action = {
            viewModel.loadAlbums()
            showAlbums = true
        }
        // Browsing albums needs the images grant even in extract mode; that is what makes the
        // folder known, which is in turn what makes a sibling lookup possible.
        val required =
            requiredMediaPermissions(context, merging = mode == Mode.MERGE, needsImages = true)
        if (required.isEmpty()) action()
        else {
            pendingPermission = required to action
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (mode == Mode.EXTRACT) "提取动图" else "合并动图") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = mode == Mode.EXTRACT,
                    onClick = { viewModel.setMode(Mode.EXTRACT) },
                    icon = {},
                    label = { Text("提取") },
                )
                NavigationBarItem(
                    selected = mode == Mode.MERGE,
                    onClick = { viewModel.setMode(Mode.MERGE) },
                    icon = {},
                    label = { Text("合并") },
                )
            }
        },
    ) { padding ->
        // No verticalScroll here on purpose. A scrollable parent measures its children with
        // infinite max height, which LazyColumn rejects outright ("Vertically scrollable
        // component was measured with an infinity maximum height constraints"). The header
        // is a fixed ~240dp block, so the list takes the remaining space and scrolls on its
        // own — which also keeps album import, unlike the system picker, unbounded in count.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = ::openPicker,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("系统相册选取") }

                OutlinedButton(
                    onClick = ::openAlbums,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("按相册导入") }
            }

            Text(
                if (mode == Mode.MERGE) {
                    "合并需读取视频权限，用于查找同目录同名视频"
                } else {
                    "系统选取有数量上限；相册导入不限量"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mode == Mode.MERGE) {
                Text("目标厂商", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MotionFormat.mergeTargets.forEachIndexed { index, target ->
                        SegmentedButton(
                            selected = format == target,
                            onClick = { viewModel.setTargetFormat(format = target) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = MotionFormat.mergeTargets.size,
                            ),
                            // A fixed height plus single-line short labels keeps every
                            // segment the same size; the full name goes in the caption so
                            // a long label can no longer stretch one segment taller.
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) { Text(target.shortName, maxLines = 1) }
                    }
                }
                Text(
                    MotionFormat.mergeTargets.first { it == format }.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("输出形态", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ExtractTarget.entries.forEachIndexed { index, target ->
                        SegmentedButton(
                            selected = extractTarget == target,
                            onClick = { viewModel.setExtractTarget(target) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ExtractTarget.entries.size,
                            ),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) { Text(target.displayName, maxLines = 1) }
                    }
                }
                Text(
                    if (extractTarget == ExtractTarget.VIVO_PAIR) {
                        "拆成图片 + 视频，并写入 vivo 用来配对的标记；相册里显示为动态照片"
                    } else {
                        "拆成图片 + 视频，不加任何厂商标记"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = directory,
                onValueChange = viewModel::setOutputDirectory,
                label = { Text("输出目录") },
                supportingText = { Text("保存到 Pictures/$directory/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (running == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (mode == Mode.EXTRACT) viewModel.startExtract()
                            else viewModel.startMerge()
                        },
                        enabled = queue.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (mode == Mode.EXTRACT) "开始提取" else "开始合并") }
                    TextButton(onClick = viewModel::clearQueue, enabled = queue.isNotEmpty()) {
                        Text("清空")
                    }
                }

                val pending by remember { derivedStateOf { viewModel.pendingCount } }
                if (pending > 0) {
                    Text(
                        "正在检测 $pending 张，可先开始处理已完成的图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                QueueList(
                    entries = queue,
                    modeLabel = if (mode == Mode.EXTRACT) MODE_EXTRACT else MODE_MERGE,
                    onRemove = { viewModel.remove(it.image) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                ProgressPanel(
                    state = running,
                    onCancel = viewModel::cancelRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }

    if (showAlbums) {
        AlbumPickerSheet(
            albums = albums,
            onDismiss = { showAlbums = false },
            onConfirm = viewModel::addFromAlbums,
        )
    }

    val finished = taskState as? TaskState.Finished
    if (finished != null) {
        ResultDialog(finished, onDismiss = { viewModel.resetTask(taskState) })
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("好") }
            },
        )
    }
}

@Composable
private fun ResultDialog(state: TaskState.Finished, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("处理完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("成功 ${state.succeeded} / ${state.total}")
                if (state.skipped.isNotEmpty()) Text("跳过 ${state.skipped.size} 项")
                if (state.failed.isNotEmpty()) {
                    Text("失败 ${state.failed.size} 项：", color = MaterialTheme.colorScheme.error)
                    state.failed.take(MAX_LISTED_FAILURES).forEach {
                        Text("· ${it.name}: ${it.detail ?: "未知原因"}")
                    }
                    if (state.failed.size > MAX_LISTED_FAILURES) {
                        Text("… 还有 ${state.failed.size - MAX_LISTED_FAILURES} 项")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private const val MAX_LISTED_FAILURES = 8

/**
 * The media reads to ask for, already filtered down to the ones not yet held.
 *
 * Merge needs *both* `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`: the first to learn which
 * folder a picked photo lives in, the second to enumerate the video collection at all. They
 * are separate locks — an images grant alone leaves every sibling query returning zero rows,
 * which surfaces as "未找到同名视频" on every row rather than as a permission error.
 *
 * Android 14 added a third grant the user can pick instead of full access. When it is already
 * held it is dropped from the request, so choosing "select media" in the system dialog counts
 * as satisfying the need rather than being re-asked for on the next tap.
 */
private fun requiredMediaPermissions(
    context: Context,
    merging: Boolean,
    needsImages: Boolean = merging,
): List<String> {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val selected = granted(READ_MEDIA_VISUAL_USER_SELECTED)
        if (!merging) return listOfNotNull(READ_MEDIA_IMAGES.takeIf { needsImages && !selected })
        val full = granted(READ_MEDIA_IMAGES) && granted(READ_MEDIA_VIDEO)
        if (full || selected) return emptyList()
        return listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return buildList {
            if (needsImages && !granted(READ_MEDIA_IMAGES)) add(READ_MEDIA_IMAGES)
            if (merging && !granted(READ_MEDIA_VIDEO)) add(READ_MEDIA_VIDEO)
        }
    }

    // Before the split, one storage grant covered images and videos alike.
    return if ((needsImages || merging) && !granted(READ_EXTERNAL_STORAGE)) {
        listOf(READ_EXTERNAL_STORAGE)
    } else {
        emptyList()
    }
}

private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
private const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
private const val READ_MEDIA_VISUAL_USER_SELECTED =
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
private const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"