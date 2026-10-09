package com.shiniese.motionswap.ui

import android.app.Application
import android.content.ContentResolver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiniese.motionswap.core.model.MotionProbe
import com.shiniese.motionswap.core.motion.ExtractTarget
import com.shiniese.motionswap.core.motion.MotionFormat
import com.shiniese.motionswap.data.Album
import com.shiniese.motionswap.data.MediaImage
import com.shiniese.motionswap.data.MediaStoreImageSource
import com.shiniese.motionswap.data.MediaStoreWriter
import com.shiniese.motionswap.data.SiblingLookup
import com.shiniese.motionswap.data.SiblingVideo
import com.shiniese.motionswap.data.SiblingVideoFinder
import com.shiniese.motionswap.task.ExtractWorker
import com.shiniese.motionswap.task.ItemStatus
import com.shiniese.motionswap.task.MergeWorker
import com.shiniese.motionswap.task.TaskRunner
import com.shiniese.motionswap.task.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One queued item plus whatever pre-flight discovered about it. */
data class QueueEntry(
    val image: MediaImage,
    val probe: MotionProbe? = null,
    val video: SiblingVideo? = null,
    /** Same-named videos exist in more than one folder, so none can be trusted. */
    val videoAmbiguous: Boolean = false,
    /**
     * A sibling search has already run, whether or not it matched.
     *
     * Without this, every queue refresh re-queries photos that have no sibling — the common case
     * for a plain gallery — so re-entering merge mode costs a MediaStore round-trip per row.
     */
    val videoSearched: Boolean = false,
    /** Pre-flight is still working on this row, so the row shows progress rather than a verdict. */
    val resolving: Boolean = false,
) {
    val name: String get() = image.displayName

    val isMotionPhoto: Boolean get() = probe is MotionProbe.Detected

    val formatLabel: String
        get() = (probe as? MotionProbe.Detected)?.origin?.name?.lowercase() ?: "未知"

    val videoLabel: String
        get() = when {
            resolving -> "正在查找同名视频…"
            video != null -> "${video.displayName} (${video.sizeBytes / 1024}KB)"
            videoAmbiguous -> "多个相册都有同名视频，无法确定"
            else -> "未找到同名视频"
        }
}

/** Which tab the user is on. */
enum class Mode { EXTRACT, MERGE }

class MotionSwapViewModel(application: Application) : AndroidViewModel(application) {

    private val resolver: ContentResolver = application.contentResolver
    private val imageSource = MediaStoreImageSource(resolver)
    private val writer = MediaStoreWriter(resolver)
    private val videoFinder = SiblingVideoFinder(resolver)
    private val extractWorker = ExtractWorker(resolver, writer)
    private val mergeWorker = MergeWorker(resolver, writer)

    val extractRunner = TaskRunner(viewModelScope)
    val mergeRunner = TaskRunner(viewModelScope)

    private val _mode = MutableStateFlow(Mode.EXTRACT)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    /**
 * One queue per mode.
 *
 * Extract and merge share nothing: a photo chosen for merging is still a plain JPEG until a
 * sibling video is found for it, so listing it under "提取动图" as a photo that has no motion
 * data is simply wrong — and merging every row of a mixed list would skip the ones with no
 * video anyway. Keeping a single shared list also made the per-mode progress ambiguous, since
 * the list you saw depended on which tab you happened to be on.
 */
private val queues: Map<Mode, MutableStateFlow<List<QueueEntry>>> =
    Mode.values().associateWith { MutableStateFlow(emptyList()) }

/** The queue backing the tab currently on screen. */
private val currentQueue: MutableStateFlow<List<QueueEntry>>
    get() = queues.getValue(_mode.value)

/** Switches with the tab, so the list on screen is always the one for the active mode. */
val queue: StateFlow<List<QueueEntry>> = combine(
    _mode,
    queues.getValue(Mode.EXTRACT),
    queues.getValue(Mode.MERGE),
) { mode, extract, merge -> if (mode == Mode.MERGE) merge else extract }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _outputDirectory = MutableStateFlow(MediaStoreWriter.DEFAULT_SUBDIRECTORY)
    val outputDirectory: StateFlow<String> = _outputDirectory.asStateFlow()

    private val _targetFormat = MutableStateFlow(MotionFormat.GOOGLE)
    val targetFormat: StateFlow<MotionFormat> = _targetFormat.asStateFlow()

    private val _extractTarget = MutableStateFlow(ExtractTarget.PLAIN)
    val extractTarget: StateFlow<ExtractTarget> = _extractTarget.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Switching to merge re-runs the sibling search on merge's own queue.
     *
     * Photos added for merging before the user committed to that tab were never looked up
     * against the video collection, and that lookup needs `READ_MEDIA_VIDEO` — which is only
     * requested at the point the user chooses to merge. Extract's queue is deliberately left
     * alone: it holds no video work and no permission to do it.
     */
    fun setMode(mode: Mode) {
        _mode.value = mode
        // Each tab owns its progress panel, so leaving one must not wipe the other's results.
        when (mode) {
            Mode.EXTRACT -> extractRunner.reset()
            Mode.MERGE -> mergeRunner.reset()
        }
        if (mode == Mode.MERGE && currentQueue.value.isNotEmpty()) refreshQueue()
    }

