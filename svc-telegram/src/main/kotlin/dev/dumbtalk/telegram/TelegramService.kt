package dev.dumbtalk.telegram

import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagePage
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import dev.dumbtalk.core.SetupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Where TDLib keeps its data and how this app identifies itself. [apiId] and
 * [apiHash] come from the build; when they're missing, setup asks for them
 * and stores them in [dataDir].
 */
class TelegramConfig(
    val dataDir: File,
    val apiId: Int,
    val apiHash: String,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val language: String,
)

/** Telegram on the device through TDLib, which keeps its own message database. */
class TelegramService(private val config: TelegramConfig, private val scope: CoroutineScope) : MessagingService {
    override val id = ServiceId.TELEGRAM

    private val mutableEvents = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<ServiceEvent> = mutableEvents

    @Volatile private var client: TdClient? = null
    private val authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    /** Set when TDLib rejects our parameters (e.g. bad API credentials). */
    private val parametersError = MutableStateFlow<Throwable?>(null)
    @Volatile private var me: TdApi.User? = null
    private var chatListsLoaded: Job? = null

    internal val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    internal val users = ConcurrentHashMap<Long, TdApi.User>()
    /** chat id → sender id → when the typing notice lapses (ms). */
    private val typing = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>>()
    private val mapper = TelegramMapper(this)

    private val databaseDir = File(config.dataDir, "db")
    private val credentialsFile = File(config.dataDir, "credentials.properties")
    private var pendingApiId: Int? = null

    init {
        // Reconnect at launch when an account was linked before.
        if (credentials() != null && File(databaseDir, "td.binlog").exists()) start()
    }

    // region Credentials and client lifecycle

    private fun credentials(): Pair<Int, String>? {
        if (config.apiId > 0 && config.apiHash.isNotBlank()) return config.apiId to config.apiHash
        if (!credentialsFile.exists()) return null
        val properties = Properties().apply { credentialsFile.inputStream().use(::load) }
        val apiId = properties.getProperty("api_id")?.toIntOrNull() ?: return null
        val apiHash = properties.getProperty("api_hash")?.takeIf { it.isNotBlank() } ?: return null
        return apiId to apiHash
    }

    private fun saveCredentials(apiId: Int, apiHash: String) {
        config.dataDir.mkdirs()
        val properties = Properties().apply {
            setProperty("api_id", apiId.toString())
            setProperty("api_hash", apiHash)
        }
        credentialsFile.outputStream().use { properties.store(it, null) }
    }

    @Synchronized
    private fun start(): TdClient = client ?: TdClient(::onUpdate).also {
        parametersError.value = null
        client = it
    }

    private fun requireClient(): TdClient = client ?: throw TelegramException(0, "Telegram is not connected")

    private fun sendParameters(target: TdClient) {
        val (apiId, apiHash) = credentials() ?: return
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = databaseDir.absolutePath
            filesDirectory = File(config.dataDir, "files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = config.language
            deviceModel = config.deviceModel
            systemVersion = config.systemVersion
            applicationVersion = config.appVersion
        }
        scope.launch {
            runCatching { target.send(parameters) }.onFailure { parametersError.value = it }
        }
    }

    // endregion

