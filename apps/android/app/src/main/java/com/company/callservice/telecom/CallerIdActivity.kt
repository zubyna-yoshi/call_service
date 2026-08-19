package com.company.callservice.telecom

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.company.callservice.ui.CompanyCallerIdTheme

class CallerIdActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }
    private var identity by mutableStateOf(CallerIdentity())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP
        }
        updateFromIntent(intent)

        setContent {
            CompanyCallerIdTheme {
                CallerOverlay(identity = identity, onDismiss = ::finish)
            }
        }
        scheduleFinish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFromIntent(intent)
        scheduleFinish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    private fun updateFromIntent(source: Intent) {
        identity = CallerIdentity(
            label = source.getStringExtra(CallerIdPresenter.EXTRA_LABEL).orEmpty().take(180),
            name = source.getStringExtra(CallerIdPresenter.EXTRA_NAME).orEmpty().take(100),
            organization = source.getStringExtra(CallerIdPresenter.EXTRA_ORGANIZATION).orEmpty().take(120),
            number = source.getStringExtra(CallerIdPresenter.EXTRA_NUMBER).orEmpty().take(32),
            numberType = source.getStringExtra(CallerIdPresenter.EXTRA_NUMBER_TYPE).orEmpty().take(40),
        )
        CallerIdPresenter.cancelNotification(
            this,
            source.getIntExtra(CallerIdPresenter.EXTRA_NOTIFICATION_ID, 0),
        )
    }

    private fun scheduleFinish() {
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, OVERLAY_LIFETIME_MILLIS)
    }

    private companion object {
        const val OVERLAY_LIFETIME_MILLIS = 12_000L
    }
}

private data class CallerIdentity(
    val label: String = "사내 전화",
    val name: String = "",
    val organization: String = "",
    val number: String = "",
    val numberType: String = "",
)

@Composable
private fun CallerOverlay(identity: CallerIdentity, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "사내 발신자",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = identity.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (identity.organization.isNotBlank() || identity.name.isNotBlank()) {
                    Text(
                        text = listOf(identity.organization, identity.name)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(identity.numberType, identity.number)
                        .filter(String::isNotBlank)
                        .joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "카드를 누르면 닫힙니다",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
