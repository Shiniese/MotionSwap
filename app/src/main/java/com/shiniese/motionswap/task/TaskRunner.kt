package com.shiniese.motionswap.task

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Per-item outcome shown in the progress list. */
enum class ItemStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/**
 * One row in the task list.
 *
 * [name] is the display name and is deliberately *not* unique: album import can pull in
 * `IMG_0001.jpg` from several camera rolls at once, so list keys must not be built from it.
 */
data class TaskItem(
    val name: String,
    val status: ItemStatus = ItemStatus.PENDING,
    val detail: String? = null,
)

/** States a batch job moves through. */
sealed interface TaskState {
    data object Idle : TaskState

    data class Running(
        val total: Int,
        val completed: Int,
        val currentName: String,
        val items: List<TaskItem>,
    ) : TaskState {
        val progress: Float
            get() = if (total == 0) 0f else completed.toFloat() / total
    }

    data class Finished(
        val succeeded: Int,
        val failed: List<TaskItem>,
        val skipped: List<TaskItem>,
    ) : TaskState {
        val total: Int get() = succeeded + failed.size + skipped.size
    }
}

/**
 * Drives a batch job and publishes its progress.
 *
 * Concurrency is deliberately low: motion photo files are tens of megabytes each, so
 * running many at once trades throughput for memory pressure and IO contention with no
 * real gain.
 */
class TaskRunner(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is TaskState.Running

    /**
     * Runs [work] once per item. A thrown exception is recorded as a failure and the
     * batch continues with the remaining items.
     */
    fun start(
        names: List<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        work: suspend (index: Int, name: String) -> Pair<ItemStatus, String?>,
    ) {
        if (isRunning) return
        job = scope.launch {
            _state.value = TaskState.Running(
                total = names.size,
                completed = 0,
                currentName = "",
                items = names.map { TaskItem(it) },
            )
            runBatch(names, concurrency, work)
        }
    }

    private suspend fun runBatch(
        names: List<String>,
        concurrency: Int,
        work: suspend (index: Int, name: String) -> Pair<ItemStatus, String?>,
    ) {
        val lanes = concurrency.coerceAtLeast(1)
        val next = AtomicInteger(0)
        val results = arrayOfNulls<Pair<ItemStatus, String?>>(names.size)

        coroutineScope {
            repeat(names.size.coerceAtMost(lanes)) {
                launch {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= names.size) break
                        markRunning(index, names[index])
                        val outcome = try {
                            work(index, names[index])
                        } catch (e: Exception) {
                            ItemStatus.FAILED to (e.message ?: "处理失败")
                        }
                        results[index] = outcome
                        markFinished(index, outcome)
                    }
                }
            }
        }

        val failed = mutableListOf<TaskItem>()
        val skipped = mutableListOf<TaskItem>()
        results.forEachIndexed { index, outcome ->
            if (outcome == null) return@forEachIndexed
            val item = TaskItem(names[index], outcome.first, outcome.second)
            when (outcome.first) {
                ItemStatus.FAILED -> failed += item
                ItemStatus.SKIPPED -> skipped += item
                else -> Unit
            }
        }
        val succeeded = results.count { it?.first == ItemStatus.DONE }
        _state.value = TaskState.Finished(succeeded, failed, skipped)
    }

    private fun markRunning(index: Int, name: String) {
        val current = _state.value as? TaskState.Running ?: return
        _state.value = current.copy(
            currentName = name,
            items = current.items.toMutableList().also { list ->
                list[index] = list[index].copy(status = ItemStatus.RUNNING)
            },
        )
    }

    private fun markFinished(index: Int, outcome: Pair<ItemStatus, String?>) {
        val current = _state.value as? TaskState.Running ?: return
        _state.value = current.copy(
            completed = current.completed + 1,
            items = current.items.toMutableList().also { list ->
                list[index] = list[index].copy(status = outcome.first, detail = outcome.second)
            },
        )
    }

    /** Stops scheduling new work; items already in flight are abandoned. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = TaskState.Idle
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = TaskState.Idle
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 2
    }
}