    // region Updates

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState)
            is TdApi.UpdateUser -> users[update.user.id] = update.user
            is TdApi.UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                conversationsChanged()
            }
            is TdApi.UpdateChatTitle -> chat(update.chatId) { title = update.title }
            is TdApi.UpdateChatLastMessage -> chat(update.chatId) {
                lastMessage = update.lastMessage
                update.positions.forEach { setPosition(it) }
            }
            is TdApi.UpdateChatPosition -> chat(update.chatId) { setPosition(update.position) }
            is TdApi.UpdateChatReadInbox -> chat(update.chatId) {
                unreadCount = update.unreadCount
                lastReadInboxMessageId = update.lastReadInboxMessageId
            }
            is TdApi.UpdateChatReadOutbox -> {
                chat(update.chatId) { lastReadOutboxMessageId = update.lastReadOutboxMessageId }
                messagesChanged(update.chatId)
            }
            is TdApi.UpdateChatNotificationSettings -> chat(update.chatId) { notificationSettings = update.notificationSettings }
            is TdApi.UpdateChatAction -> onChatAction(update)
            is TdApi.UpdateNewMessage -> {
                messagesChanged(update.message.chatId)
                val chat = chats[update.message.chatId]
                if (!update.message.isOutgoing && chat != null) {
                    mutableEvents.tryEmit(ServiceEvent.IncomingMessage(id, mapper.conversation(chat), mapper.message(chat, update.message)))
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> messagesChanged(update.message.chatId)
            is TdApi.UpdateMessageSendFailed -> messagesChanged(update.message.chatId)
            is TdApi.UpdateMessageContent -> messagesChanged(update.chatId)
            is TdApi.UpdateMessageEdited -> messagesChanged(update.chatId)
            is TdApi.UpdateMessageIsPinned -> messagesChanged(update.chatId)
            is TdApi.UpdateMessageInteractionInfo -> messagesChanged(update.chatId)
            is TdApi.UpdateDeleteMessages -> if (!update.fromCache) messagesChanged(update.chatId)
        }
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> client?.let(::sendParameters)
            is TdApi.AuthorizationStateReady -> scope.launch {
                me = runCatching { requireClient().send(TdApi.GetMe()) }.getOrNull()
                loadChatLists()
                mutableEvents.emit(ServiceEvent.StatusChanged(id))
            }
            is TdApi.AuthorizationStateClosed -> {
                client = null
                me = null
                chatListsLoaded = null
                chats.clear()
                users.clear()
                typing.clear()
                mutableEvents.tryEmit(ServiceEvent.StatusChanged(id))
                conversationsChanged()
            }
        }
        authState.value = state
    }

    private fun onChatAction(update: TdApi.UpdateChatAction) {
        val sender = (update.senderId as? TdApi.MessageSenderUser)?.userId ?: return
        val senders = typing.getOrPut(update.chatId) { ConcurrentHashMap() }
        if (update.action is TdApi.ChatActionCancel) senders.remove(sender)
        else senders[sender] = System.currentTimeMillis() + TYPING_MILLIS
        conversationsChanged()
        messagesChanged(update.chatId)
        // Refresh once the notice lapses so stale "typing…" disappears.
        scope.launch {
            kotlinx.coroutines.delay(TYPING_MILLIS + 100)
            conversationsChanged()
        }
    }

    internal fun typingNames(chatId: Long): List<String> {
        val now = System.currentTimeMillis()
        return typing[chatId].orEmpty().filterValues { it > now }.keys.mapNotNull { users[it]?.let(mapper::userName) }
    }

    /** TDLib's chat objects are shared and mutated in place, as in its examples. */
    private inline fun chat(chatId: Long, change: TdApi.Chat.() -> Unit) {
        val chat = chats[chatId] ?: return
        synchronized(chat) { chat.change() }
        conversationsChanged()
    }

    private fun TdApi.Chat.setPosition(position: TdApi.ChatPosition) {
        val others = positions.filterNot { sameList(it.list, position.list) }
        positions = (if (position.order == 0L) others else others + position).toTypedArray()
    }

    private fun conversationsChanged() {
        mutableEvents.tryEmit(ServiceEvent.ConversationsChanged(id))
    }

    private fun messagesChanged(chatId: Long) {
        mutableEvents.tryEmit(ServiceEvent.MessagesChanged(id, conversationId(chatId)))
    }

    private suspend fun loadChatLists() {
        val job = chatListsLoaded ?: scope.launch {
            // TDLib answers 404 once a list is fully loaded; that's fine.
            runCatching { requireClient().send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) }
            runCatching { requireClient().send(TdApi.LoadChats(TdApi.ChatListArchive(), 50)) }
        }.also { chatListsLoaded = it }
        job.join()
    }

    // endregion

    // region Status and setup

    override suspend fun status() = ServiceStatus(
        id,
        connected = authState.value is TdApi.AuthorizationStateReady,
        ready = client != null,
        accountLabel = me?.let { user -> mapper.userName(user) + (user.phoneNumber.takeIf { it.isNotBlank() }?.let { " · +$it" } ?: "") },
    )

    override suspend fun capabilities() = Capabilities(
        reactions = true,
        edits = true,
        deletes = true,
        pins = true,
        search = true,
        compose = true,
        muting = true,
        attachments = true,
        voiceNotes = true,
    )

    override suspend fun beginSetup(): SetupStep {
        if (credentials() == null) {
            return SetupStep.Input(
                "api-id", "Telegram API ID",
                "This build has no Telegram app credentials. Create an app at my.telegram.org and enter its API ID.",
                SetupStep.Input.Field.API_ID, placeholder = "12345678",
            )
        }
        start()
        return step(awaitState { true })
    }

    override suspend fun advanceSetup(step: SetupStep, value: String?): SetupStep {
        val current = authState.value
        return when (step) {
            is SetupStep.Input -> when (step.field) {
                SetupStep.Input.Field.API_ID -> {
                    pendingApiId = value?.toIntOrNull() ?: throw TelegramException(0, "The API ID is a number")
                    SetupStep.Input("api-hash", "Telegram API hash", "Enter the 32-character API hash.", SetupStep.Input.Field.API_HASH)
                }
                SetupStep.Input.Field.API_HASH -> {
                    val hash = value.orEmpty()
                    if (!Regex("[0-9a-fA-F]{32}").matches(hash)) throw TelegramException(0, "The API hash is 32 letters and digits")
                    saveCredentials(pendingApiId ?: throw TelegramException(0, "Enter the API ID first"), hash.lowercase())
                    start()
                    step(awaitState { true })
                }
                SetupStep.Input.Field.PHONE -> {
                    val phone = value.orEmpty().filter { it.isDigit() || it == '+' }
                    requireClient().send(TdApi.SetAuthenticationPhoneNumber(phone, TdApi.PhoneNumberAuthenticationSettings()))
                    step(awaitState { it !== current })
                }
                SetupStep.Input.Field.CODE -> {
                    requireClient().send(TdApi.CheckAuthenticationCode(value.orEmpty().trim()))
                    step(awaitState { it !== current })
                }
                SetupStep.Input.Field.PASSWORD -> {
                    requireClient().send(TdApi.CheckAuthenticationPassword(value.orEmpty()))
                    step(awaitState { it !== current })
                }
            }
            is SetupStep.Choice -> if (value == "phone") {
                SetupStep.Input(
                    "phone", "Phone number", "Enter your Telegram number with its country code.",
                    SetupStep.Input.Field.PHONE, placeholder = "+44…",
                )
            } else {
                requireClient().send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
                step(awaitState { it is TdApi.AuthorizationStateWaitOtherDeviceConfirmation })
            }
            // Waits for the scan; Telegram also refreshes the link every ~30s.
            is SetupStep.Qr -> step(awaitState(timeoutMillis = 10 * 60_000L) { it !== current })
            is SetupStep.PairCode, is SetupStep.Complete -> step
        }
    }

    private suspend fun awaitState(
        timeoutMillis: Long = 30_000,
        accept: (TdApi.AuthorizationState) -> Boolean,
    ): TdApi.AuthorizationState = withTimeout(timeoutMillis) {
        combine(authState, parametersError) { state, error -> state to error }
            .first { (state, error) ->
                if (error != null) throw error
                state != null && state !is TdApi.AuthorizationStateWaitTdlibParameters && accept(state)
            }
            .first!!
    }

    private fun step(state: TdApi.AuthorizationState): SetupStep = when (state) {
        is TdApi.AuthorizationStateReady -> SetupStep.Complete("Telegram connected", "Your Telegram chats now appear in the inbox.")
        is TdApi.AuthorizationStateWaitPhoneNumber -> SetupStep.Choice(
            "method", "Link Telegram", "Choose how to sign in.",
            listOf(
                SetupStep.Choice.Option("qr", "QR code", "Scan from Telegram on another device"),
                SetupStep.Choice.Option("phone", "Phone number", "Get a login code"),
            ),
        )
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> SetupStep.Qr(
            "qr", "Scan QR code",
            "On your main phone open Telegram → Settings → Devices → Link Desktop Device, then scan this code.",
            state.link,
        )
        is TdApi.AuthorizationStateWaitCode -> SetupStep.Input(
            "code", "Login code",
            when (state.codeInfo.type) {
                is TdApi.AuthenticationCodeTypeTelegramMessage -> "Telegram sent a code to your other devices. Enter it here."
                is TdApi.AuthenticationCodeTypeSms -> "Enter the code sent by SMS to +${state.codeInfo.phoneNumber}."
                else -> "Enter the login code for +${state.codeInfo.phoneNumber}."
            },
            SetupStep.Input.Field.CODE,
        )
        is TdApi.AuthorizationStateWaitPassword -> SetupStep.Input(
            "password", "Two-step password", "This account has a two-step verification password.",
            SetupStep.Input.Field.PASSWORD,
            hint = state.passwordHint.takeIf { it.isNotBlank() }?.let { "Hint: $it" },
        )
        is TdApi.AuthorizationStateWaitRegistration -> throw TelegramException(0, "This number has no Telegram account. Sign up in the official app first.")
        is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode ->
            throw TelegramException(0, "Telegram wants an email login for this account. Finish signing in once in the official app, then try again.")
        else -> throw TelegramException(0, "Telegram is busy (${state.javaClass.simpleName}). Try again.")
    }

    override suspend fun disconnect() {
        val current = client ?: return
        runCatching { current.send(TdApi.LogOut()) }
        runCatching { awaitState { it is TdApi.AuthorizationStateClosed } }
    }

    // endregion

    // region Conversations and messages

    private fun conversationId(chatId: Long) = "${id.key}:$chatId"

    private fun chatId(conversation: Conversation) = conversation.remoteId.toLong()

    private fun requireChat(conversation: Conversation): TdApi.Chat =
        chats[chatId(conversation)] ?: throw TelegramException(0, "Unknown Telegram chat")

    override suspend fun listConversations(archived: Boolean): ConversationPage {
        if (authState.value !is TdApi.AuthorizationStateReady) return ConversationPage(emptyList(), 0)
        loadChatLists()
        val all = chats.values.map { chat -> synchronized(chat) { mapper.conversation(chat) } }
            .filter { it.isArchived || inMainList(it) }
        return ConversationPage(all.filter { it.isArchived == archived }, all.count { it.isArchived })
    }

    private fun inMainList(conversation: Conversation): Boolean =
        chats[conversation.remoteId.toLong()]?.positions?.any { it.list is TdApi.ChatListMain && it.order != 0L } == true

    override suspend fun listMessages(conversation: Conversation, before: Message?): MessagePage {
        val chat = requireChat(conversation)
        val client = requireClient()
        val collected = ArrayList<TdApi.Message>()
        var from = before?.id?.toLongOrNull() ?: 0L
        // TDLib may answer with only what's cached locally; ask a few times.
        repeat(4) {
            if (collected.size >= PAGE) return@repeat
            val page = client.send(TdApi.GetChatHistory(chat.id, from, 0, PAGE, false)).messages
                .filter { it.id != from }
            if (page.isEmpty()) return@repeat
            collected += page
            from = page.last().id
        }
        val replies = resolveReplies(chat.id, collected)
        val messages = collected.asReversed().map { synchronized(chat) { mapper.message(chat, it, replies) } }
        return MessagePage(messages = messages, hasMore = collected.isNotEmpty(), typingNames = typingNames(chat.id))
    }

    /** Quoted messages in the same chat, fetched from TDLib's local database when not on the page. */
    private suspend fun resolveReplies(chatId: Long, page: List<TdApi.Message>): Map<Long, TdApi.Message> {
        val byId = page.associateBy { it.id }.toMutableMap()
        page.mapNotNull { (it.replyTo as? TdApi.MessageReplyToMessage)?.takeIf { reply -> reply.chatId == chatId }?.messageId }
            .filter { it !in byId }
            .distinct()
            .forEach { replyId ->
                runCatching { requireClient().send(TdApi.GetMessage(chatId, replyId)) }.getOrNull()?.let { byId[replyId] = it }
            }
        return byId
    }

    override suspend fun openConversation(conversation: Conversation) {
        requireClient().send(TdApi.OpenChat(chatId(conversation)))
    }

    override suspend fun closeConversation(conversation: Conversation) {
        client?.post(TdApi.CloseChat(chatId(conversation)))
    }

    override suspend fun markRead(conversation: Conversation) {
        val chat = requireChat(conversation)
        val last = chat.lastMessage?.id ?: return
        if (chat.unreadCount == 0) return
        requireClient().send(TdApi.ViewMessages(chat.id, longArrayOf(last), null, true))
    }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message {
        val chat = requireChat(conversation)
        val request = TdApi.SendMessage().apply {
            chatId = chat.id
            inputMessageContent = TdApi.InputMessageText().apply {
                this.text = TdApi.FormattedText(text, emptyArray())
                clearDraft = true
            }
            if (replyTo != null) {
                this.replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyTo.id.toLong() }
            }
        }
        val sent = requireClient().send(request)
        return synchronized(chat) { mapper.message(chat, sent) }
    }

    override suspend fun setTyping(conversation: Conversation, active: Boolean) {
        val action = if (active) TdApi.ChatActionTyping() else TdApi.ChatActionCancel()
        client?.post(TdApi.SendChatAction(chatId(conversation), null, "", action))
    }

    override suspend fun react(conversation: Conversation, message: Message, emoji: String, remove: Boolean) {
        val type = TdApi.ReactionTypeEmoji(emoji)
        if (remove) requireClient().send(TdApi.RemoveMessageReaction(chatId(conversation), message.id.toLong(), type))
        else requireClient().send(TdApi.AddMessageReaction(chatId(conversation), message.id.toLong(), type, false, true))
    }

    override suspend fun editMessage(conversation: Conversation, message: Message, text: String) {
        val content = TdApi.InputMessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
        requireClient().send(TdApi.EditMessageText(chatId(conversation), message.id.toLong(), null, content))
    }

    override suspend fun deleteMessage(conversation: Conversation, message: Message) {
        requireClient().send(TdApi.DeleteMessages(chatId(conversation), longArrayOf(message.id.toLong()), true))
    }

    override suspend fun pinMessage(conversation: Conversation, message: Message, pinned: Boolean) {
        if (pinned) requireClient().send(TdApi.PinChatMessage(chatId(conversation), message.id.toLong(), true, false))
        else requireClient().send(TdApi.UnpinChatMessage(chatId(conversation), message.id.toLong()))
    }

    override suspend fun updateConversation(conversation: Conversation, update: ConversationUpdate) {
        val chat = requireChat(conversation)
        val client = requireClient()
        update.archived?.let { archived ->
            client.send(TdApi.AddChatToList(chat.id, if (archived) TdApi.ChatListArchive() else TdApi.ChatListMain()))
        }
        update.favourite?.let { favourite ->
            val list: TdApi.ChatList = if (update.archived ?: conversation.isArchived) TdApi.ChatListArchive() else TdApi.ChatListMain()
            client.send(TdApi.ToggleChatIsPinned(list, chat.id, favourite))
        }
        update.muted?.let { muted ->
            val settings = synchronized(chat) { chat.notificationSettings }.apply {
                useDefaultMuteFor = false
                muteFor = if (muted) MUTE_FOREVER else 0
            }
            client.send(TdApi.SetChatNotificationSettings(chat.id, settings))
        }
    }

    override suspend fun downloadAttachment(conversation: Conversation, message: Message, attachment: Attachment): String {
        val file = requireClient().send(TdApi.DownloadFile(attachment.id.toInt(), 32, 0, 0, true))
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() }
            ?: throw TelegramException(0, "Download didn't finish")
    }

    override suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String?) {
        val file = TdApi.InputFileLocal(path)
        val text = TdApi.FormattedText(caption.orEmpty(), emptyArray())
        val content: TdApi.InputMessageContent = when {
            mimeType.startsWith("image/") -> TdApi.InputMessagePhoto().apply { photo = TdApi.InputPhoto().apply { photo = file }; this.caption = text }
            mimeType.startsWith("video/") -> TdApi.InputMessageVideo().apply { video = TdApi.InputVideo().apply { video = file }; this.caption = text }
            else -> TdApi.InputMessageDocument().apply { document = TdApi.InputDocument().apply { document = file }; this.caption = text }
        }
        send(conversation, content)
    }

    override suspend fun sendVoiceNote(conversation: Conversation, path: String, mimeType: String, durationMillis: Long) {
        val content = TdApi.InputMessageVoiceNote().apply {
            voiceNote = TdApi.InputVoiceNote().apply {
                voiceNote = TdApi.InputFileLocal(path)
                duration = (durationMillis / 1000).toInt()
            }
        }
        send(conversation, content)
    }

    private suspend fun send(conversation: Conversation, content: TdApi.InputMessageContent) {
        requireClient().send(TdApi.SendMessage().apply {
            chatId = chatId(conversation)
            inputMessageContent = content
        })
    }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> {
        val found = requireClient().send(TdApi.SearchMessages().apply {
            this.query = query
            offset = ""
            limit = 50
        }).messages
        return found
            .filter { conversation == null || it.chatId == chatId(conversation) }
            .mapNotNull { message ->
                val chat = chats[message.chatId] ?: return@mapNotNull null
                val mapped = synchronized(chat) { mapper.message(chat, message) }
                SearchResult(mapped.id, conversationId(chat.id), mapped.sender.orEmpty(), mapped.text.orEmpty(), mapped.sentAt)
            }
    }

    override suspend fun listContacts(): List<Contact> {
        val client = requireClient()
        return client.send(TdApi.GetContacts()).userIds.toList()
            .mapNotNull { id -> users[id] ?: runCatching { client.send(TdApi.GetUser(id)) }.getOrNull() }
            .map { Contact(it.id.toString(), id, mapper.userName(it), it.phoneNumber.takeIf { n -> n.isNotBlank() }?.let { n -> "+$n" }) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun startChat(contact: Contact): Conversation {
        val chat = requireClient().send(TdApi.CreatePrivateChat(contact.id.toLong(), false))
        chats.putIfAbsent(chat.id, chat)
        return synchronized(chat) { mapper.conversation(chats.getValue(chat.id)) }
    }

    override suspend fun createDirect(address: String, title: String?): Conversation {
        val client = requireClient()
        val trimmed = address.trim()
        val chat = if (trimmed.startsWith("+") || trimmed.firstOrNull()?.isDigit() == true) {
            val user = runCatching { client.send(TdApi.SearchUserByPhoneNumber(trimmed.filter { it.isDigit() }, false)) }
                .getOrElse { throw TelegramException(0, "No Telegram account found for that number") }
            client.send(TdApi.CreatePrivateChat(user.id, false))
        } else {
            runCatching { client.send(TdApi.SearchPublicChat(trimmed.removePrefix("@"))) }
                .getOrElse { throw TelegramException(0, "No Telegram user called $trimmed") }
        }
        chats.putIfAbsent(chat.id, chat)
        return synchronized(chat) { mapper.conversation(chats.getValue(chat.id)) }
    }

    internal fun myUserId(): Long = me?.id ?: 0L

    // endregion

    internal companion object {
        const val PAGE = 40
        const val TYPING_MILLIS = 6_000L
        /** What Telegram's own apps send for "mute forever". */
        const val MUTE_FOREVER = Int.MAX_VALUE

        fun sameList(first: TdApi.ChatList, second: TdApi.ChatList): Boolean = when (first) {
            is TdApi.ChatListMain -> second is TdApi.ChatListMain
            is TdApi.ChatListArchive -> second is TdApi.ChatListArchive
            is TdApi.ChatListFolder -> second is TdApi.ChatListFolder && first.chatFolderId == second.chatFolderId
            else -> false
        }

        fun kind(chat: TdApi.Chat): ConversationKind = when (chat.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> ConversationKind.DIRECT
            else -> ConversationKind.GROUP
        }
    }
}
