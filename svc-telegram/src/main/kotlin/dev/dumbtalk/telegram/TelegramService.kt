package dev.dumbtalk.telegram

import android.util.Log
import android.util.LruCache
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.Capabilities
import dev.dumbtalk.core.Contact
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ConversationKind
import dev.dumbtalk.core.ConversationPage
import dev.dumbtalk.core.ConversationUpdate
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Member
import dev.dumbtalk.core.Message
import dev.dumbtalk.core.MessagePage
import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.OutgoingAttachment
import dev.dumbtalk.core.Presence
import dev.dumbtalk.core.Receipt
import dev.dumbtalk.core.Reaction
import dev.dumbtalk.core.SearchResult
import dev.dumbtalk.core.ServiceEvent
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.ServiceStatus
import dev.dumbtalk.core.SetupStep
import dev.dumbtalk.core.TextSpan
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * Where TDLib keeps its data and how this device identifies itself. Setup asks
 * for the user's API ID/hash and stores them in [dataDir]; APKs contain none.
 */
class TelegramConfig(
    val dataDir: File,
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
    /** TDLib's link to Telegram's servers, separate from being signed in. */
    @Volatile private var connection: TdApi.ConnectionState? = null
    @Volatile private var foreground = false
    /** Set when TDLib rejects our parameters (e.g. bad API credentials). */
    private val parametersError = MutableStateFlow<Throwable?>(null)
    @Volatile private var me: TdApi.User? = null
    private var chatListsLoaded: Job? = null

    internal val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    internal val users = ConcurrentHashMap<Long, TdApi.User>()
    /** Our membership of each group, for whether we may change it. */
    private val basicGroups = ConcurrentHashMap<Long, TdApi.BasicGroup>()
    private val supergroups = ConcurrentHashMap<Long, TdApi.Supergroup>()
    /** chat id → sender id → when the typing notice lapses (ms). */
    private val typing = ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>>()
    private val mapper = TelegramMapper(this)
    private val reactionCache = LruCache<String, Pair<String, List<Reaction>>>(128)
    private val pendingReactions = ConcurrentHashMap.newKeySet<String>()
    private val reactionRequests = Semaphore(3)

    private val databaseDir = File(config.dataDir, "db")
    private val credentialsFile = File(config.dataDir, "credentials.properties")
    private var pendingApiId: Int? = null

    init {
        // Reconnect at launch when an account was linked before.
        if (credentials() != null && File(databaseDir, "td.binlog").exists()) start()
    }

    // region Credentials and client lifecycle

    private fun credentials(): Pair<Int, String>? {
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
            is TdApi.UpdateConnectionState -> onConnectionState(update.state)
            is TdApi.UpdateUser -> users[update.user.id] = update.user
            is TdApi.UpdateUserStatus -> {
                users[update.userId]?.let { synchronized(it) { it.status = update.status } }
                mutableEvents.tryEmit(ServiceEvent.PresenceChanged(id))
            }
            is TdApi.UpdateBasicGroup -> basicGroups[update.basicGroup.id] = update.basicGroup
            is TdApi.UpdateSupergroup -> supergroups[update.supergroup.id] = update.supergroup
            is TdApi.UpdateChatActionBar -> chat(update.chatId) { actionBar = update.actionBar }
            is TdApi.UpdateChatBlockList -> chat(update.chatId) { blockList = update.blockList }
            is TdApi.UpdateChatMessageAutoDeleteTime -> {
                chat(update.chatId) { messageAutoDeleteTime = update.messageAutoDeleteTime }
                messagesChanged(update.chatId)
            }
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
                client?.let { postOnline(it) }
                me = runCatching { requireClient().send(TdApi.GetMe()) }.getOrNull()
                loadChatLists()
                mutableEvents.emit(ServiceEvent.StatusChanged(id))
            }
            is TdApi.AuthorizationStateClosed -> {
                client = null
                connection = null
                me = null
                chatListsLoaded = null
                chats.clear()
                users.clear()
                basicGroups.clear()
                supergroups.clear()
                typing.clear()
                mutableEvents.tryEmit(ServiceEvent.StatusChanged(id))
                conversationsChanged()
            }
        }
        authState.value = state
    }

    private fun onConnectionState(state: TdApi.ConnectionState) {
        val wasUp = isUp(connection)
        connection = state
        Log.i(TAG, "connection ${state.javaClass.simpleName.removePrefix("ConnectionState")}")
        if (isUp(state) != wasUp) mutableEvents.tryEmit(ServiceEvent.StatusChanged(id))
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
            delay(TYPING_MILLIS + 100)
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
        ready = isUp(connection),
        accountLabel = me?.let { user -> mapper.userName(user) + (user.phoneNumber.takeIf { it.isNotBlank() }?.let { " · +$it" } ?: "") },
    )

    override suspend fun capabilities() = Capabilities(
        retries = true,
        reactions = true,
        edits = true,
        deletes = true,
        pins = true,
        polls = true,
        closingPolls = true,
        search = true,
        compose = true,
        muting = true,
        attachments = true,
        voiceNotes = true,
        mentions = true,
        disappearingMessages = true,
        // Telegram's auto-delete choices: a day, a week, a month.
        disappearingDurations = listOf(86_400, 604_800, 2_678_400),
        messageRequests = true,
        blocking = true,
        groups = true,
    )

    override suspend fun beginSetup(): SetupStep {
        if (credentials() == null) {
            return SetupStep.Input(
                "api-id", "Telegram API ID",
                "Telegram needs app credentials of your own. On a computer, sign in at my.telegram.org, open " +
                    "API development tools and create an app (any name). Enter its App api_id here.",
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

    override suspend fun conversationAvatar(conversation: Conversation): String? {
        val chat = chats[chatId(conversation)] ?: return null
        return synchronized(chat) { chat.photo?.small }?.let { localPath(it) }
    }

    /** A file's local path, downloading it first when needed; null if it can't be fetched. */
    private suspend fun localPath(file: TdApi.File): String? =
        file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() }
            ?: runCatching { download(file.id) }.getOrNull()

    /** Downloads a file (at once if already there) and returns its path, or null if it didn't finish. */
    private suspend fun download(fileId: Int, priority: Int = 1): String? {
        val file = requireClient().send(TdApi.DownloadFile(fileId, priority, 0, 0, true))
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() }
    }

    /** Reactors are "user:<id>" or "chat:<id>", as [TelegramMapper.reactor] keys them. */
    override suspend fun personAvatar(personId: String): String? {
        val (kind, raw) = personId.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val id = raw.toLongOrNull() ?: return null
        val photo = when (kind) {
            "user" -> user(id)?.profilePhoto?.small
            "chat" -> chats[id]?.let { synchronized(it) { it.photo?.small } }
            else -> null
        } ?: return null
        return localPath(photo)
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
        val messages = collected.asReversed().map { source ->
            val mapped = synchronized(chat) { mapper.message(chat, source, replies) }
            withReactionPeople(chat.id, source, mapped)
        }
        return MessagePage(messages = messages, hasMore = collected.isNotEmpty(), typingNames = typingNames(chat.id))
    }

    /** Populate complete reactor rosters in the background without delaying chat history. */
    private fun withReactionPeople(chat: Long, source: TdApi.Message, mapped: Message): Message {
        val info = source.interactionInfo?.reactions ?: return mapped
        if (!info.canGetAddedReactions || mapped.reactions.isEmpty()) return mapped
        val signature = info.reactions.joinToString(";") { reaction ->
            val emoji = (reaction.type as? TdApi.ReactionTypeEmoji)?.emoji.orEmpty()
            "$emoji:${reaction.totalCount}:${reaction.isChosen}:" + reaction.recentSenderIds.joinToString(",") {
                when (it) { is TdApi.MessageSenderUser -> "u${it.userId}"; is TdApi.MessageSenderChat -> "c${it.chatId}"; else -> "?" }
            }
        }
        val key = "$chat:${source.id}"
        reactionCache.get(key)?.takeIf { it.first == signature }?.let { return mapped.copy(reactions = it.second) }
        if (pendingReactions.add(key)) scope.launch {
            try {
                val people = reactionRequests.withPermit {
                    val all = mutableListOf<Reaction>()
                    var offset = ""
                    do {
                        val page = requireClient().send(TdApi.GetMessageAddedReactions(chat, source.id, null, offset, 100))
                        for (entry in page.reactions) {
                            val emoji = (entry.type as? TdApi.ReactionTypeEmoji)?.emoji ?: continue
                            val sender = entry.senderId
                            if (sender is TdApi.MessageSenderUser) user(sender.userId) // so the mapper can name them
                            all.add(mapper.reactor(emoji, sender, entry.isOutgoing))
                        }
                        val next = page.nextOffset
                        if (next.isEmpty() || next == offset) break
                        offset = next
                    } while (true)
                    all
                }
                reactionCache.put(key, signature to people)
                mutableEvents.emit(ServiceEvent.MessagesChanged(id, conversationId(chat)))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                reactionCache.put(key, signature to mapped.reactions)
            } finally { pendingReactions.remove(key) }
        }
        return mapped
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

    /**
     * TDLib reconnects by itself, but while the phone sleeps its keep-alive
     * pings stop and a connection can die without TDLib noticing: sends wait
     * on the clock and new messages and read receipts stop arriving. So
     * unless the connection answers a test request, reopen it; TDLib then
     * fetches what it missed. A network change always reopens it.
     */
    override suspend fun reconnect(networkChanged: Boolean) {
        val client = client ?: return
        val alive = !networkChanged && isUp(connection) &&
            withTimeoutOrNull(PROBE_MILLIS) { runCatching { client.send(TdApi.TestNetwork()) }.isSuccess } == true
        if (alive) return
        Log.i(TAG, "reopening connection (network changed: $networkChanged)")
        client.post(TdApi.SetNetworkType(TdApi.NetworkTypeOther()))
    }

    /** Official apps mark the account online while they're on screen. */
    override fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        client?.let(::postOnline)
    }

    private fun postOnline(target: TdClient) = target.post(TdApi.SetOption("online", TdApi.OptionValueBoolean(foreground)))

    override suspend fun closeConversation(conversation: Conversation) {
        client?.post(TdApi.CloseChat(chatId(conversation)))
    }

    /** Telegram keeps how far a chat is read; viewing a message moves it there. */
    override suspend fun markRead(conversation: Conversation, upTo: Message?) {
        val chat = requireChat(conversation)
        val through = upTo?.id?.toLongOrNull() ?: chat.lastMessage?.id ?: return
        if (chat.unreadCount == 0) return
        requireClient().send(TdApi.ViewMessages(chat.id, longArrayOf(through), null, true))
    }

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?): Message =
        sendText(conversation, text, replyTo, emptyList())

    override suspend fun sendText(conversation: Conversation, text: String, replyTo: Message?, mentions: List<TextSpan>): Message {
        val chat = requireChat(conversation)
        // A mention names its user, so it notifies even people without a username.
        val entities = mentions.mapNotNull { mention ->
            val user = mention.target?.toLongOrNull() ?: return@mapNotNull null
            if (mention.start < 0 || mention.end > text.length) return@mapNotNull null
            TdApi.TextEntity(mention.start, mention.length, TdApi.TextEntityTypeMentionName(user))
        }.toTypedArray()
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text, entities)
            clearDraft = true
        }
        val sent = send(conversation, content, replyTo)
        return synchronized(chat) { mapper.message(chat, sent) }
    }

    /** TDLib fails a message only on an error from Telegram, and a resend replaces the failed one. */
    override suspend fun retryMessage(conversation: Conversation, message: Message) {
        val resent = requireClient().send(TdApi.ResendMessages(chatId(conversation), longArrayOf(message.id.toLong()), null, 0))
        if (resent.messages.firstOrNull() == null) throw TelegramException(0, "Telegram won't take this message again yet; try in a moment")
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

    override suspend fun listPinnedMessages(conversation: Conversation): List<Message> {
        val chat = requireChat(conversation)
        val collected = linkedMapOf<Long, TdApi.Message>()
        var from = 0L
        while (true) {
            val page = requireClient().send(TdApi.SearchChatMessages().apply {
                chatId = chat.id
                query = ""
                fromMessageId = from
                offset = 0
                limit = 100
                filter = TdApi.SearchMessagesFilterPinned()
            }).messages
            val fresh = page.filter { it.id !in collected }
            if (fresh.isEmpty()) break
            fresh.forEach { collected[it.id] = it }
            from = fresh.last().id
        }
        val replies = resolveReplies(chat.id, collected.values.toList())
        return collected.values.map { synchronized(chat) { mapper.message(chat, it, replies) } }
    }

    override suspend fun createPoll(conversation: Conversation, question: String, options: List<String>, multiple: Boolean) {
        require(question.trim().length in 1..255) { "Poll questions must be 1 to 255 characters" }
        require(options.size in 2..10) { "A poll needs 2 to 10 options" }
        require(options.all { it.trim().length in 1..100 }) { "Poll options must be 1 to 100 characters" }
        val poll = TdApi.InputMessagePoll().apply {
            this.question = TdApi.FormattedText(question.trim(), emptyArray())
            this.options = options.map { option ->
                TdApi.InputPollOption(TdApi.FormattedText(option.trim(), emptyArray()), null)
            }.toTypedArray()
            isAnonymous = true
            allowsMultipleAnswers = multiple
            type = TdApi.InputPollTypeRegular()
        }
        send(conversation, poll)
    }

    override suspend fun votePoll(conversation: Conversation, message: Message, options: List<Int>) {
        require(options.isNotEmpty()) { "Choose at least one option" }
        requireClient().send(TdApi.SetPollAnswer(chatId(conversation), message.id.toLong(), options.toIntArray()))
    }

    override suspend fun closePoll(conversation: Conversation, message: Message) {
        requireClient().send(TdApi.StopPoll(chatId(conversation), message.id.toLong(), null))
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
        update.expiration?.let { seconds -> client.send(TdApi.SetChatMessageAutoDeleteTime(chat.id, seconds)) }
    }

    /** Whether we may change who is in a group (admin or owner). */
    internal fun isAdmin(chat: TdApi.Chat): Boolean {
        val status = when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> basicGroups[type.basicGroupId]?.status
            is TdApi.ChatTypeSupergroup -> supergroups[type.supergroupId]?.status
            else -> null
        }
        return isAdminStatus(status)
    }

    private fun isAdminStatus(status: TdApi.ChatMemberStatus?) =
        status is TdApi.ChatMemberStatusCreator || status is TdApi.ChatMemberStatusAdministrator

    override suspend fun presence(conversation: Conversation): Presence? {
        val userId = (requireChat(conversation).type as? TdApi.ChatTypePrivate)?.userId ?: return null
        if (userId == myUserId()) return null
        return user(userId)?.let { synchronized(it) { presenceOf(it.status) } }
    }

    override suspend fun memberPresence(conversation: Conversation, members: List<Member>): Map<String, Presence> =
        members.mapNotNull { member ->
            val user = member.id.toLongOrNull()?.let { user(it) } ?: return@mapNotNull null
            synchronized(user) { presenceOf(user.status) }?.let { member.id to it }
        }.toMap()

    /** Telegram only says roughly when, for people who hide their last seen. */
    private fun presenceOf(status: TdApi.UserStatus?): Presence? = when (status) {
        is TdApi.UserStatusOnline -> Presence(online = true)
        is TdApi.UserStatusOffline -> Presence(online = false, lastSeen = status.wasOnline * 1000L)
        is TdApi.UserStatusRecently -> Presence(online = false, approximately = Presence.Approximately.RECENTLY)
        is TdApi.UserStatusLastWeek -> Presence(online = false, approximately = Presence.Approximately.WITHIN_A_WEEK)
        is TdApi.UserStatusLastMonth -> Presence(online = false, approximately = Presence.Approximately.WITHIN_A_MONTH)
        else -> null
    }

    /**
     * Who has read our message: a group's viewers (Telegram keeps them for
     * small groups, for a week), or when the other person in a private chat
     * read it, if both of you show read times. Telegram has no delivered step.
     */
    override suspend fun messageDetails(conversation: Conversation, message: Message): Message {
        if (message.direction != Direction.OUTGOING) return message
        val chat = requireChat(conversation)
        val id = message.id.toLongOrNull() ?: return message
        val readers = runCatching {
            if (chat.type is TdApi.ChatTypePrivate) {
                val read = requireClient().send(TdApi.GetMessageReadDate(chat.id, id)) as? TdApi.MessageReadDateRead
                listOfNotNull(read?.let { Receipt.Reader(conversation.title, readAt = it.readDate * 1000L) })
            } else {
                requireClient().send(TdApi.GetMessageViewers(chat.id, id)).viewers.map { viewer ->
                    Receipt.Reader(user(viewer.userId)?.let(mapper::userName) ?: "Telegram user", readAt = viewer.viewDate * 1000L)
                }
            }
        }.getOrDefault(emptyList())
        return message.copy(receipt = (message.receipt ?: Receipt(Receipt.State.SENT)).copy(readBy = readers))
    }

    override suspend fun listMembers(conversation: Conversation): List<Member> {
        val chat = requireChat(conversation)
        val client = requireClient()
        val members: List<TdApi.ChatMember> = when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> return listOfNotNull(
                user(type.userId)?.takeIf { it.id != myUserId() }?.let { Member(it.id.toString(), mapper.userName(it)) },
            )
            is TdApi.ChatTypeBasicGroup -> client.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId)).members.toList()
            is TdApi.ChatTypeSupergroup -> client.send(TdApi.GetSupergroupMembers(type.supergroupId, null, 0, 200)).members.toList()
            else -> emptyList()
        }
        return members.mapNotNull { member ->
            val userId = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
            if (userId == myUserId() || member.status is TdApi.ChatMemberStatusLeft || member.status is TdApi.ChatMemberStatusBanned) return@mapNotNull null
            Member(userId.toString(), user(userId)?.let(mapper::userName) ?: "Telegram user", isAdmin = isAdminStatus(member.status))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun setBlocked(conversation: Conversation, blocked: Boolean) {
        val user = (requireChat(conversation).type as? TdApi.ChatTypePrivate)?.userId
            ?: throw TelegramException(0, "Only people can be blocked; leave the group instead")
        requireClient().send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(user), if (blocked) TdApi.BlockListMain() else null))
    }

    override suspend fun respondToMessageRequest(conversation: Conversation, accept: Boolean) {
        val chat = requireChat(conversation)
        if (accept) requireClient().send(TdApi.RemoveChatActionBar(chat.id))
        else requireClient().send(TdApi.DeleteChatHistory(chat.id, true, false))
        conversationsChanged()
    }

    override suspend fun createGroup(name: String, members: List<String>): Conversation {
        val title = name.trim().ifEmpty { throw TelegramException(0, "A group needs a name") }
        val ids = members.mapNotNull { it.toLongOrNull() }.toLongArray()
        if (ids.isEmpty()) throw TelegramException(0, "Choose at least one person")
        val created = requireClient().send(TdApi.CreateNewBasicGroupChat(ids, title, 0))
        return conversationFor(chats[created.chatId] ?: requireClient().send(TdApi.GetChat(created.chatId)))
    }

    override suspend fun renameGroup(conversation: Conversation, name: String) {
        requireClient().send(TdApi.SetChatTitle(chatId(conversation), name.trim().ifEmpty { throw TelegramException(0, "A group needs a name") }))
    }

    override suspend fun addGroupMembers(conversation: Conversation, members: List<String>) {
        val failed = requireClient().send(TdApi.AddChatMembers(chatId(conversation), members.mapNotNull { it.toLongOrNull() }.toLongArray()))
        if (failed.failedToAddMembers.isNotEmpty()) {
            val names = failed.failedToAddMembers.map { users[it.userId]?.let(mapper::userName) ?: "Someone" }
            throw TelegramException(0, "${names.joinToString()} couldn't be added; their privacy settings need an invite link")
        }
    }

    override suspend fun removeGroupMember(conversation: Conversation, member: Member) {
        val user = member.id.toLongOrNull() ?: throw TelegramException(0, "Unknown member")
        requireClient().send(TdApi.BanChatMember(chatId(conversation), TdApi.MessageSenderUser(user), 0, false))
    }

    override suspend fun leaveGroup(conversation: Conversation) {
        requireClient().send(TdApi.LeaveChat(chatId(conversation)))
    }

    override suspend fun downloadAttachment(conversation: Conversation, message: Message, attachment: Attachment): String {
        return download(attachment.id.toInt(), priority = 32) ?: throw TelegramException(0, "Download didn't finish")
    }

    override suspend fun downloadThumbnail(conversation: Conversation, message: Message, attachment: Attachment): String? {
        return download(attachment.thumbnailId?.toIntOrNull() ?: return null)
    }

    override suspend fun sendAttachments(conversation: Conversation, files: List<OutgoingAttachment>, caption: String?, replyTo: Message?) {
        if (files.size < 2 || files.any { !it.mimeType.startsWith("image/") && !it.mimeType.startsWith("video/") }) {
            return super.sendAttachments(conversation, files, caption, replyTo)
        }
        require(files.size <= 10) { "An album can contain at most 10 files" }
        requireClient().send(TdApi.SendMessageAlbum().apply {
            chatId = chatId(conversation)
            if (replyTo != null) this.replyTo = replyInput(replyTo)
            inputMessageContents = files.mapIndexed { index, file -> attachmentContent(file.path, file.mimeType, if (index == 0) caption else null) }.toTypedArray()
        })
    }

    override suspend fun sendAttachment(conversation: Conversation, path: String, mimeType: String, caption: String?) {
        send(conversation, attachmentContent(path, mimeType, caption))
    }

    private fun attachmentContent(path: String, mimeType: String, caption: String?): TdApi.InputMessageContent {
        val file = TdApi.InputFileLocal(path)
        val text = TdApi.FormattedText(caption.orEmpty(), emptyArray())
        return when {
            mimeType.startsWith("image/") -> TdApi.InputMessagePhoto().apply { photo = TdApi.InputPhoto().apply { photo = file }; this.caption = text }
            mimeType.startsWith("video/") -> TdApi.InputMessageVideo().apply { video = TdApi.InputVideo().apply { video = file }; this.caption = text }
            else -> TdApi.InputMessageDocument().apply { document = TdApi.InputDocument().apply { document = file }; this.caption = text }
        }
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

    private suspend fun send(conversation: Conversation, content: TdApi.InputMessageContent, replyTo: Message? = null): TdApi.Message =
        requireClient().send(TdApi.SendMessage().apply {
            chatId = chatId(conversation)
            inputMessageContent = content
            if (replyTo != null) this.replyTo = replyInput(replyTo)
        })

    private fun replyInput(message: Message) = TdApi.InputMessageReplyToMessage().apply { messageId = message.id.toLong() }

    override suspend fun searchMessages(query: String, conversation: Conversation?): List<SearchResult> {
        val found = if (conversation != null) requireClient().send(TdApi.SearchChatMessages().apply {
            chatId = chatId(conversation)
            this.query = query
            fromMessageId = 0
            offset = 0
            limit = 100
        }).messages else requireClient().send(TdApi.SearchMessages().apply {
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
        return requireClient().send(TdApi.GetContacts()).userIds.toList()
            .mapNotNull { id -> user(id) }
            .map { Contact(it.id.toString(), id, mapper.userName(it), it.phoneNumber.takeIf { n -> n.isNotBlank() }?.let { n -> "+$n" }) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun startChat(contact: Contact): Conversation {
        return conversationFor(requireClient().send(TdApi.CreatePrivateChat(contact.id.toLong(), false)))
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
        return conversationFor(chat)
    }

    /** Maps a chat TDLib returned, keeping the copy its updates gave us when there is one. */
    private fun conversationFor(chat: TdApi.Chat): Conversation {
        val known = chats.putIfAbsent(chat.id, chat) ?: chat
        return synchronized(known) { mapper.conversation(known) }
    }

    /** A user from TDLib's updates, or fetched (and kept) when we haven't seen them yet. */
    private suspend fun user(userId: Long): TdApi.User? =
        users[userId] ?: runCatching { requireClient().send(TdApi.GetUser(userId)) }.getOrNull()?.also { users[it.id] = it }

    internal fun myUserId(): Long = me?.id ?: 0L

    // endregion

    internal companion object {
        private const val TAG = "Telegram"
        const val PAGE = 40
        /** How long a live connection may take to answer [reconnect]'s test request. */
        const val PROBE_MILLIS = 10_000L
        const val TYPING_MILLIS = 6_000L
        /** What Telegram's own apps send for "mute forever". */
        const val MUTE_FOREVER = Int.MAX_VALUE

        fun sameList(first: TdApi.ChatList, second: TdApi.ChatList): Boolean = when (first) {
            is TdApi.ChatListMain -> second is TdApi.ChatListMain
            is TdApi.ChatListArchive -> second is TdApi.ChatListArchive
            is TdApi.ChatListFolder -> second is TdApi.ChatListFolder && first.chatFolderId == second.chatFolderId
            else -> false
        }

        /** Connected; Updating is connected and catching up. */
        fun isUp(state: TdApi.ConnectionState?) =
            state is TdApi.ConnectionStateReady || state is TdApi.ConnectionStateUpdating

        fun kind(chat: TdApi.Chat): ConversationKind = when (chat.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> ConversationKind.DIRECT
            else -> ConversationKind.GROUP
        }
    }
}
