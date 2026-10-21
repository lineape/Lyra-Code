package com.yukisoffd.lyracode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ChatGptAccount
import com.yukisoffd.lyracode.data.ChatGptProtocol
import com.yukisoffd.lyracode.data.ChatGptUsageWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ChatGptAccountPage(
    settings: AppSettings,
    onServicesChanged: () -> Unit,
    onBeforeSignOut: (String) -> Unit,
    selectedAccountId: String,
    onSelectAccount: (String) -> Unit,
    onOpenSignIn: (String) -> Unit,
    initialNotice: String,
    initialNoticeFailed: Boolean,
    onNoticeConsumed: () -> Unit,
) {
    val client = remember(settings) { settings.chatGptAccount }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accountRevision by client.accountsRevision.collectAsState()
    var accounts by remember { mutableStateOf<List<ChatGptAccount>>(emptyList()) }
    var windows by remember(selectedAccountId) { mutableStateOf<List<ChatGptUsageWindow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(initialNotice) }
    var failed by remember { mutableStateOf(initialNoticeFailed) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    val selected = accounts.firstOrNull { it.clientId == selectedAccountId }
    LaunchedEffect(Unit) { onNoticeConsumed() }
    LaunchedEffect(revision, accountRevision) {
        try {
            accounts = withContext(Dispatchers.IO) { client.accounts() }
            if (accounts.none { it.clientId == selectedAccountId })
                onSelectAccount(accounts.firstOrNull { it.isLoggedIn }?.clientId ?: accounts.firstOrNull()?.clientId.orEmpty())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true; notice = context.getString(R.string.chatgpt_storage_unavailable) }
        finally { loaded = true }
    }
    LaunchedEffect(revision, selectedAccountId, accountRevision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    withContext(Dispatchers.IO) { client.refreshUsageIfStale(selectedAccountId) }
                    windows = withContext(Dispatchers.IO) { client.usage(selectedAccountId) }
                    accounts = withContext(Dispatchers.IO) { client.accounts() }
                    now = System.currentTimeMillis()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { failed = true; notice = context.getString(R.string.chatgpt_storage_unavailable) }
                delay(60_000)
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    DisposableEffect(Unit) { onDispose { actionJob?.cancel() } }
    fun runAction(action: suspend () -> String) {
        if (busy) return
        busy = true
        failed = false
        notice = ""
        actionJob = scope.launch {
            try { notice = action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed = true; notice = error.message ?: context.getString(R.string.chatgpt_request_failed) }
            finally { busy = false; revision++; onServicesChanged() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatGptLogo(Modifier.size(42.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
            Text(uiText(R.string.chatgpt_provided_by), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
        }
    }
    if (busy || !loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall)
    if (accounts.size > 1) KimiCardBox {
        Text(uiText(R.string.chatgpt_saved_accounts), style = MaterialTheme.typography.titleSmall)
        accounts.forEach { account ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelectAccount(account.clientId) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedAccountId == account.clientId, enabled = !busy, onClick = { onSelectAccount(account.clientId) })
                Column(Modifier.weight(1f)) {
                    Text(account.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(uiText(when {
                        account.isLoggedIn && account.clientId == settings.selectedProfile().chatGptAccountId -> R.string.chatgpt_active_account
                        account.isLoggedIn -> R.string.chatgpt_connected
                        else -> R.string.chatgpt_signed_out
                    }), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
                }
            }
        }
    }
    if (selected != null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatGptIdentityCard(uiText(R.string.chatgpt_email_label), selected.email.ifBlank { uiText(R.string.chatgpt_email_unavailable) }, Modifier.weight(1f))
                ChatGptIdentityCard(uiText(R.string.chatgpt_plan_label), selected.plan.ifBlank { uiText(R.string.chatgpt_not_provided) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatGptAllowanceCard(selected, 300, windows.firstOrNull { it.minutes == 300 }, now, Modifier.weight(1f), noFiveHourLimit = selected.hasNoFiveHourLimit(windows))
                ChatGptAllowanceCard(selected, 10080, windows.firstOrNull { it.minutes == 10080 }, now, Modifier.weight(1f))
            }
            windows.filter { it.minutes !in setOf(300, 10080) }.forEach { window ->
                ChatGptAllowanceCard(selected, window.minutes, window, now, Modifier.fillMaxWidth())
            }
        }
        if (selected.isLoggedIn) {
            if (!selected.planEnabled) Text(uiText(R.string.chatgpt_plan_disabled), color = MaterialTheme.colorScheme.error)
            Button(enabled = !busy && selected.planEnabled, modifier = Modifier.fillMaxWidth(), onClick = { runAction {
                val profile = withContext(Dispatchers.IO) { synchronizeChatGptService(settings, selected) }
                context.getString(if (profile.savedModels.isEmpty()) R.string.notice_no_models_fetched else R.string.chatgpt_models_synced)
            } }) { Text(uiText(R.string.chatgpt_sync_and_use)) }
            OutlinedButton(enabled = !busy && selected.planEnabled, modifier = Modifier.fillMaxWidth(), onClick = { runAction {
                withContext(Dispatchers.IO) { client.refreshUsage(selected.clientId) }
                val profile = withContext(Dispatchers.IO) { synchronizeChatGptService(settings, selected, select = false) }
                context.getString(if (profile.savedModels.isEmpty()) R.string.notice_no_models_fetched else R.string.chatgpt_refreshed)
            } }) { Text(uiText(R.string.chatgpt_refresh)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { onOpenSignIn(selected.clientId) }) { Text(uiText(R.string.chatgpt_sign_in_again), textAlign = TextAlign.Center) }
                TextButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    onBeforeSignOut(selected.clientId)
                    runAction {
                        val revoked = withContext(Dispatchers.IO) { client.signOut(selected.clientId) }
                        context.getString(if (revoked) R.string.chatgpt_signed_out else R.string.chatgpt_revocation_unconfirmed)
                    }
                }) { Text(uiText(R.string.new_api_logout), color = MaterialTheme.colorScheme.error) }
            }
        } else Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { onOpenSignIn(selected.clientId) }) {
            Text(uiText(R.string.chatgpt_sign_in_again))
        }
        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
            runCatching { uriHandler.openUri(ChatGptProtocol.USAGE_URL) }.onFailure { failed = true; notice = context.getString(R.string.chatgpt_request_failed) }
        }) { Text(uiText(R.string.chatgpt_manage_usage)) }
    }
    if (loaded) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { onOpenSignIn("") }) {
        Icon(Icons.Default.Add, null, Modifier.padding(end = 8.dp).size(20.dp))
        Text(uiText(if (accounts.isEmpty()) R.string.chatgpt_continue else R.string.chatgpt_add_account))
    }
}

