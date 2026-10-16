package dev.dumbtalk.app

import androidx.compose.runtime.mutableStateListOf
import dev.dumbtalk.core.Conversation
import dev.dumbtalk.core.ServiceId

sealed interface Screen {
    data class Inbox(val archived: Boolean = false) : Screen
    data class Menu(val selected: Conversation?, val archived: Boolean) : Screen
    data class Chat(val conversationId: String, val initial: Conversation) : Screen
    data object Services : Screen
    data class Setup(val serviceId: ServiceId) : Screen
    data object Search : Screen
    data object NewMessage : Screen
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

    fun openChat(conversation: Conversation) = push(Screen.Chat(conversation.id, conversation))
}
