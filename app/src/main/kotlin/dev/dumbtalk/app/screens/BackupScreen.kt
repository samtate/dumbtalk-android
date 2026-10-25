package dev.dumbtalk.app.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.DumbTalkApp
import dev.dumbtalk.app.backup.Backups
import dev.dumbtalk.app.ui.Header
import dev.dumbtalk.app.ui.ListButton
import dev.dumbtalk.app.ui.Palette
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.Softkeys
import dev.dumbtalk.app.ui.TextInput
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.focusAfterFrame
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs up to, or [restore]s from, an encrypted file the person picks with
 * Android's file picker. It runs in a restart with no service running (see
 * [DumbTalkApp.maintenance]); finishing or cancelling restarts normally.
 */
@Composable
fun BackupScreen(app: DumbTalkApp, restore: Boolean) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var media by rememberSaveable { mutableStateOf(true) }
    var file by rememberSaveable { mutableStateOf<Uri?>(null) }
    /** What's happening while it runs, then the result. */
    var working by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(file) { requester.focusAfterFrame() }
    val finish = { app.restartFor(activity, null) }

    fun run(task: suspend ((Long) -> Unit) -> String) {
        error = null
        working = "Starting…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    var shown = -1L
                    task { bytes -> (bytes shr 20).takeIf { it != shown }?.let { shown = it; working = "$it MB…" } }
                }
            }.onSuccess { done = it }.onFailure { error = it.message ?: "That didn't work" }
            working = null
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) run { progress ->
            val size = Backups.backUp(activity, uri, passphrase.toCharArray(), media, progress)
            "Saved ${size shr 20} MB. Keep the passphrase safe: without it this backup can't be opened."
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) file = uri }

    Softkeys(
        center = when {
            done != null -> SoftkeyAction("Finish", finish)
            working != null -> SoftkeyAction("Working")
            else -> SoftkeyAction("Select")
        },
        right = if (working == null && done == null) SoftkeyAction("Cancel", finish) else null,
    )
    Column(Modifier.fillMaxSize()) {
        Header(if (restore) "Restore" else "Back up")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                done != null -> Hint(done!!)
                working != null -> BasicText(working!!, style = Type.body)
                restore -> {
                    Table {
                        Cell("⚠", "Replaces everything in DumbTalk on this phone")
                        Cell("⚠", "Remove DumbTalk from the old phone: a linked device can't run on two")
                        Cell("✗", "SMS isn't in backups; it stays in Android")
                    }
                    ListButton("Choose backup file", { open.launch(arrayOf("*/*")) },
                        description = file?.lastPathSegment ?: "None chosen", focusRequester = requester.takeIf { file == null })
                    if (file != null) {
                        TextInput(passphrase, { passphrase = it }, placeholder = "The backup's passphrase", focusRequester = requester,
                            password = true, modifier = Modifier.fillMaxWidth())
                        ListButton("Restore", {
                            if (passphrase.isEmpty()) error = "Enter the passphrase"
                            else run { progress ->
                                Backups.restore(activity, file!!, passphrase.toCharArray(), progress)
                                "Restored. Finish to start DumbTalk with it."
                            }
                        })
                    }
                }
                else -> {
                    Table {
                        Cell("✓", "Chats, linked accounts and settings")
                        Cell(if (media) "✓" else "✗", "Photos and files")
                        Cell("✗", "SMS: it stays in Android")
                    }
                    Hint("Encrypted with a passphrase you choose. Without it, the backup can't be opened.")
                    TextInput(passphrase, { passphrase = it }, placeholder = "Passphrase, 8 or more characters", focusRequester = requester,
                        password = true, modifier = Modifier.fillMaxWidth())
                    ListButton("Photos and files: " + if (media) "included" else "left out", { media = !media },
                        description = "Leaving them out makes a much smaller backup")
                    ListButton("Choose where to save", {
                        if (passphrase.length < 8) error = "Use 8 or more characters"
                        else save.launch("dumbtalk-${LocalDate.now()}.dtbackup")
                    })
                }
            }
            error?.let { BasicText(it, style = Type.small.copy(color = Palette.error)) }
        }
    }
}