    fun setOutputDirectory(value: String) {
        _outputDirectory.value = value
    }

    /** Surfaces a one-off note in the same banner the task results use. */
    fun notify(text: String) {
        _message.value = text
    }

    fun setTargetFormat(format: MotionFormat) {
        _targetFormat.value = format
    }

    /**
     * Extraction's own vendor choice. Separate from [setTargetFormat] because the two tabs
     * ask different questions: merging picks the container to write into one file, while
     * extraction picks what the two halves should look like once apart.
     */
    fun setExtractTarget(target: ExtractTarget) {
        _extractTarget.value = target
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun loadAlbums() {
        viewModelScope.launch {
            _busy.value = true
            val loaded = guarded("读取相册失败") { imageSource.loadAlbums() }
            _albums.value = loaded
            _busy.value = false
            if (_message.value == null && loaded.isEmpty()) _message.value = "没有找到相册"
        }
    }

    /**
     * Adds photos to the queue and starts pre-flight, showing the rows before it finishes.
     *
     * The two steps are deliberately separate: the images land in [queue] the moment they are
     * read, marked [QueueEntry.resolving], and each row's verdict replaces it in place as it
     * arrives. Holding the list back until the whole batch resolved meant a several-hundred-photo
     * import looked like a frozen app, with the buttons greyed out and no rows to show.
     */
    private fun addAndResolve(images: List<MediaImage>, emptyMessage: String, added: (Int) -> String) {
        if (images.isEmpty()) {
            notify(emptyMessage)
            return
        }
        val existingUris = currentQueue.value.mapTo(mutableSetOf()) { it.image.uri }
        val incoming = images.filter { it.uri !in existingUris }
        // Marks rows in flight, including ones re-added that still need a result, so a row that
        // was queued but never resolved does not sit on "待检测" forever.
        val merging = _mode.value == Mode.MERGE
        currentQueue.value = merge(currentQueue.value, images.associateBy { it.uri }).map { entry ->
            val needsWork = entry.probe == null || (merging && entry.video == null && !entry.videoSearched)
            if (needsWork) entry.copy(resolving = true) else entry
        }
        notify(added(incoming.size))
        resolveEntriesAsync()
    }

    /** Runs [resolveEntries] over the current queue, folding each result back in as it lands. */
    private fun resolveEntriesAsync() {
        val merging = _mode.value == Mode.MERGE
        val entries = currentQueue.value
        val pending = entries.filter {
            it.probe == null || (merging && it.video == null && !it.videoSearched)
        }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            resolveEntries(entries) { updated ->
                currentQueue.value = currentQueue.value.map { if (it.image.uri == updated.image.uri) updated else it }
            }
        }
    }

