package com.alglucomon.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.AppTheme
import com.alglucomon.android.data.BrightnessMode
import com.alglucomon.android.data.DexcomClient
import com.alglucomon.android.data.DexcomRegion
import com.alglucomon.android.data.GlucoseUnit
import com.alglucomon.android.data.GraphStyle
import com.alglucomon.android.data.OverlaySize
import com.alglucomon.android.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    canDrawOverlays: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    currentLux: Float?,
    onBackToDisplay: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    var draftRegion by remember(settings.region) { mutableStateOf(settings.region) }
    var draftUsername by remember(settings.username) { mutableStateOf(settings.username) }
    var draftPassword by remember(settings.password) { mutableStateOf(settings.password) }
    var testing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var urgentLowDraft by remember(settings.urgentLowThreshold) {
        mutableStateOf(settings.urgentLowThreshold.toString())
    }
    var lowDraft by remember(settings.lowThreshold) {
        mutableStateOf(settings.lowThreshold.toString())
    }
    var highDraft by remember(settings.highThreshold) {
        mutableStateOf(settings.highThreshold.toString())
    }

    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFE2E8F0),
        focusedBorderColor = Color(0xFF38BDF8),
        unfocusedBorderColor = Color(0x33FFFFFF),
        focusedContainerColor = Color(0xFF0B101B),
        unfocusedContainerColor = Color(0xFF0B101B),
        focusedLabelColor = Color(0xFF38BDF8),
        unfocusedLabelColor = Color(0xFF94A3B8),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B101B))
            .systemBarsPadding()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "AlGlucoMon",
                        color = Color(0xFFF8FAFC),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Android 8+",
                        color = Color(0xFF7DD3FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x2838BDF8))
                            .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = "by Almoukhtar Amiouni — Display & Floating Overlay",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                )
            }
            Button(
                onClick = onBackToDisplay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFFF8FAFC),
                ),
            ) {
                Text("Display")
            }
        }

        SettingsCard(title = "Dexcom G7 Sign-In") {
            if (settings.isSignedIn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x142DD4BF))
                        .border(1.dp, Color(0x402DD4BF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2DD4BF))
                        )
                        Text(
                            text = "Signed in as ${settings.username}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.5.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            DexcomClient.clearSession()
                            SettingsRepository.update {
                                it.copy(username = "", password = "", clickThrough = false)
                            }
                            draftUsername = ""
                            draftPassword = ""
                            statusMsg = true to "Signed out."
                        }
                    ) {
                        Text("Sign Out", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                    }
                }
            }

            Text("Region", color = Color(0xFF94A3B8), fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DexcomRegion.entries.forEach { reg ->
                    ChoicePill(
                        label = reg.label,
                        selected = draftRegion == reg,
                        onClick = { draftRegion = reg },
                    )
                }
            }

            OutlinedTextField(
                value = draftUsername,
                onValueChange = { draftUsername = it },
                label = { Text("Primary Dexcom Username / Email") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draftPassword,
                onValueChange = { draftPassword = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = !testing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0EA5E9),
                        contentColor = Color(0xFF082F49),
                    ),
                    onClick = {
                        val cleanUser = draftUsername.trim()
                        if (cleanUser.isEmpty() || draftPassword.isEmpty()) {
                            statusMsg = false to "Enter your primary Dexcom username and password."
                            return@Button
                        }
                        testing = true
                        statusMsg = null
                        scope.launch {
                            try {
                                val msg = DexcomClient.testConnection(draftRegion, cleanUser, draftPassword)
                                SettingsRepository.update {
                                    it.copy(
                                        region = draftRegion,
                                        username = cleanUser,
                                        password = draftPassword,
                                    )
                                }
                                statusMsg = true to "Signed in! $msg"
                            } catch (t: Throwable) {
                                statusMsg = false to (t.message ?: "Connection failed")
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = when {
                            testing -> "Connecting..."
                            settings.isSignedIn -> "Update & Test Connection"
                            else -> "Sign In & Connect"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            statusMsg?.let { (ok, text) ->
                Text(
                    text = text,
                    color = if (ok) Color(0xFF2DD4BF) else Color(0xFFFDA4AF),
                    fontSize = 12.sp,
                )
            }

            Text("Refresh interval", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(60 to "60s", 120 to "2m", 300 to "5m").forEach { (sec, label) ->
                    ChoicePill(
                        label = label,
                        selected = settings.pollIntervalSec == sec,
                        onClick = { SettingsRepository.update { it.copy(pollIntervalSec = sec) } },
                    )
                }
            }

            Text(
                text = "Requires the wearer's primary Dexcom account (not a follower account), with Dexcom Share enabled and at least 1 active follower in the G7 phone app.",
                color = Color(0xFF94A3B8),
                fontSize = 11.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xB30F172A))
                    .padding(10.dp),
            )
        }

        SettingsCard(title = "Fullscreen Sugar Display") {
            ToggleRow(
                title = "Keep screen always on",
                subtitle = "Prevents tablet/phone screen sleep while Display is open",
                checked = settings.keepScreenOn,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(keepScreenOn = v) } },
            )

            Text(
                text = "Brightness mode" + (currentLux?.let { " (Sensor: ${it.roundToInt()} lux)" } ?: ""),
                color = Color(0xFFE2E8F0),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrightnessMode.entries.forEach { mode ->
                    ChoicePill(
                        label = mode.label,
                        selected = settings.brightnessMode == mode,
                        onClick = { SettingsRepository.update { it.copy(brightnessMode = mode) } },
                    )
                }
            }

            if (settings.brightnessMode == BrightnessMode.MANUAL) {
                SliderRow(
                    label = "Display brightness (${(settings.manualBrightness * 100).roundToInt()}%)",
                    value = settings.manualBrightness,
                    range = 0.03f..1.0f,
                    onValueChange = { v -> SettingsRepository.update { it.copy(manualBrightness = v) } },
                )
            } else {
                ToggleRow(
                    title = "Night low-glare auto-dim",
                    subtitle = "Adds gentle extra dimming in dark rooms (< 6 lux)",
                    checked = settings.nightAutoDim,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(nightAutoDim = v) } },
                )
            }

            ToggleRow(
                title = "Burn-in protection pixel shift",
                subtitle = "Subtly shifts readout every 90s for overnight use",
                checked = settings.burnInShift,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(burnInShift = v) } },
            )

            ToggleRow(
                title = "Show clock & ambient lux",
                checked = settings.showClock,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(showClock = v) } },
            )
        }

        SettingsCard(title = "Floating Overlay") {
            ToggleRow(
                title = "Enable floating overlay",
                subtitle = if (canDrawOverlays) {
                    "Floats over other apps; toggle via notification or Quick Tile"
                } else {
                    "Tap to grant 'Display over other apps' permission"
                },
                checked = settings.overlayEnabled && canDrawOverlays,
                onCheckedChange = { on -> onToggleOverlay(on) },
            )

            if (!canDrawOverlays) {
                OutlinedButton(onClick = onRequestOverlayPermission) {
                    Text("Grant Overlay Permission", color = Color(0xFF38BDF8))
                }
            }

            ToggleRow(
                title = "Click-through lock",
                subtitle = "Dims overlay & lets touches pass through. Unlock anytime from the notification.",
                checked = settings.clickThrough,
                enabled = settings.isSignedIn,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(clickThrough = v) } },
            )

            SliderRow(
                label = "Locked opacity (${(settings.clickThroughOpacity * 100).roundToInt()}%)",
                value = settings.clickThroughOpacity,
                range = 0.15f..0.75f,
                onValueChange = { v -> SettingsRepository.update { it.copy(clickThroughOpacity = v) } },
            )

            Text("Overlay size", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OverlaySize.entries.forEach { sz ->
                    ChoicePill(
                        label = sz.label,
                        selected = settings.overlaySize == sz,
                        onClick = { SettingsRepository.update { it.copy(overlaySize = sz) } },
                    )
                }
            }

            ToggleRow(
                title = "Start overlay on device boot",
                subtitle = "Automatically launches the floating overlay after restart",
                checked = settings.startOnBoot,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(startOnBoot = v) } },
            )

            if (!isBatteryOptimizationIgnored) {
                OutlinedButton(onClick = onRequestBatteryWhitelist) {
                    Text("Disable Battery Optimization (Recommended)", color = Color(0xFF2DD4BF), fontSize = 12.sp)
                }
            }
        }

        SettingsCard(title = "Background Trend Graph") {
            ToggleRow(
                title = "Show trend graph background",
                subtitle = "Renders your recent glucose curve behind the reading",
                checked = settings.showBackgroundGraph,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(showBackgroundGraph = v) } },
            )

            Column(
                modifier = Modifier.alpha(if (settings.showBackgroundGraph) 1f else 0.45f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Time window", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 3, 6).forEach { hrs ->
                        ChoicePill(
                            label = "${hrs}h",
                            selected = settings.graphHours == hrs,
                            onClick = {
                                if (settings.showBackgroundGraph) {
                                    SettingsRepository.update { it.copy(graphHours = hrs) }
                                }
                            },
                        )
                    }
                }

                Text("Graph style", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GraphStyle.entries.forEach { st ->
                        ChoicePill(
                            label = st.label,
                            selected = settings.graphStyle == st,
                            onClick = {
                                if (settings.showBackgroundGraph) {
                                    SettingsRepository.update { it.copy(graphStyle = st) }
                                }
                            },
                        )
                    }
                }

                if (settings.graphStyle == GraphStyle.DOTS) {
                    Text(
                        text = "Each dot represents a glucose reading — drag your finger across dots on the Display (or in Overlay inspect mode) to see its value and time.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.5.sp,
                    )
                }

                SliderRow(
                    label = "Graph opacity (${(settings.graphOpacity * 100).roundToInt()}%)",
                    value = settings.graphOpacity,
                    range = 0.15f..0.85f,
                    onValueChange = { v -> SettingsRepository.update { it.copy(graphOpacity = v) } },
                )

                ToggleRow(
                    title = "Show target range band",
                    checked = settings.showTargetBand,
                    onCheckedChange = { v -> SettingsRepository.update { it.copy(showTargetBand = v) } },
                )
            }
        }

        SettingsCard(title = "Appearance & Thresholds") {
            Text("Theme", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppTheme.entries.forEach { th ->
                    ChoicePill(
                        label = th.label,
                        selected = settings.theme == th,
                        onClick = { SettingsRepository.update { it.copy(theme = th) } },
                    )
                }
            }

            SliderRow(
                label = "Overlay card opacity (${(settings.cardOpacity * 100).roundToInt()}%)",
                value = settings.cardOpacity,
                range = 0.30f..1.0f,
                onValueChange = { v -> SettingsRepository.update { it.copy(cardOpacity = v) } },
            )

            Text("Units", color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlucoseUnit.entries.forEach { u ->
                    ChoicePill(
                        label = u.id,
                        selected = settings.unit == u,
                        onClick = { SettingsRepository.update { it.copy(unit = u) } },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThresholdField(
                    label = "Urgent Low",
                    value = urgentLowDraft,
                    onValueChange = { txt ->
                        urgentLowDraft = txt
                        txt.toIntOrNull()?.let { n ->
                            SettingsRepository.update { it.copy(urgentLowThreshold = n.coerceIn(40, 90)) }
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                ThresholdField(
                    label = "Low",
                    value = lowDraft,
                    onValueChange = { txt ->
                        lowDraft = txt
                        txt.toIntOrNull()?.let { n ->
                            SettingsRepository.update { it.copy(lowThreshold = n.coerceIn(50, 120)) }
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                ThresholdField(
                    label = "High",
                    value = highDraft,
                    onValueChange = { txt ->
                        highDraft = txt
                        txt.toIntOrNull()?.let { n ->
                            SettingsRepository.update { it.copy(highThreshold = n.coerceIn(120, 350)) }
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
            }

            ToggleRow(
                title = "Show delta & reading age",
                checked = settings.showDelta,
                onCheckedChange = { v -> SettingsRepository.update { it.copy(showDelta = v) } },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = {
            Text(
                text = title.uppercase(),
                color = Color(0xFF38BDF8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            content()
        },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(text = title, color = Color(0xFFE2E8F0), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 11.5.sp)
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF042F2E),
                checkedTrackColor = Color(0xFF2DD4BF),
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF2DD4BF),
                activeTrackColor = Color(0xFF2DD4BF),
            ),
        )
    }
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF1E293B) else Color(0xFF0B101B))
            .border(
                1.dp,
                if (selected) Color(0xFF38BDF8) else Color(0x18FFFFFF),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFFF8FAFC) else Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ThresholdField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    colors: androidx.compose.material3.TextFieldColors,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = colors,
        modifier = modifier,
    )
}
