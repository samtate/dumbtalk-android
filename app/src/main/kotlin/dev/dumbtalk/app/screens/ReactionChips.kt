package dev.dumbtalk.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.Avatar
import dev.dumbtalk.app.ui.Square
import dev.dumbtalk.app.ui.Type
import dev.dumbtalk.app.ui.forDisplay
import dev.dumbtalk.core.Reaction
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Each emoji owns its roster. Both rosters and groups wrap on small screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionChips(reactions: List<Reaction>, tint: Color, loadAvatar: suspend (String) -> String? = { null }) {
    FlowRow(
        modifier = Modifier.padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        reactions.groupBy { it.emoji }.forEach { (emoji, people) ->
            val known = people.filterNot { it.author == "Someone" && it.authorId == null }
            val unknown = people.size - known.size
            FlowRow(
                modifier = Modifier.border(1.dp, tint.copy(alpha = 0.45f), Square).padding(3.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildList {
                            addAll(known.map { it.author })
                            if (unknown > 0) add("$unknown unidentified ${if (unknown == 1) "person" else "people"}")
                        }.joinToString(", ") + " reacted $emoji"
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                known.forEach { person ->
                    val photo by produceState(person.avatarPath ?: person.authorId?.let(ReactorPhotos::cached), person.authorId) {
                        val id = person.authorId
                        if (value == null && id != null) value = ReactorPhotos.load(id, loadAvatar)
                    }
                    Box { Avatar(person.author, size = 24.dp, imagePath = photo) }
                }
                if (unknown > 0) BasicText("+$unknown", style = Type.small)
                BasicText(emoji.forDisplay(), style = Type.body)
            }
        }
    }
}

/** Reactors' profile photos, fetched once per person while the app runs. */
private object ReactorPhotos {
    private val paths = ConcurrentHashMap<String, String>()
    private val missing = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun cached(id: String): String? = paths[id]

    suspend fun load(id: String, fetch: suspend (String) -> String?): String? {
        paths[id]?.let { return it }
        if (id in missing) return null
        // A failure (offline, say) is tried again next time; only "no photo" is remembered.
        val path = runCatching { fetch(id) }.getOrElse { return null }?.takeIf { it.isNotBlank() }
        if (path != null) paths[id] = path else missing += id
        return path
    }
}