    /** Adds photos chosen through the system photo picker. */
    fun addFromPicker(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val images = guarded("读取所选图片失败") { imageSource.loadByUris(uris) }
            _busy.value = false
            addAndResolve(
                images = images,
                emptyMessage = "未能读取所选图片",
                added = { "已加入 $it 张图片" },
            )
        }
    }

    /** Adds every photo in the chosen albums. Unbounded, unlike the system picker. */
    fun addFromAlbums(albums: List<Album>) {
        viewModelScope.launch {
            _busy.value = true
            val images = guarded("读取相册图片失败") {
                albums.flatMap { imageSource.loadAlbum(it) }
            }
            _busy.value = false
            addAndResolve(
                images = images,
                emptyMessage = "该相册里没有读到图片",
                added = { "已加入 $it 张图片，正在查找同名视频…" },
            )
        }
    }

    /**
     * Runs [block] on the IO dispatcher, turning any failure into a user-facing note.
     *
     * Two things are bundled here on purpose. The dispatcher hop is mandatory: every one of
     * these calls hits a content provider, and running that on the main thread is both slow
     * and a `NetworkOnMainThreadException` risk on large albums. And `viewModelScope.launch`
     * installs no exception handler, so anything thrown inside would reach the main thread's
     * uncaught handler and kill the process — a provider-side `SecurityException` or a
     * malformed selection arg would take the app down. A gallery app must degrade to
     * "nothing was added", never to a crash.
     */
    private suspend inline fun <T> guarded(
        context: String,
        crossinline block: () -> List<T>,
    ): List<T> =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            _message.value = "$context：${e.message ?: e.javaClass.simpleName}"
            emptyList()
        }

    private fun merge(
        existing: List<QueueEntry>,
        incoming: Map<android.net.Uri, MediaImage>,
    ): List<QueueEntry> {
        val byUri = LinkedHashMap<android.net.Uri, QueueEntry>()
        existing.forEach { byUri[it.image.uri] = it }
        incoming.forEach { (uri, image) ->
            if (!byUri.containsKey(uri)) byUri[uri] = QueueEntry(image)
        }
        return byUri.values.toList()
    }

    fun remove(image: MediaImage) {
        currentQueue.value = currentQueue.value.filterNot { it.image.uri == image.uri }
    }

    fun clearQueue() {
        // Only the tab on screen. Clearing merge must not throw away photos queued for extract,
        // nor leave the other tab's progress panel showing results for a list that is gone.
        currentQueue.value = emptyList()
        when (_mode.value) {
            Mode.EXTRACT -> extractRunner.reset()
            Mode.MERGE -> mergeRunner.reset()
        }
    }

    /**
     * Re-resolves every queued entry: motion-photo detection plus, in merge mode, the sibling
     * video. Called on every add and on switching to merge, which is why there is no manual
     * pre-flight button — nothing here needs the user to ask for it.
     */
    private fun refreshQueue() {
        if (currentQueue.value.isEmpty()) return
        // Re-run the sibling search for everything. The first switch into merge is the moment
        // video permission is requested, so rows that were searched while the grant was still
        // missing recorded an empty result; re-running is what turns those into matches.
        // Deliberately does not set `_busy`: that flag disables the add buttons, and a background
        // re-scan must not lock the UI.
        currentQueue.value = currentQueue.value.map { it.copy(videoSearched = false, resolving = true) }
        resolveEntriesAsync()
    }

    /**
     * Fills in each entry's probe and sibling video, reporting each one as it lands.
     *
     * Results are folded into [onUpdate] one entry at a time instead of returning a single
     * finished list. Importing a few hundred photos costs one MediaStore round-trip per photo,
     * so publishing only at the end left the list blank and every button disabled for the
     * whole scan — indistinguishable from a hang. Emitting per row keeps the queue readable and
     * lets the user start merging on the photos that already resolved.
     *
     * Every entry is re-examined rather than only the new ones, because a previously queued
     * entry may have been added before its folder became known (a picker URI that only gained
     * `RELATIVE_PATH` after album access was granted), in which case its video lookup failed and
     * would otherwise never be retried.
     */
    private suspend fun resolveEntries(
        entries: List<QueueEntry>,
        onUpdate: (QueueEntry) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            val needsProbe = entry.probe == null
            val needsVideo = _mode.value == Mode.MERGE && entry.video == null && !entry.videoSearched
            if (!needsProbe && !needsVideo) return@forEach

            val probe = if (needsProbe) extractWorker.probe(entry.image) else entry.probe
            // One lookup, reused for both fields.
            val lookup = if (needsVideo) lookupSibling(entry.image) else null
            onUpdate(
                entry.copy(
                    probe = probe,
                    video = lookup?.video ?: entry.video,
                    videoAmbiguous = lookup?.ambiguous ?: entry.videoAmbiguous,
                    // Recorded whether or not it matched: re-running the search on every refresh
                    // would cost another round-trip per photo that legitimately has no sibling.
                    videoSearched = entry.videoSearched || needsVideo,
                    resolving = false,
                )
            )
        }
    }

/**
     * Looks up the sibling video, treating a refused grant as "no match".
     *
 * Without `READ_MEDIA_VIDEO` the provider throws `SecurityException` rather than returning
 * zero rows, and `viewModelScope.launch` has no exception handler, so an uncaught throw here
 * would kill the process. The row simply keeps showing that no video was found.
 */
private suspend fun lookupSibling(image: MediaImage): SiblingLookup =
    withContext(Dispatchers.IO) {
        try {
            videoFinder.resolve(image)
        } catch (e: Exception) {
            SiblingLookup(null, ambiguous = false)
        }
    }

    fun startExtract() {
        val entries = currentQueue.value
        if (entries.isEmpty()) return
        val directory = _outputDirectory.value
        val target = _extractTarget.value
        extractRunner.start(entries.map { it.name }) { index, _ ->
            extractWorker.extract(entries[index].image, directory, target)
        }
    }

    fun startMerge() {
        val entries = currentQueue.value
        if (entries.isEmpty()) return
        val directory = _outputDirectory.value
        val format = _targetFormat.value
        mergeRunner.start(entries.map { it.name }) { index, _ ->
            val entry = entries[index]
            if (!entry.image.isJpegLike) {
                return@start ItemStatus.SKIPPED to "仅支持 JPEG，请先转换格式"
            }
            // Looked up again here rather than trusting the cached result: the folder can have
            // become known since the row was queued, and a permission may have been granted
            // after the fact. Both turn an earlier miss into a match.
            val video = entry.video ?: lookupSibling(entry.image).video
            if (video == null) {
                return@start ItemStatus.SKIPPED to "未找到同名视频"
            }
            mergeWorker.merge(entry.image, video, format, directory)
        }
    }

    /** Photos still being pre-flighted, so the UI can say so before the user hits merge. */
    val pendingCount: Int
        get() = currentQueue.value.count { it.resolving }

    fun cancelRunning() {
        if (extractRunner.isRunning) extractRunner.cancel()
        if (mergeRunner.isRunning) mergeRunner.cancel()
    }

    fun resetTask(state: TaskState) {
        if (state is TaskState.Finished) {
            when (_mode.value) {
                Mode.EXTRACT -> extractRunner.reset()
                Mode.MERGE -> mergeRunner.reset()
            }
        }
    }
}