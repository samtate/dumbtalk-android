package dev.dumbtalk.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.Navigator
import dev.dumbtalk.app.Screen
import dev.dumbtalk.app.ui.CenteredMessage
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.ServiceIcon
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import dev.dumbtalk.core.SetupStep
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun ServicesScreen(app: DumbTalkApp, navigator: Navigator) {
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<ServiceStatus>?>(null) }
    var confirmDisconnect by remember { mutableStateOf<ServiceId?>(null) }
    // Focus returns to the row last acted on when the confirmation closes.
    var focusedService by remember { mutableStateOf<ServiceId?>(null) }
    val requester = remember { FocusRequester() }

    suspend fun refresh() {
        statuses = coroutineScope {
            app.registry.installed.map { service ->
                async { runCatching { service.status() }.getOrDefault(ServiceStatus(service.id, connected = false, ready = false)) }
            }.awaitAll()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        app.registry.events.filter { it is ServiceEvent.StatusChanged }.conflate().collect { refresh() }
    }

    LaunchedEffect(statuses != null, confirmDisconnect) {
        if (statuses == null || confirmDisconnect != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }

    val pending = confirmDisconnect
    if (pending != null) {
        Softkeys(center = SoftkeyAction("Select"), right = SoftkeyAction("Back") { confirmDisconnect = null })
        val confirmFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
        Column(Modifier.fillMaxSize()) {
            Header("Disconnect ${pending.label}?")
            CenteredMessage("This unlinks DumbTalk and deletes its ${pending.label} data from this phone.")
            ListButton("Keep connected", { confirmDisconnect = null }, focusRequester = confirmFocus)
            ListButton("Disconnect", {
                scope.launch {
                    runCatching { app.registry.byId(pending).disconnect() }
                    confirmDisconnect = null
                    refresh()
                }
            })
        }
        return
    }

    Softkeys(center = SoftkeyAction("Open"), right = SoftkeyAction("Back") { navigator.pop() })
    Column(Modifier.fillMaxSize()) {
        Header("Services")
        BasicText(
            "Connected services share one conversation list while keeping their own accounts.",
            style = Type.preview,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
        statuses?.forEach { status ->
            ListButton(
                status.id.label,
                onClick = {
                    focusedService = status.id
                    if (status.connected) confirmDisconnect = status.id else navigator.push(Screen.Setup(status.id))
                },
                description = status.accountLabel ?: if (status.connected) "Connected" else "Not connected",
                focusRequester = if (status.id == (focusedService ?: statuses!!.first().id)) requester else null,
                leading = { ServiceIcon(status.id, size = 28.dp) },
                trailing = {
                    BasicText(
                        if (status.connected) "On" else "Off",
                        style = Type.small.copy(fontWeight = FontWeight.Bold, color = if (status.connected) Color(0xFFCFEBDD) else Palette.muted),
                        modifier = Modifier
                            .background(if (status.connected) Color(0xFF24533F) else Palette.panel, RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                },
            )
        } ?: CenteredMessage("Loading…")
    }
}

@Composable
fun SetupScreen(app: DumbTalkApp, navigator: Navigator, serviceId: ServiceId) {
    val service = app.registry.byId(serviceId)
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<SetupStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember(step) { mutableStateOf("") }
    val requester = remember(step) { FocusRequester() }

    fun advance(value: String? = null) {
        val current = step ?: return
        busy = true
        error = null
        scope.launch {
            runCatching { service.advanceSetup(current, value) }
                .onSuccess { step = it }
                .onFailure { error = it.message ?: "Setup failed" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { service.beginSetup() }.onSuccess { step = it }.onFailure { error = it.message ?: "Setup failed" }
    }

    // Code steps complete on their own once the main phone links.
    LaunchedEffect(step) {
        val current = step
        if (current is SetupStep.Qr || current is SetupStep.PairCode) advance()
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }

    val current = step
    Softkeys(
        center = when {
            current is SetupStep.Input && !busy -> SoftkeyAction("Next") { if (input.isNotBlank()) advance(input.trim()) }
            current is SetupStep.Complete -> SoftkeyAction("Done") { navigator.pop(); navigator.pop() }
            current is SetupStep.Choice -> SoftkeyAction("Select")
            else -> null
        },
        right = SoftkeyAction("Back") { navigator.pop() },
    )

    Column(Modifier.fillMaxSize()) {
        Header(current?.title ?: "Connect ${serviceId.label}")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (current != null) BasicText(current.instructions, style = Type.body.copy(fontSize = 12.sp), modifier = Modifier.padding(horizontal = 9.dp))
            when (current) {
                null -> if (error == null) CenteredMessage("Starting…")
                is SetupStep.Choice -> current.choices.forEachIndexed { index, choice ->
                    ListButton(choice.label, { advance(choice.value) }, description = choice.description, focusRequester = if (index == 0) requester else null)
                }
                is SetupStep.Input -> TextInput(
                    input, { input = it },
                    placeholder = current.placeholder.orEmpty(),
                    focusRequester = requester,
                    password = current.field == SetupStep.Input.Field.PASSWORD,
                    keyboardType = when (current.field) {
                        SetupStep.Input.Field.PHONE -> KeyboardType.Phone
                        SetupStep.Input.Field.CODE, SetupStep.Input.Field.API_ID -> KeyboardType.Number
                        SetupStep.Input.Field.PASSWORD -> KeyboardType.Password
                        SetupStep.Input.Field.API_HASH -> KeyboardType.Ascii
                    },
                    onDone = { if (input.isNotBlank()) advance(input.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp),
                )
                is SetupStep.Qr -> OpenInApp(current.payload, requester)
                else -> Unit
            }
            when (current) {
                is SetupStep.Qr -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // As large as fits: dense WhatsApp codes need it for older cameras.
                    val width = LocalConfiguration.current.screenWidthDp
                    QrCode(current.payload, size = (width - 24).coerceIn(160, 320).dp)
                }
                is SetupStep.PairCode -> BasicText(
                    current.code,
                    style = Type.title.copy(fontSize = 24.sp, letterSpacing = 2.sp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                else -> Unit
            }
            if (current is SetupStep.Input) current.hint?.let { BasicText(it, style = Type.preview, modifier = Modifier.padding(horizontal = 9.dp)) }
            if (busy && current !is SetupStep.Qr && current !is SetupStep.PairCode) CenteredMessage("Working…")
            error?.let { CenteredMessage(it, color = Palette.error) }
        }
    }
}

/**
 * When the main app is on this same phone (with its notifications off), the
 * QR can't be scanned. If an installed app handles the QR's link — Telegram
 * for tg://login — open it directly. Signal and Molly deliberately refuse to
 * link from an opened sgnl:// link, so those need scanning from a screen.
 */
@Composable
private fun OpenInApp(payload: String, requester: FocusRequester) {
    val context = LocalContext.current
    val intent = remember(payload) { Intent(Intent.ACTION_VIEW, Uri.parse(payload)) }
    val handler = remember(payload) {
        if (!payload.contains("://") || payload.startsWith("sgnl:")) null
        else intent.resolveActivity(context.packageManager)?.let { component ->
            runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(component.packageName, 0)).toString()
            }.getOrNull()
        }
    } ?: return
    ListButton(
        "Link with $handler on this phone",
        { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        description = "Or scan the code below from another phone.",
        focusRequester = requester,
    )
}

/** QR codes need a quiet white border to scan reliably from another phone. */
@Composable
fun QrCode(payload: String, size: androidx.compose.ui.unit.Dp = 180.dp) {
    val matrix = remember(payload) { QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(size).background(Color.White).padding(10.dp)) {
        val cell = this.size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
