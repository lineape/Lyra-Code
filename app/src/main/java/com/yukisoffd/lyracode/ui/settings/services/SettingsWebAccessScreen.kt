package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.webaccess.WebAccessManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.yukisoffd.lyracode.workspace.SharedStorageWorkspace

@Composable
internal fun WebAccessSettings(settings: AppSettings, manager: WebAccessManager) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state = manager.state.value
    var port by remember { mutableStateOf(settings.webAccessPort.toString()) }
    var token by remember { mutableStateOf(settings.webAccessToken) }
    var lan by remember { mutableStateOf(settings.webAccessHost == "0.0.0.0") }
    KimiCardBox {
        Text(context.getString(R.string.web_access_title), style = MaterialTheme.typography.titleLarge)
        Text(context.getString(R.string.web_access_desc), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(context.getString(R.string.web_access_lan))
            Switch(checked = lan, enabled = !state.running, onCheckedChange = { lan = it; settings.webAccessHost = if (it) "0.0.0.0" else "127.0.0.1" })
        }
        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, enabled = !state.running,
            label = { Text(context.getString(R.string.web_access_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (state.running) manager.stop() else {
                val value = port.toIntOrNull()
                if (value != null && value in 1024..65535) { settings.webAccessPort = value; manager.start() }
            }
        }, enabled = state.running || port.toIntOrNull() in 1024..65535) {
            Text(context.getString(if (state.running) R.string.web_access_stop else R.string.web_access_start))
        }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (!Android17Compatibility.hasLocalNetworkAccess(context)) {
            TextButton(onClick = { context.findMainActivity()?.requestLocalNetworkPermission() }) { Text(context.getString(R.string.permission_local_network)) }
        }
        Text(context.getString(R.string.web_access_storage_desc),style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=android.os.Build.VERSION.SDK_INT>=30,onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))
        }) { Text(context.getString(R.string.web_access_storage_permission)) }
    }
    KimiCardBox {
        Text(context.getString(R.string.web_access_addresses), style = MaterialTheme.typography.titleMedium)
        state.addresses.forEach { address ->
            SelectionContainer { Text(address) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) { Text(context.getString(R.string.web_access_copy)) }
        }
        Text(context.getString(R.string.web_access_token), style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(token, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text(context.getString(R.string.web_access_copy)) }
            TextButton(onClick = { manager.stop(); token = settings.resetWebAccessToken() }) { Text(context.getString(R.string.web_access_reset_token)) }
        }
    }
    Text(context.getString(R.string.web_access_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
