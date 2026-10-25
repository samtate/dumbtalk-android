package dev.dumbtalk.app

import androidx.compose.runtime.mutableStateListOf
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.SearchResult

enum class ChatTool { SEARCH, PINS }

sealed interface Screen {
    data class Inbox(val archived: Boolean = false) : Screen
    data class Menu(val selected: Conversation?, val archived: Boolean) : Screen
    data class Chat(val conversationId: String, val initial: Conversation, val showOptions: Boolean = false, val target: SearchResult? = null) : Screen
    data class ChatTools(val conversation: Conversation, val tool: ChatTool) : Screen
    data object Services : Screen
    data object Settings : Screen
    data class Setup(val serviceId: ServiceId) : Screen
    data object Search : Screen
    data object NewMessage : Screen
    data object NewNumber : Screen
    data object NewGroup : Screen
    /** [firstRun]: the welcome, which leads on to Android's permission prompts. */
    data class Help(val firstRun: Boolean = false) : Screen
    data object About : Screen
    /** Backing up or restoring, in a restart with no services running; see [DumbTalkApp.maintenance]. */
    data class Backup(val restore: Boolean) : Screen
}

class Navigator {
    private val stack = mutableStateListOf<Screen>(Screen.Inbox())

    val current: Screen get() = stack.last()

    /** Remembered per inbox so Back lands on the row you left from. */
    val inboxFocus = mutableMapOf<Boolean, String>()

    fun push(screen: Screen) {
        stack += screen
    }

    /** Replaces the top screen, e.g. menu → chosen destination. */
    fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun openChat(conversation: Conversation, showOptions: Boolean = false, target: SearchResult? = null) =
        push(Screen.Chat(conversation.id, conversation, showOptions, target))

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
