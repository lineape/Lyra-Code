package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ProviderKeysEditor(primary: String, secondary: List<String>, onChange: (String, List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderKeyField(value = primary, onValueChange = { onChange(it, secondary) }, modifier = Modifier.fillMaxWidth(),
            label = uiText(R.string.provider_primary_key))
        secondary.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderKeyField(value = value, onValueChange = { next ->
                    onChange(primary, secondary.mapIndexed { i, key -> if (i == index) next else key })
                }, modifier = Modifier.weight(1f), label = uiText(R.string.provider_secondary_key, index + 1))
                IconButton(onClick = { onChange(value, secondary.mapIndexed { i, key -> if (i == index) primary else key }) }) {
                    Icon(Icons.Default.ArrowUpward, uiText(R.string.provider_make_primary))
                }
                IconButton(onClick = { onChange(primary, secondary.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Default.Delete, uiText(R.string.provider_remove_key))
                }
            }
        }
        TextButton(onClick = { onChange(primary, secondary + "") }) { Text(uiText(R.string.provider_add_secondary)) }
        Text(uiText(R.string.provider_keys_hint), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProviderKeyField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    // Changing a key or moving a different key into this field always ends the previous reveal.
    val interactionSource = remember(value) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val revealed = pressed && LocalWindowInfo.current.isWindowFocused
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = {}, interactionSource = interactionSource) {
                Icon(if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = uiText(R.string.provider_hold_to_reveal_key))
            }
        },
    )
}
