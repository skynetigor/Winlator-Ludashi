package com.winlator.cmod.ui.container

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.lsfg.LsfgManager
import com.winlator.cmod.ui.settings.SettingChoice
import com.winlator.cmod.ui.settings.SettingToggle
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val multiplierEntries = listOf("2x", "3x", "4x")
private val flowScaleEntries = listOf("0.25", "0.50", "0.75", "0.80", "1.00")
private val presentModeEntries = listOf(LsfgManager.PRESENT_MODE_MAILBOX, LsfgManager.PRESENT_MODE_FIFO)

private fun formatFlowScale(value: Float) = String.format(Locale.US, "%.2f", value)

@Composable
internal fun ContainerLsfgCard(container: Container) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(LsfgManager.isEnabled(container)) }
    var dllSize by remember { mutableStateOf(dllSizeOf(container)) }
    var importing by remember { mutableStateOf(false) }
    var multiplier by remember { mutableStateOf("${LsfgManager.getMultiplier(container)}x") }
    var flowScale by remember { mutableStateOf(formatFlowScale(LsfgManager.getFlowScale(container))) }
    var performanceMode by remember { mutableStateOf(LsfgManager.isPerformanceMode(container)) }
    var presentMode by remember { mutableStateOf(LsfgManager.getPresentMode(container)) }

    val dllPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { LsfgManager.importDll(context, container, uri) }
            importing = false
            dllSize = dllSizeOf(container)
            Toast.makeText(context, error ?: "Lossless.dll imported", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsCard {
        Text(
            "Frame Generation (LSFG)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        SettingsDivider()
        SettingToggle("Enable Frame Generation", enabled) {
            enabled = it; LsfgManager.setEnabled(container, it)
        }
        SettingsDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lossless.dll", style = MaterialTheme.typography.bodyLarge)
                Text(
                    dllSize?.let { "Imported · $it" } ?: "Not imported",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dllSize != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            if (dllSize != null) {
                TextButton(enabled = !importing, onClick = {
                    LsfgManager.removeDll(container); dllSize = dllSizeOf(container)
                }) { Text("Remove") }
                Spacer(Modifier.width(4.dp))
            }
            OutlinedButton(enabled = !importing, onClick = { dllPicker.launch(arrayOf("*/*")) }) {
                Text(if (importing) "Importing…" else if (dllSize != null) "Replace" else "Import")
            }
        }
        if (enabled && dllSize == null) {
            Text(
                "Frame generation stays off until you import Lossless.dll from your own Lossless Scaling install (Steam).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        SettingsDivider()
        SettingChoice("Multiplier", multiplier, multiplierEntries) {
            multiplier = it; LsfgManager.setMultiplier(container, it.removeSuffix("x").toInt())
        }
        SettingsDivider()
        SettingChoice("Flow Scale", flowScale, flowScaleEntries) {
            flowScale = it; LsfgManager.setFlowScale(container, it.toFloat())
        }
        SettingsDivider()
        SettingToggle("Performance Mode", performanceMode) {
            performanceMode = it; LsfgManager.setPerformanceMode(container, it)
        }
        SettingsDivider()
        SettingChoice("Present Mode", presentMode, presentModeEntries) {
            presentMode = it; LsfgManager.setPresentMode(container, it)
        }
        Text(
            "Uses the lsfg-vk Vulkan layer, so it applies to DXVK, VKD3D, Vulkan and Zink games. Changes take effect on the next launch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

private fun dllSizeOf(container: Container): String? {
    if (!LsfgManager.hasDll(container)) return null
    return String.format(Locale.US, "%.1f MB", LsfgManager.getDllFile(container).length() / 1048576.0)
}
