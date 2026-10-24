package com.bitlockerdroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VolumeOperationKind { CLOSE, EJECT }
enum class VolumeOperationStage {
    PREPARING, WAITING_FOR_WRITES, STOPPING_SHARES, UNMOUNTING,
    SYNCING_VOLUME, CLOSING_VOLUME, CHECKING_DISK,
    SYNCING_DEVICE, STOPPING_DEVICE, SYNCING_AND_STOPPING_DEVICE, FINALIZING
}
enum class VolumeOperationOutcome { RUNNING, VOLUME_CLOSED, DISK_BUSY, DISK_STOPPED, FAILED }

data class VolumeOperationProgress(
    val id: Long,
    val devicePath: String,
    val diskPath: String,
    val label: String,
    val kind: VolumeOperationKind,
    val stage: VolumeOperationStage = VolumeOperationStage.PREPARING,
    val outcome: VolumeOperationOutcome = VolumeOperationOutcome.RUNNING,
    val error: String? = null
)

/** In-memory observations only; never drives storage operations or acquires their locks. */
class VolumeOperationTracker(private val retainedResults: Int = 16) {
    private val monitor = Any()
    private var nextId = 0L
    private val mutableStates = MutableStateFlow<Map<String, VolumeOperationProgress>>(emptyMap())
    val states: StateFlow<Map<String, VolumeOperationProgress>> = mutableStates.asStateFlow()

    inner class Handle internal constructor(private val path: String, private val id: Long) {
        fun stage(stage: VolumeOperationStage) = update(path, id) { it.copy(stage = stage) }
        fun label(label: String) = update(path, id) { it.copy(label = label) }
        fun finish(outcome: VolumeOperationOutcome, error: String? = null) {
            require(outcome != VolumeOperationOutcome.RUNNING)
            update(path, id) { it.copy(outcome = outcome, error = error) }
        }
    }

    fun begin(path: String, diskPath: String, kind: VolumeOperationKind): Handle = synchronized(monitor) {
        val id = ++nextId
        publish(mutableStates.value + (path to VolumeOperationProgress(id, path, diskPath, path, kind)))
        Handle(path, id)
    }

    fun dismiss(path: String, id: Long) = synchronized(monitor) {
        val current = mutableStates.value[path]
        if (current?.id == id && current.outcome != VolumeOperationOutcome.RUNNING) {
            // The UI coalesces a batch by physical disk. Dismissing its latest result
            // must not reveal older partition results from the same batch.
            publish(mutableStates.value.filterValues {
                it.diskPath != current.diskPath || it.id > id || it.outcome == VolumeOperationOutcome.RUNNING
            })
        }
    }

    private fun update(path: String, id: Long, transform: (VolumeOperationProgress) -> VolumeOperationProgress) =
        synchronized(monitor) {
            val current = mutableStates.value[path] ?: return@synchronized
            if (current.id != id || current.outcome != VolumeOperationOutcome.RUNNING) return@synchronized
            publish(mutableStates.value + (path to transform(current)))
        }

    private fun publish(states: Map<String, VolumeOperationProgress>) {
        val discard = states.values.filter { it.outcome != VolumeOperationOutcome.RUNNING }
            .sortedByDescending { it.id }.drop(retainedResults.coerceAtLeast(0)).map { it.devicePath }.toSet()
        mutableStates.value = states.filterKeys { it !in discard }
    }
}
