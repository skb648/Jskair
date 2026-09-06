package com.aircontrol.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircontrol.nativeinput.HidHostInfo
import com.aircontrol.nativeinput.NativeHidMouseState
import com.aircontrol.ui.Dimens

/**
 * HID POC (Phase 1): experimental Native HID Mouse card. Opt-in switch, live
 * controller status, and a bonded-host picker for the receiver device.
 * Completely isolated from the existing control-path settings — flipping
 * anything here can never change the accessibility control behavior while the
 * switch is OFF (default).
 */
@androidx.compose.runtime.Composable
internal fun NativeHidMouseCard(viewModel: SettingsViewModel, enabled: Boolean) {
    val context = LocalContext.current
    val status by viewModel.nativeHidStatus.collectAsStateWithLifecycle()
    var hosts by remember { mutableStateOf(emptyList<HidHostInfo>()) }

    // Android 12+ requires the runtime BLUETOOTH_CONNECT permission before any
    // HID registration / host listing; older versions grant it at install.
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.updateNativeHidMouseEnabled(true)
    }
    val needsBtPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingMedium),
        ) {
            Row {
                Text(
                    text = stringResource(R.string.settings_native_hid_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (want && needsBtPermission) {
                            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            viewModel.updateNativeHidMouseEnabled(want)
                        }
                    },
                )
            }
            Text(
                text = stringResource(R.string.settings_native_hid_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing8))
            Text(
                text = stringResource(R.string.settings_native_hid_state_prefix) + " " +
                    status.state.name + (status.reason?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.state == NativeHidMouseState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (enabled) {
                Spacer(modifier = Modifier.height(Dimens.spacing8))
                Text(
                    text = stringResource(R.string.settings_native_hid_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Dimens.spacing8))
                Row {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(stringResource(R.string.settings_native_hid_open_bt)) }
                    Spacer(modifier = Modifier.width(Dimens.spacing8))
                    OutlinedButton(onClick = { hosts = viewModel.bondedHidHosts() }) {
                        Text(stringResource(R.string.settings_native_hid_refresh_hosts))
                    }
                }
                hosts.forEach { host ->
                    Row {
                        Text(
                            text = host.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { viewModel.connectHidHost(host.address) }) {
                            Text(stringResource(R.string.settings_native_hid_connect))
                        }
                    }
                }
                if (status.state == NativeHidMouseState.CONNECTED) {
                    OutlinedButton(onClick = { viewModel.disconnectHidHost() }) {
                        Text(stringResource(R.string.settings_native_hid_disconnect))
                    }
                }
            }
        }
    }
}
