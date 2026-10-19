package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.DeepSeekWebConfig

@Composable
internal fun WebSearchSettings(
    settings: AppSettings,
    externalRevision: Int,
    onOpenBlacklist: () -> Unit,
    onOpenDeepSeekSearch: () -> Unit,
    onOpenDeepSeekFetch: () -> Unit,
) {
    val search = remember(externalRevision) { settings.deepSeekSearchConfig }
    val fetch = remember(externalRevision) { settings.deepSeekFetchConfig }
    KimiCardBox {
        KimiMenuRow(
            Icons.Default.Search, uiText(R.string.deepseek_search_title),
            uiText(if (search.enabled) R.string.deepseek_backend_enabled else R.string.deepseek_backend_disabled),
            onClick = onOpenDeepSeekSearch,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.Description, uiText(R.string.deepseek_fetch_title),
            uiText(if (fetch.enabled) R.string.deepseek_backend_enabled else R.string.deepseek_backend_disabled),
            onClick = onOpenDeepSeekFetch,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.Block, uiText(R.string.title_domain_blacklist),
            uiText(R.string.web_search_blacklist_scope), onClick = onOpenBlacklist,
        )
    }
}

@Composable
internal fun DeepSeekWebSettings(
    settings: AppSettings,
    search: Boolean,
    externalRevision: Int,
    onChanged: () -> Unit,
) {
    val config = remember(search, externalRevision) {
        if (search) settings.deepSeekSearchConfig else settings.deepSeekFetchConfig
    }
    var baseUrl by rememberSaveable(search, config.baseUrl) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(search, config.apiKey) { mutableStateOf(config.apiKey) }
    var saved by remember(search) { mutableStateOf(false) }
    var invalidKey by remember(search) { mutableStateOf(false) }
    var invalidUrl by remember(search) { mutableStateOf(false) }
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(if (search) R.string.deepseek_enable_search else R.string.deepseek_enable_fetch), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiText(if (search) R.string.deepseek_search_description else R.string.deepseek_fetch_description),
                    color = KimiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = config.enabled && config.isConfigured,
                enabled = config.isConfigured,
                onCheckedChange = { enabled ->
                    val current = if (search) settings.deepSeekSearchConfig else settings.deepSeekFetchConfig
                    if (search) settings.deepSeekSearchConfig = current.copy(enabled = enabled)
                    else settings.deepSeekFetchConfig = current.copy(enabled = enabled)
                    onChanged()
                },
            )
        }
        KimiDivider()
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; invalidUrl = false; saved = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.label_base_url)) },
            placeholder = { Text(DeepSeekWebConfig.DEFAULT_BASE_URL) },
            isError = invalidUrl,
            singleLine = true,
        )
        if (invalidUrl) Text(uiText(R.string.deepseek_invalid_url), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; invalidKey = false; saved = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            isError = invalidKey,
            singleLine = true,
        )
        if (invalidKey) Text(uiText(R.string.deepseek_key_required), color = MaterialTheme.colorScheme.error)
        Text(uiText(R.string.deepseek_config_hint), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                val current = if (search) settings.deepSeekSearchConfig else settings.deepSeekFetchConfig
                val updated = current.copy(baseUrl = baseUrl, apiKey = apiKey).normalized()
                invalidKey = updated.apiKey.any { it.code !in 32..126 }
                invalidUrl = runCatching { updated.endpoint(if (search) "search" else "fetch") }.isFailure
                if (!invalidKey && !invalidUrl) {
                    if (search) settings.deepSeekSearchConfig = updated else settings.deepSeekFetchConfig = updated
                    baseUrl = updated.baseUrl
                    apiKey = updated.apiKey
                    saved = true
                    onChanged()
                }
            },
            shape = KimiPillShape,
        ) { Text(uiText(R.string.file_editor_save)) }
        if (saved) Text(uiText(R.string.deepseek_config_saved), color = MaterialTheme.colorScheme.primary)
    }
}
