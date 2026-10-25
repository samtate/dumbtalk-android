package dev.dumbtalk.app.sms

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.ui.*

@Composable
fun SmsSetupScreen(sms: SmsService, navigator: Navigator) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requester = remember { FocusRequester() }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() { sms.refresh(); revision++ }
    revision // Re-read system state after role/permission/lifecycle changes.
    val enabled = sms.enabled()
    val default = sms.isDefault()
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val notifications = notificationManager.areNotificationsEnabled() &&
        notificationManager.getNotificationChannel("dumbtalk-messages")?.importance != NotificationManager.IMPORTANCE_NONE
    val available = sms.available()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    fun requestPermissions() {
        val requested = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(requested.toTypedArray())
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (sms.isDefault()) requestPermissions()
        refresh()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { requester.focusAfterFrame() }
    Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { navigator.pop() })
    Column(Modifier.fillMaxSize()) {
        Header("SMS")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(if (enabled) "SMS is enabled" else "Use DumbTalk for SMS", style = Type.header)
            BasicText("Your existing text messages stay in Android's SMS database and appear alongside your other chats. Switching SMS apps keeps them.", style = Type.body)
            BasicText("Plain SMS only. MMS pictures, carrier group messages and RCS are not supported.", style = Type.small)
            when {
                !available -> BasicText("This device does not offer a default SMS app role.", style = Type.body)
                !enabled -> ListButton(if (default) "Allow SMS permissions" else "Make default SMS app", {
                    error = null
                    runCatching {
                        if (sms.isDefault()) requestPermissions()
                        else role.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
                    }.onFailure { error = it.message ?: "Could not open SMS setup" }
                }, focusRequester = requester, highlightFocus = true)
                else -> {
                    ListButton("SMS app settings", {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }, description = "Choose another default app to turn off SMS in DumbTalk", focusRequester = requester, highlightFocus = true)
                    if (!sms.hasPermission(Manifest.permission.READ_CONTACTS)) ListButton("Allow contact names", ::requestPermissions, highlightFocus = true)
                }
            }
            if (!notifications) {
                BasicText("SMS notifications are off. Enable them to receive alerts.", style = Type.body.copy(color = Palette.error))
                ListButton("Enable notifications", {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, highlightFocus = true)
            } else {
                BasicText("SMS alerts are delivered by Android even when DumbTalk's screen is closed. You can adjust sound and vibration in Android notification settings.", style = Type.small)
            }
            error?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
        }
    }
}
