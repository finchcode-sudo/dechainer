package io.github.warleysr.dechainer.screens.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.warleysr.dechainer.R
import io.github.warleysr.dechainer.dhizuku.DhizukuStore

@Composable
fun DhizukuServerDialog(gate: RecoveryGate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DhizukuStore.isEnabled(context)) }
    var clients by remember { mutableStateOf(DhizukuStore.clients(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dhizuku_server)) },
        text = {
            Column {
                Text(stringResource(R.string.dhizuku_server_explanation), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.dhizuku_server_enable), fontWeight = FontWeight.Bold)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            gate.run {
                                DhizukuStore.setEnabled(context, value)
                                enabled = value
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dhizuku_clients_section), fontWeight = FontWeight.Bold)
                val granted = clients.filter { it.permissions.isNotEmpty() }
                if (granted.isEmpty()) {
                    Text(stringResource(R.string.dhizuku_no_clients), style = MaterialTheme.typography.bodyMedium)
                } else {
                    granted.forEach { client ->
                        val label = try {
                            val pm = context.packageManager
                            pm.getApplicationInfo(client.packageName, 0).loadLabel(pm).toString()
                        } catch (e: Exception) {
                            client.packageName
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label)
                                Text(client.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                gate.run {
                                    DhizukuStore.remove(context, client.uid)
                                    clients = DhizukuStore.clients(context)
                                }
                            }) { Text(stringResource(R.string.dhizuku_revoke)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
