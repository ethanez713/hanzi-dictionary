package com.hanzidictionary.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.extractHanzi
import com.hanzidictionary.core.glyphIdOfChar

/** Route arguments can be CJK chars or "ids:⿰..." pseudo-ids, neither URL-safe, so
 * every id crossing a route boundary is percent-encoded here and decoded in the screen.
 *
 * Uses android.net.Uri, not java.net.URLEncoder: URLEncoder does form-encoding, which
 * turns spaces into '+' and, on the way back, '+' into a space — corrupting glyph ids
 * like "U+597D". Uri.decode only reverses percent-escapes and leaves '+' alone, so it's
 * also idempotent if Navigation has already decoded the path argument. */
private fun enc(s: String): String = Uri.encode(s)
private fun dec(s: String): String = Uri.decode(s)

/** Deck sources travel through routes as short tags. Kept next to the decoder so the two
 * can't drift apart. */
private fun encodeSource(source: DeckSource): String = when (source) {
    is DeckSource.Everything -> "everything"
    is DeckSource.UserList -> "list:${source.listId}:${enc(source.name)}"
    is DeckSource.Encountered -> "encountered"
    is DeckSource.Hsk -> "hsk:${source.level}"
    is DeckSource.Series -> "series:${enc(source.componentId)}"
}

/** The dictionary home — the start destination, and what the top bars' Home button
 * unwinds to. */
private const val HOME_ROUTE = "search"

private fun decodeSource(raw: String?): DeckSource {
    val value = raw?.let { dec(it) } ?: return DeckSource.Everything
    val parts = value.split(":")
    return when (parts[0]) {
        "list" -> parts.getOrNull(1)?.toLongOrNull()
            ?.let { DeckSource.UserList(it, parts.drop(2).joinToString(":").let(::dec)) }
            ?: DeckSource.Everything
        "encountered" -> DeckSource.Encountered
        "hsk" -> parts.getOrNull(1)?.toIntOrNull()?.let { DeckSource.Hsk(it) } ?: DeckSource.Everything
        "series" -> parts.drop(1).joinToString(":").let { DeckSource.Series(dec(it)) }
        else -> DeckSource.Everything
    }
}

@Composable
fun AppRoot(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    widgetItem: StudyItem? = null,
    onWidgetItemConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    var query by rememberSaveable { mutableStateOf("") }

    // "Home" unwinds the stack rather than pushing another copy of the start destination:
    // search is always the bottom of the stack, so popping back to it leaves a clean
    // one-entry history and Back from home still exits the app.
    val goHome = { nav.popBackStack(HOME_ROUTE, inclusive = false); Unit }

    // Shared text (SEND target, spec §9 capture): one hanzi jumps straight to its page;
    // several prefill search (word search picks up multi-char words from there).
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            val hanzi = extractHanzi(sharedText)
            when {
                hanzi.size == 1 -> nav.navigate("character/${enc(glyphIdOfChar(hanzi[0]))}")
                hanzi.isNotEmpty() -> {
                    query = hanzi.joinToString("")
                    nav.navigate("search") {
                        popUpTo("search")
                        launchSingleTop = true
                    }
                }
            }
            onSharedTextConsumed()
        }
    }

    // Tapping the middle of the home-screen widget opens Practice on that exact card,
    // already revealed — the widget can't show an answer, so the app finishes the thought.
    LaunchedEffect(widgetItem) {
        if (widgetItem != null) {
            nav.navigate(
                "practice?source=everything&item=${enc(widgetItem.itemId)}" +
                    "&kind=${widgetItem.kind}&reveal=true",
            )
            onWidgetItemConsumed()
        }
    }

    NavHost(navController = nav, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            SearchScreen(
                repository = repository,
                query = query,
                onQueryChange = { query = it },
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenWord = { wordId -> nav.navigate("word/${enc(wordId)}") },
                onOpenStudy = { nav.navigate("lists") },
            )
        }
        composable(
            route = "word/{wordId}",
            arguments = listOf(navArgument("wordId") { type = NavType.StringType }),
        ) { entry ->
            WordScreen(
                repository = repository,
                tts = tts,
                wordId = dec(entry.arguments?.getString("wordId").orEmpty()),
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable("lists") {
            ListsScreen(
                hskLevels = hskLevels,
                onOpenList = { listId -> nav.navigate("list/$listId") },
                onPractice = { source -> nav.navigate("practice?source=${enc(encodeSource(source))}") },
                onOpenArchive = { nav.navigate("known") },
                onOpenConfusables = { nav.navigate("confusables") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "list/{listId}",
            arguments = listOf(navArgument("listId") { type = NavType.LongType }),
        ) { entry ->
            ListDetailScreen(
                listId = entry.arguments?.getLong("listId") ?: 0L,
                onOpenItem = { item ->
                    if (item.isWord) nav.navigate("word/${enc(item.itemId)}")
                    else nav.navigate("character/${enc(item.itemId)}")
                },
                onPractice = { source -> nav.navigate("practice?source=${enc(encodeSource(source))}") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "practice?source={source}&item={item}&kind={kind}&reveal={reveal}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("item") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("kind") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("reveal") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            val itemId = args?.getString("item")?.let { dec(it) }
            PracticeScreen(
                repository = repository,
                hskLevels = hskLevels,
                tts = tts,
                source = decodeSource(args?.getString("source")),
                initialItem = itemId?.let {
                    StudyItem(it, args.getString("kind") ?: StudyItem.KIND_CHARACTER)
                },
                startRevealed = args?.getString("reveal") == "true",
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenWord = { wordId -> nav.navigate("word/${enc(wordId)}") },
                onOpenConfusables = { nav.navigate("confusables") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable("confusables") {
            ConfusableDrillScreen(
                repository = repository,
                userRepository = userRepository,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "character/{glyphId}",
            arguments = listOf(navArgument("glyphId") { type = NavType.StringType }),
        ) { entry ->
            val glyphId = dec(entry.arguments?.getString("glyphId").orEmpty())
            CharacterScreen(
                repository = repository,
                userRepository = userRepository,
                tts = tts,
                glyphId = glyphId,
                onOpenComponent = { componentId -> nav.navigate("component/${enc(componentId)}") },
                onOpenCharacter = { gid -> nav.navigate("character/${enc(gid)}") },
                onOpenWord = { wordId -> nav.navigate("word/${enc(wordId)}") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "component/{componentId}",
            arguments = listOf(navArgument("componentId") { type = NavType.StringType }),
        ) { entry ->
            val componentId = dec(entry.arguments?.getString("componentId").orEmpty())
            ComponentScreen(
                repository = repository,
                userRepository = userRepository,
                componentId = componentId,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenComponent = { cid -> nav.navigate("component/${enc(cid)}") },
                onPracticeSeries = {
                    nav.navigate("practice?source=${enc(encodeSource(DeckSource.Series(componentId)))}")
                },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
        composable("known") {
            KnownScreen(
                userRepository = userRepository,
                hskLevels = hskLevels,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onHome = goHome,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
