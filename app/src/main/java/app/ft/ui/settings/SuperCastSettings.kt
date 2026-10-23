package app.ft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.ft.FTApp
import app.ft.R
import app.ft.supercast.Helper
import app.ft.supercast.ShizukuBridge
import app.ft.supercast.SuperCast
import app.ft.ui.components.AppChecklist
import app.ft.ui.components.Option
import app.ft.ui.components.OptionRow
import app.ft.ui.components.PickSetting
import app.ft.ui.components.rememberRootGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SuperCastSettings() {
    val context = LocalContext.current
    val prefs = FTApp.instance.prefs
    val scope = rememberCoroutineScope()
    val root = rememberRootGranted()
    val ready by SuperCast.ready.collectAsState()
    var onCar by remember { mutableStateOf(prefs.supercastOn) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableIntStateOf(-1) }

    fun setUp() {
        failed = false
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { SuperCast.start(context.applicationContext) }
            busy = false
            failed = !ok
        }
    }

    PickSetting(
        stringResource(R.string.apps_how),
        listOf(
            Option(stringResource(R.string.apps_mirror), stringResource(R.string.apps_mirror_about)),
            Option(stringResource(R.string.apps_run), stringResource(R.string.apps_run_about))
        ),
        if (onCar) 1 else 0
    ) { index ->
        onCar = index == 1
        prefs.supercastOn = onCar
        if (onCar) setUp() else scope.launch { withContext(Dispatchers.IO) { SuperCast.stopAll() } }
    }

    if (!onCar) {
        BoolSetting(stringResource(R.string.apps_car_size), prefs.carSizedApps) { prefs.carSizedApps = it }
        Text(
            stringResource(R.string.apps_car_size_about),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Status(ready, busy)
    if (!ready && !busy) {
        FilledTonalButton(onClick = { setUp() }) { Text(stringResource(R.string.apps_setup_now)) }
    }
    if (failed && !ready) {
        Text(stringResource(R.string.apps_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    BoolSetting(stringResource(R.string.supercast_screen_off), prefs.supercastScreenOff, stringResource(R.string.supercast_screen_off_about)) { prefs.supercastScreenOff = it }
    var chosen by remember { mutableStateOf(prefs.supercastApps.split(',').filter { it.isNotBlank() }.toSet()) }
    var picking by remember { mutableStateOf(false) }
    OptionRow(
        stringResource(R.string.supercast_which_apps),
        if (chosen.isEmpty()) stringResource(R.string.supercast_all_apps) else stringResource(R.string.supercast_some_apps, chosen.size),
        detail = stringResource(R.string.supercast_which_about)
    ) { picking = true }
    if (picking) {
        AppChecklist(chosen, onDismiss = { picking = false }) { now ->
            chosen = now
            prefs.supercastApps = now.joinToString(",")
        }
    }

    Text(stringResource(R.string.supercast_ways), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Way(
        index = 0,
        open = open,
        onOpen = { open = if (open == 0) -1 else 0 },
        title = stringResource(R.string.supercast_way_root),
        body = stringResource(R.string.supercast_way_root_body),
        best = root
    )
    Way(
        index = 1,
        open = open,
        onOpen = { open = if (open == 1) -1 else 1 },
        title = stringResource(R.string.supercast_way_wireless),
        body = stringResource(R.string.supercast_way_wireless_body),
        best = !root
    )
    Way(
        index = 2,
        open = open,
        onOpen = { open = if (open == 2) -1 else 2 },
        title = stringResource(R.string.supercast_way_shizuku),
        body = stringResource(R.string.supercast_way_shizuku_body)
    ) {
        var shizukuState by remember { mutableStateOf(ShizukuBridge.state(context)) }
        // Same pattern as the "Set it up now" button above: the button itself disappears
        // while the action is in flight and a spinner takes its place, so a tap always
        // gets visible feedback instead of looking like it did nothing for the second or
        // two Shizuku actually takes to come up after the broadcast.
        var startingShizuku by remember { mutableStateOf(false) }
        var stoppingShizuku by remember { mutableStateOf(false) }
        LaunchedEffect(open) {
            // The card's own state snapshot goes stale the moment you tap Start/Stop/Allow
            // inside it - poll while it's open so the buttons below update themselves
            // instead of needing the card closed and reopened to see what changed.
            while (open == 2) {
                shizukuState = ShizukuBridge.state(context)
                if (shizukuState != ShizukuBridge.State.NOT_RUNNING) startingShizuku = false
                if (shizukuState != ShizukuBridge.State.READY && shizukuState != ShizukuBridge.State.UNAUTHORIZED) stoppingShizuku = false
                delay(1000)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (startingShizuku || stoppingShizuku) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        if (shizukuState == ShizukuBridge.State.READY) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Text(
                when {
                    startingShizuku -> stringResource(R.string.supercast_way_shizuku_starting)
                    stoppingShizuku -> stringResource(R.string.supercast_way_shizuku_stopping)
                    shizukuState == ShizukuBridge.State.READY -> stringResource(R.string.supercast_way_shizuku_ready)
                    shizukuState == ShizukuBridge.State.UNAUTHORIZED -> stringResource(R.string.supercast_way_shizuku_unauthorized)
                    shizukuState == ShizukuBridge.State.NOT_RUNNING -> stringResource(R.string.supercast_way_shizuku_not_running)
                    else -> stringResource(R.string.supercast_way_shizuku_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!startingShizuku && shizukuState == ShizukuBridge.State.NOT_RUNNING) {
            TextButton(onClick = { startingShizuku = true; ShizukuBridge.start(context) }) { Text(stringResource(R.string.supercast_way_shizuku_start)) }
        }
        if (shizukuState == ShizukuBridge.State.UNAUTHORIZED) {
            TextButton(onClick = { ShizukuBridge.requestPermission() }) { Text(stringResource(R.string.supercast_way_shizuku_authorize)) }
        }
        if (!stoppingShizuku && (shizukuState == ShizukuBridge.State.READY || shizukuState == ShizukuBridge.State.UNAUTHORIZED)) {
            // For after a drive: apps on the car keep running without Shizuku, so this is
            // just "I'm done, put Shizuku away" - no need to dig into Shizuku's own UI.
            TextButton(onClick = { stoppingShizuku = true; SuperCast.quickStop(context) }) { Text(stringResource(R.string.supercast_way_shizuku_stop)) }
        }
        Spacer(Modifier.height(6.dp))
        BoolSetting(stringResource(R.string.supercast_auto_start), prefs.supercastAutoStart, stringResource(R.string.supercast_auto_start_about)) { prefs.supercastAutoStart = it }
        BoolSetting(stringResource(R.string.supercast_auto_stop), prefs.supercastAutoStop, stringResource(R.string.supercast_auto_stop_about)) { prefs.supercastAutoStop = it }
    }
    Way(
        index = 3,
        open = open,
        onOpen = { open = if (open == 3) -1 else 3 },
        title = stringResource(R.string.supercast_way_computer),
        body = stringResource(R.string.supercast_way_computer_body)
    ) {
        val command = remember { Helper.computerCommand(SuperCast.token) }
        Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("FT", command))
            Toast.makeText(context, context.getString(R.string.supercast_copy_command), Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.supercast_copy_command)) }
    }
}

@Composable
private fun Status(ready: Boolean, busy: Boolean) {
    val colour = when {
        ready -> MaterialTheme.colorScheme.primary
        busy -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colour)
        else Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(
                when {
                    ready -> R.string.apps_ready_now
                    busy -> R.string.apps_setting_up
                    else -> R.string.supercast_not_ready
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = colour
        )
    }
}

@Composable
private fun Way(
    index: Int,
    open: Int,
    onOpen: () -> Unit,
    title: String,
    body: String,
    best: Boolean = false,
    extra: @Composable (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (best) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            AnimatedVisibility(visible = open == index) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (extra != null) {
                        Spacer(Modifier.height(6.dp))
                        extra()
                    }
                }
            }
        }
    }
}
