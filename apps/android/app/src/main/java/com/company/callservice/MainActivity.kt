package com.company.callservice

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.callservice.data.SnapshotInfo
import com.company.callservice.ui.CompanyCallerIdTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as DirectoryApplication).graph)
    }
    private lateinit var roleManager: RoleManager
    private var roleAvailable by mutableStateOf(false)
    private var roleHeld by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var fullScreenIntentGranted by mutableStateOf(true)

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshSystemState()
        if (roleHeld) requestNotificationPermission()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshSystemState()
    }

    private val fullScreenIntentSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshSystemState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roleManager = getSystemService(RoleManager::class.java)
        refreshSystemState()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompanyCallerIdTheme {
                MainScreen(
                    state = state,
                    roleAvailable = roleAvailable,
                    roleHeld = roleHeld,
                    notificationsGranted = notificationsGranted,
                    notificationPermissionRelevant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    fullScreenIntentGranted = fullScreenIntentGranted,
                    fullScreenIntentPermissionRelevant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    onRequestRole = ::requestScreeningRole,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onOpenFullScreenIntentSettings = ::openFullScreenIntentSettings,
                    onApiBaseUrlChanged = viewModel::updateApiBaseUrl,
                    onCountryCodeChanged = viewModel::updateCountryCallingCode,
                    onCallerIdEnabledChanged = viewModel::updateCallerIdEnabled,
                    onSaveSettings = viewModel::saveSettings,
                    onSaveToken = viewModel::saveToken,
                    onClearToken = viewModel::clearToken,
                    onSync = viewModel::syncDirectory,
                    onImportSample = viewModel::importBundledSample,
                    onClearSnapshot = viewModel::clearSnapshot,
                    onDismissMessage = viewModel::dismissMessage,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::roleManager.isInitialized) {
            refreshSystemState()
            viewModel.autoSyncDirectory()
        }
    }

    private fun requestScreeningRole() {
        if (!roleAvailable || roleHeld) return
        roleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsGranted
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val fullScreenSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:$packageName"),
        )
        val intent = if (fullScreenSettingsIntent.resolveActivity(packageManager) != null) {
            fullScreenSettingsIntent
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        }
        fullScreenIntentSettings.launch(intent)
    }

    private fun refreshSystemState() {
        roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
        roleHeld = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        fullScreenIntentGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
}

@Composable
private fun MainScreen(
    state: MainUiState,
    roleAvailable: Boolean,
    roleHeld: Boolean,
    notificationsGranted: Boolean,
    notificationPermissionRelevant: Boolean,
    fullScreenIntentGranted: Boolean,
    fullScreenIntentPermissionRelevant: Boolean,
    onRequestRole: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onApiBaseUrlChanged: (String) -> Unit,
    onCountryCodeChanged: (String) -> Unit,
    onCallerIdEnabledChanged: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onSync: () -> Unit,
    onImportSample: () -> Unit,
    onClearSnapshot: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var tokenInput by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "사내 발신자 확인",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "전화는 항상 즉시 허용하고, 기기에 저장된 직원 snapshot과 일치할 때만 이름과 조직을 표시합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(title = "1. Android 역할과 알림") {
                StatusLine("통화 스크리닝 지원", roleAvailable)
                StatusLine("통화 스크리닝 역할", roleHeld)
                if (notificationPermissionRelevant) {
                    StatusLine("발신자 알림 권한", notificationsGranted)
                }
                if (fullScreenIntentPermissionRelevant) {
                    StatusLine("전체화면 알림 허용", fullScreenIntentGranted)
                }
                Button(
                    onClick = onRequestRole,
                    enabled = roleAvailable && !roleHeld && !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (roleHeld) "통화 스크리닝 역할 설정됨" else "통화 스크리닝 역할 요청")
                }
                if (notificationPermissionRelevant && !notificationsGranted) {
                    OutlinedButton(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("알림 권한 요청")
                    }
                }
                if (fullScreenIntentPermissionRelevant && !fullScreenIntentGranted) {
                    OutlinedButton(
                        onClick = onOpenFullScreenIntentSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("전체화면 알림 설정 열기")
                    }
                }
            }

            SectionCard(title = "2. 디렉터리 API") {
                OutlinedTextField(
                    value = state.apiBaseUrl,
                    onValueChange = onApiBaseUrlChanged,
                    label = { Text("API 기본 URL") },
                    supportingText = { Text("HTTPS만 허용하며 /v1/directory를 자동으로 붙입니다.") },
                    placeholder = { Text("https://directory.example.com") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.defaultCountryCallingCode,
                    onValueChange = onCountryCodeChanged,
                    label = { Text("기본 국가번호 (+ 제외)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("수신 시 직원 정보 표시", fontWeight = FontWeight.Medium)
                        Text(
                            "끄더라도 전화 허용 동작은 유지됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.callerIdEnabled,
                        onCheckedChange = onCallerIdEnabledChanged,
                        enabled = !state.isBusy,
                    )
                }
                Button(
                    onClick = onSaveSettings,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("일반 설정 저장")
                }

                HorizontalDivider()
                Text(
                    text = if (state.tokenConfigured) "Bearer 토큰: 저장됨" else "Bearer 토큰: 없음",
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("새 Bearer 토큰") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val token = tokenInput
                        tokenInput = ""
                        onSaveToken(token)
                    },
                    enabled = !state.isBusy && tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("토큰 암호화 저장")
                }
                if (state.tokenConfigured) {
                    TextButton(
                        onClick = onClearToken,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("토큰 및 로컬 snapshot 삭제")
                    }
                }
            }

            SectionCard(title = "3. 로컬 snapshot") {
                SnapshotSummary(state.snapshotInfo)
                Button(
                    onClick = onSync,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isBusy) "작업 중…" else "API에서 동기화")
                }
                OutlinedButton(
                    onClick = onImportSample,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("네트워크 없이 내장 샘플 불러오기")
                }
                if (state.snapshotInfo != null) {
                    TextButton(
                        onClick = onClearSnapshot,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("로컬 snapshot 삭제")
                    }
                }
            }

            state.message?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(message)
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onDismissMessage) { Text("확인") }
                    }
                }
            }

            Text(
                text = "이 앱은 발신 번호를 직원 신원으로 보증하지 않습니다. 번호 위조 가능성을 고려해 참고 정보로만 사용하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, ready: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            text = if (ready) "준비됨" else "필요",
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SnapshotSummary(info: SnapshotInfo?) {
    if (info == null) {
        Text("저장된 디렉터리가 없습니다.", color = MaterialTheme.colorScheme.error)
        return
    }
    Text("${info.entryCount}건 · 버전 ${info.version}", fontWeight = FontWeight.Bold)
    Text(
        "서버 생성: ${info.generatedAt}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "마지막 확인: ${formatTimestamp(info.checkedAtEpochMillis)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatTimestamp(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("-")