@Composable
private fun ChatGptIdentityCard(label: String, value: String, modifier: Modifier) {
    Card(modifier.heightIn(min = 124.dp).fillMaxHeight(), shape = KimiCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = KimiMuted)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChatGptAllowanceCard(account: ChatGptAccount, minutes: Int, window: ChatGptUsageWindow?, now: Long, modifier: Modifier, noFiveHourLimit: Boolean = false) {
    val label = chatGptWindowLabel(minutes)
    val unlimited = minutes == 300 && noFiveHourLimit
    val remaining = if (account.isLoggedIn) window?.remaining(now) else null
    val percentage = remaining?.let { String.format(Locale.getDefault(), if (it == it.toInt().toDouble()) "%.0f%%" else "%.1f%%", it) }
    val value = if (unlimited) "∞" else percentage ?: "—"
    val description = "$label: ${if (unlimited) uiText(R.string.chatgpt_window_not_applicable) else percentage ?: uiText(R.string.chatgpt_not_provided)}"
    val color = if (remaining != null && remaining < 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(modifier.heightIn(min = 236.dp).fillMaxHeight(), shape = KimiCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = KimiMuted, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(96.dp).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { ((remaining ?: 0.0) / 100).toFloat() }, modifier = Modifier.fillMaxSize(),
                    color = color, strokeWidth = 6.dp, strokeCap = StrokeCap.Round, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (remaining == null && !unlimited) KimiMuted else color)
            }
            Spacer(Modifier.height(18.dp))
            Spacer(Modifier.weight(1f))
            Text(if (unlimited) uiText(R.string.chatgpt_window_not_applicable) else uiText(R.string.chatgpt_next_reset,
                window?.resetsAt?.takeIf { account.isLoggedIn && it > now }?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                } ?: uiText(R.string.chatgpt_not_provided)),
                style = MaterialTheme.typography.bodySmall, color = KimiMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ChatGptInformationDialog(codex: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.chatgpt_information)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(uiText(R.string.chatgpt_provided_by), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.chatgpt_disclosure))
            Text(uiText(R.string.chatgpt_privacy))
            if (codex) Text(uiText(R.string.chatgpt_codex_disclosure))
            Text(uiText(if (codex) R.string.chatgpt_codex_usage_description else R.string.chatgpt_usage_unavailable))
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.action_close)) } })
}
