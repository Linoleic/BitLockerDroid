package com.bitlockerdroid.ui.volumes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitlockerdroid.R
import com.bitlockerdroid.service.VolumeOperationKind
import com.bitlockerdroid.service.VolumeOperationOutcome
import com.bitlockerdroid.service.VolumeOperationProgress
import com.bitlockerdroid.service.VolumeOperationStage
import com.bitlockerdroid.service.VolumeWriteState

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun VolumeOperationStatus(
    operation: VolumeOperationProgress,
    terminal: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    val kind = stringResource(
        if (operation.kind == VolumeOperationKind.CLOSE) R.string.volume_operation_close
        else R.string.volume_operation_eject
    )
    val stage = stringResource(
        when (operation.stage) {
            VolumeOperationStage.PREPARING -> R.string.volume_operation_stage_preparing
            VolumeOperationStage.WAITING_FOR_WRITES -> R.string.volume_operation_stage_waiting_writes
            VolumeOperationStage.STOPPING_SHARES -> R.string.volume_operation_stage_stopping_shares
            VolumeOperationStage.UNMOUNTING -> R.string.volume_operation_stage_unmounting
            VolumeOperationStage.SYNCING_VOLUME -> R.string.volume_operation_stage_syncing_volume
            VolumeOperationStage.CLOSING_VOLUME -> R.string.volume_operation_stage_closing_volume
            VolumeOperationStage.CHECKING_DISK -> R.string.volume_operation_stage_checking_disk
            VolumeOperationStage.SYNCING_DEVICE -> R.string.volume_operation_stage_syncing_device
            VolumeOperationStage.STOPPING_DEVICE -> R.string.volume_operation_stage_stopping_device
            VolumeOperationStage.SYNCING_AND_STOPPING_DEVICE -> R.string.volume_operation_stage_syncing_stopping_device
            VolumeOperationStage.FINALIZING -> R.string.volume_operation_stage_finalizing
        }
    )
    val outcome = if (!terminal) null else stringResource(
        when (operation.outcome) {
            VolumeOperationOutcome.VOLUME_CLOSED -> R.string.volume_operation_result_volume_closed
            VolumeOperationOutcome.DISK_BUSY -> R.string.volume_operation_result_disk_busy
            VolumeOperationOutcome.DISK_STOPPED -> R.string.volume_operation_result_disk_stopped
            VolumeOperationOutcome.FAILED -> if (operation.stage in setOf(
                VolumeOperationStage.SYNCING_DEVICE, VolumeOperationStage.STOPPING_DEVICE,
                VolumeOperationStage.SYNCING_AND_STOPPING_DEVICE
            )) R.string.volume_operation_result_stop_failed else R.string.volume_operation_result_failed
            VolumeOperationOutcome.RUNNING -> R.string.volume_operation_stage_preparing
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (operation.outcome == VolumeOperationOutcome.FAILED)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = if (terminal) stringResource(R.string.volume_operation_result_title, operation.label, outcome ?: "")
                    else stringResource(R.string.volume_operation_running_title, kind, operation.label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (terminal) stringResource(R.string.volume_operation_last_stage, stage)
                    else stringResource(R.string.volume_operation_current_stage, stage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            operation.error?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.volume_operation_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (terminal && onDismiss != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.volume_operation_dismiss)) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SafWriteStatus(snapshot: VolumeWriteState.Snapshot) {
    if (snapshot.activeCount == 0 && snapshot.syncingCount == 0 && snapshot.failure.isNullOrBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(stringResource(R.string.saf_write_status_title), style = MaterialTheme.typography.labelMedium)
            if (snapshot.activeCount > 0) Text(
                stringResource(R.string.saf_write_active_count, snapshot.activeCount),
                style = MaterialTheme.typography.bodySmall
            )
            if (snapshot.syncingCount > 0) Text(
                stringResource(R.string.saf_write_syncing_count, snapshot.syncingCount),
                style = MaterialTheme.typography.bodySmall
            )
            snapshot.failure?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.saf_write_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
