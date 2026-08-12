package dev.imian.herald.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.imian.herald.data.DeliveryState
import dev.imian.herald.data.StoredMessageEvent
import dev.imian.herald.notification.HeraldNotificationListener
import dev.imian.herald.status.ListenerRuntimeStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            HeraldTheme(darkTheme = isSystemInDarkTheme()) {
                HeraldScreen(
                    viewModel = viewModel,
                    openNotificationAccess = ::openNotificationAccess,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, HeraldNotificationListener::class.java)
        val detailIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString(),
            )
        } else {
            null
        }
        val fallbackIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        try {
            startActivity(detailIntent ?: fallbackIntent)
        } catch (_: RuntimeException) {
            startActivity(fallbackIntent)
        }
    }
}

@Composable
private fun HeraldScreen(
    viewModel: MainViewModel,
    openNotificationAccess: () -> Unit,
) {
    val draft by viewModel.settingsDraft.collectAsStateWithLifecycle()
    val accessGranted by viewModel.isAccessGranted.collectAsStateWithLifecycle()
    val runtimeStatus by viewModel.listenerStatus.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 20.dp,
                end = 20.dp,
                bottom = 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AppHeader()
            }
            item {
                AccessCard(
                    accessGranted = accessGranted,
                    runtimeStatus = runtimeStatus,
                    onOpenSettings = openNotificationAccess,
                )
            }
            item {
                SettingsCard(
                    draft = draft,
                    isBusy = isBusy,
                    notice = notice,
                    onWebhookChange = viewModel::updateWebhookUrl,
                    onTokenChange = viewModel::updateBearerToken,
                    onClearToken = viewModel::clearBearerToken,
                    onPackagesChange = viewModel::updateAllowedPackages,
                    onAllowHttpChange = viewModel::updateAllowInsecureLocalHttp,
                    onSave = viewModel::saveSettings,
                    onDismissNotice = viewModel::dismissNotice,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        SectionLabel("RECENT EVENTS")
                        Text(
                            text = "최근 수집 ${events.size}건",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (events.any { it.deliveryState == DeliveryState.FAILED }) {
                            OutlinedButton(onClick = viewModel::retryFailed) {
                                Text("실패 재시도")
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::clearEvents,
                            enabled = events.isNotEmpty(),
                        ) {
                            Text("기록 삭제")
                        }
                    }
                }
            }
            if (events.isEmpty()) {
                item {
                    EmptyEventsCard()
                }
            } else {
                items(events, key = StoredMessageEvent::id) { event ->
                    EventCard(event)
                }
            }
            item {
                LimitationsNote()
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(HeraldGold, CircleShape),
            )
            Text(
                text = "  HERALD",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "알림을 메시지로,\n메시지를 원하는 곳으로.",
            fontSize = 31.sp,
            lineHeight = 37.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = "카카오톡을 기본으로 수집하고 표준 JSON 웹훅으로 전달합니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AccessCard(
    accessGranted: Boolean,
    runtimeStatus: ListenerRuntimeStatus,
    onOpenSettings: () -> Unit,
) {
    val connected = accessGranted && runtimeStatus.isConnected
    SectionCard {
        SectionLabel("01 · NOTIFICATION ACCESS")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                StatusPill(
                    label = when {
                        connected -> "연결됨"
                        accessGranted -> "권한 허용됨 · 연결 대기"
                        else -> "권한 필요"
                    },
                    active = connected,
                )
                Text(
                    text = when {
                        connected -> "새 알림을 받을 준비가 됐습니다."
                        accessGranted -> "Android가 리스너를 다시 연결하면 자동으로 시작합니다."
                        else -> "알림 접근 설정에서 Herald를 허용해 주세요."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                runtimeStatus.lastEventAt?.let {
                    Text("마지막 수집 ${formatTimestamp(it)}", style = MaterialTheme.typography.labelSmall)
                }
                runtimeStatus.lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(onClick = onOpenSettings) {
                Text(if (accessGranted) "설정 열기" else "권한 연결")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    draft: SettingsDraft,
    isBusy: Boolean,
    notice: String?,
    onWebhookChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onClearToken: () -> Unit,
    onPackagesChange: (String) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    SectionCard {
        SectionLabel("02 · ROUTING")
        Text(
            text = "웹훅 전달",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "비워두면 메시지는 이 기기에만 기록됩니다. HTTPS가 기본입니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.webhookUrl,
            onValueChange = onWebhookChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Webhook URL") },
            placeholder = { Text("https://jarvis.example.com/hooks/herald") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.bearerToken,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (draft.hasStoredToken && draft.bearerToken.isEmpty()) {
                        "Bearer token · 저장됨 (입력 시 교체)"
                    } else {
                        "Bearer token · 선택"
                    },
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            singleLine = true,
        )
        if (draft.hasStoredToken) {
            TextButton(
                onClick = onClearToken,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("저장된 토큰 삭제")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAllowHttpChange(!draft.allowInsecureLocalHttp) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("로컬 HTTP 허용", fontWeight = FontWeight.Medium)
                Text(
                    "localhost·사설 IP만 허용하며 Bearer token은 사용할 수 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = draft.allowInsecureLocalHttp,
                onCheckedChange = onAllowHttpChange,
            )
        }
        HorizontalDivider()
        Text(
            text = "수집할 앱",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "정확한 Android 패키지명을 한 줄에 하나씩 입력합니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.allowedPackages,
            onValueChange = onPackagesChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Package allowlist") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 2,
            maxLines = 5,
        )
        notice?.let {
            Text(
                text = it,
                color = if (it.contains("올바르") || it.contains("필요") || it.contains("너무")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.clickable(onClick = onDismissNotice),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onSave,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (isBusy) "저장 중…" else "설정 저장")
        }
    }
}

@Composable
private fun EventCard(event: StoredMessageEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.sourceLabel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DeliveryBadge(event.deliveryState)
            }
            val identity = listOfNotNull(event.conversation, event.sender)
                .distinct()
                .joinToString(" · ")
            if (identity.isNotEmpty()) {
                Text(
                    text = identity,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = when {
                    event.text != null -> event.text
                    event.deliveryState == DeliveryState.DELIVERED -> "전달 완료 · 본문은 기기에서 삭제됨"
                    event.hasAttachment -> "첨부 메시지"
                    else -> "본문 없음"
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTimestamp(event.capturedAt), style = MaterialTheme.typography.labelSmall)
                Text(
                    event.extractionMethod.wireValue,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            event.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DeliveryBadge(state: DeliveryState) {
    val (label, color) = when (state) {
        DeliveryState.LOCAL -> "LOCAL" to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryState.PENDING -> "PENDING" to HeraldGold
        DeliveryState.DELIVERED -> "SENT" to MaterialTheme.colorScheme.primary
        DeliveryState.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun EmptyEventsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("아직 수집한 메시지가 없습니다.", fontWeight = FontWeight.SemiBold)
            Text(
                "권한을 연결한 뒤 카카오톡 메시지를 받아 보세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LimitationsNote() {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SectionLabel("PRIVACY & LIMITS")
        Text(
            "전달 완료된 본문은 즉시 지웁니다. 전달 대기 항목은 자동 삭제하지 않고, 그 밖의 기록은 최대 500건·7일간 보관합니다. " +
                "미리보기가 숨겨진 메시지, Android 15에서 가려진 OTP, 제조사 절전 정책으로 놓친 알림은 복구할 수 없습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun StatusPill(label: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else HeraldGold
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm:ss")

private fun formatTimestamp(timestamp: Long): String = timestampFormatter.format(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()),
)
