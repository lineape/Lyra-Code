package com.yukisoffd.lyracode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SponsorDevelopmentScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) {
            scope.launch {
                saving = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                        output.use { destination ->
                            context.resources.openRawResource(R.drawable.sponsor_wechat_code).use { source ->
                                source.copyTo(destination)
                            }
                        }
                    }
                    notice = context.getString(R.string.sponsor_save_success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = context.getString(R.string.sponsor_save_failed)
                } finally {
                    saving = false
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KimiCardBox {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AboutIconBadge(Icons.Default.Favorite)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.sponsor_development_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.sponsor_thanks),
                            style = MaterialTheme.typography.bodySmall,
                            color = KimiMuted,
                        )
                    }
                }
            }
            KimiCardBox {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.sponsor_wechat_code),
                        contentDescription = stringResource(R.string.sponsor_code_description),
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        stringResource(R.string.sponsor_scan_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = KimiMuted,
                    )
                    Button(
                        onClick = { saveImage.launch("Lyra-Code-WeChat-Sponsor.jpg") },
                        enabled = !saving,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sponsor_save_code))
                    }
                }
            }
        }
        TransientNotice(
            message = notice,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            onDismiss = { notice = "" },
        )
    }
}
