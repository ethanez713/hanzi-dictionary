package com.hanzidictionary.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.CardPin
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.extractHanzi
import com.hanzidictionary.core.glyphIdOfChar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** The dictionary home — the start destination, and what the bottom bar's Home tab
 * unwinds to. */
private const val HOME_ROUTE = "search"

/** The bottom navigation bar's three permanent destinations. Detail pages (a character, a
 * component, a word) select none of them — see [selectedTab]. */
private enum class Tab { HOME, STUDY, PRACTICE }

/** Which tab a back-stack entry's route pattern belongs to, matched by pattern rather than
 * the filled route so a parameterised destination (a specific list, a practice deck) still
 * lights up its tab. */
private fun selectedTab(route: String?): Tab? = when (route) {
    HOME_ROUTE -> Tab.HOME
    "lists", "list/{listId}", "known" -> Tab.STUDY
    PRACTICE_ROUTE, "confusables" -> Tab.PRACTICE
    else -> null
}

/** The practice destination's route *pattern* — how the graph knows it, as opposed to the
 * filled route [practiceRoute] builds. Kept as a constant because [CardReturn] has to look
 * the destination up by it. */
private const val PRACTICE_ROUTE = "practice?source={source}"

private fun practiceRoute(source: DeckSource): String =
    "practice?source=${enc(encodeSource(source))}"

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

@OptIn(ExperimentalMaterial3Api::class)
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
    /** Bumped by [com.hanzidictionary.app.MainActivity] each time the app comes back from a
     * long enough absence to count as a new errand (see `isStaleSession`). */
    staleSessionKey: Int = 0,
) {
    val app = LocalContext.current.applicationContext as HanziApp
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }

    // The trail marker back to a flashcard you followed into the dictionary (see [CardReturn]).
    // It lives here, above the NavHost, because every destination under it is disposed and
    // recomposed as you browse — which is exactly the churn it exists to survive.
    val cardReturn = remember(nav) { CardReturn(nav) }
    val currentEntry by nav.currentBackStackEntryAsState()
    // Arriving back on the card's own screen retires the marker, whichever way you got there:
    // the button, repeated Back, or Home. Nothing else has to remember to clear it.
    LaunchedEffect(currentEntry) {
        if (cardReturn.isAtCard(currentEntry?.destination?.id)) cardReturn.clear()
    }
    LaunchedEffect(cardReturn.label) {
        if (cardReturn.label != null) {
            delay(CARD_RETURN_TTL_MS)
            cardReturn.clear()
        }
    }

    // A browse trail has a shelf life. Come back to the app half an hour later and the four
    // pages you were deep in belong to an errand you have finished — Back through them is a
    // maze, and the card the app is holding open is one you no longer remember leaving. So a
    // stale return starts over: home, no pinned cards, no marker, no leftover query.
    //
    // Declared before the intent handlers below on purpose: if a stale return and an incoming
    // widget/share intent land in the same composition, effects run in declaration order and
    // the arriving intent must have the last word about where you end up.
    LaunchedEffect(staleSessionKey) {
        if (staleSessionKey == 0) return@LaunchedEffect
        app.homeCardPin = null
        app.practiceCardPin = null
        cardReturn.clear()
        query = ""
        nav.popBackStack(HOME_ROUTE, inclusive = false)
    }

    // "Home" unwinds the stack rather than pushing another copy of the start destination:
    // search is always the bottom of the stack, so popping back to it leaves a clean
    // one-entry history and Back from home still exits the app.
    val goHome = { nav.popBackStack(HOME_ROUTE, inclusive = false); Unit }

    // Shared text (SEND target, spec §9 capture): one hanzi jumps straight to its page;
    // several prefill search (word search picks up multi-char words from there).
    //
    // Like the widget below, a share is a *new way in* — text from another app about
    // something you were doing there — so it lands on home's clean stack rather than on top
    // of whatever the app was left showing.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            val hanzi = extractHanzi(sharedText)
            when {
                hanzi.size == 1 -> {
                    nav.popBackStack(HOME_ROUTE, inclusive = false)
                    nav.navigate("character/${enc(glyphIdOfChar(hanzi[0]))}")
                }
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

    // Tapping the middle of the home-screen widget opens that character's **detail page** —
    // the fullest answer the app has, which is the thought the widget's flipped card starts
    // and can't finish in four lines of RemoteViews.
    //
    // It also makes that character the pinned card everywhere, because the widget, the home
    // card and Practice are meant to agree about what's up next: the card you tapped is now
    // the card all three are on, so Back (or the ↩ marker) lands on it rather than on whatever
    // was showing before. Revealed, since you have just read its whole page.
    LaunchedEffect(widgetItem) {
        val item = widgetItem ?: return@LaunchedEffect
        val pin = CardPin(item.itemId, revealed = true)
        app.homeCardPin = pin
        app.practiceCardPin = pin
        // Label the trail marker with the card's own text, resolved the way the widget and the
        // cards resolve it so all three read alike — a word's itemId is not its text.
        val loaded = withContext(Dispatchers.IO) {
            val text = repository.itemSummaries(listOf(item)).firstOrNull()?.text
            val activeId = app.studyRepository.activeListId()
            val name = activeId?.let { id ->
                app.studyRepository.lists().firstOrNull { it.id == id }?.name
            }
            val source =
                if (activeId != null) DeckSource.UserList(activeId, name.orEmpty())
                else DeckSource.Everything
            text to source
        }
        // Arriving from the widget REBUILDS the stack rather than landing on top of it. The
        // widget can fire onto anything — home on a cold start, some character page four
        // levels deep on a warm one — and none of that has anything to do with the card you
        // just tapped, so Back through it is somebody else's history. What is behind the
        // card's page instead is Practice, over the same active-list deck the widget itself
        // deals from: Back from the page lands on this card, which is what tapping a
        // flashcard and then backing out of it should mean.
        nav.navigate(practiceRoute(loaded.second)) {
            popUpTo(HOME_ROUTE) { inclusive = false }
            launchSingleTop = true
        }
        // Marked while Practice is the current destination, so the ↩ button unwinds to the
        // entry we just pushed — the same one Back uses. One way back, two ways to ask for it.
        cardReturn.leaving(loaded.first ?: item.itemId)
        nav.navigate(
            if (item.isWord) "word/${enc(item.itemId)}" else "character/${enc(item.itemId)}",
        )
        onWidgetItemConsumed()
    }

    // The one persistent piece of chrome: three destinations deep enough that Back alone used
    // to cost four or five taps to escape (character -> component -> character -> ...). It
    // shows on every screen, including the detail pages, which select none of its tabs.
    Scaffold(
        bottomBar = {
            NavigationBar {
                val tab = selectedTab(currentEntry?.destination?.route)
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = goHome,
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == Tab.STUDY,
                    onClick = {
                        nav.navigate("lists") {
                            popUpTo(HOME_ROUTE) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Study") },
                )
                NavigationBarItem(
                    selected = tab == Tab.PRACTICE,
                    onClick = {
                        // The active list, resolved fresh rather than held in state, so the
                        // widget, the home screen's Practice button and this tab always agree
                        // on what's up next.
                        scope.launch {
                            val source = withContext(Dispatchers.IO) {
                                val activeId = app.studyRepository.activeListId()
                                val name = activeId?.let { id ->
                                    app.studyRepository.lists().firstOrNull { it.id == id }?.name
                                }
                                if (activeId != null) DeckSource.UserList(activeId, name.orEmpty())
                                else DeckSource.Everything
                            }
                            nav.navigate(practiceRoute(source)) {
                                popUpTo(HOME_ROUTE) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("Practice") },
                )
            }
        },
        // The way back to a suspended flashcard, hoisted here rather than left in each
        // screen's own Scaffold: an inner FAB would now float over this bar instead of above
        // it. ReturnToCardFab renders nothing when no card is suspended, so one instance is
        // correct on every destination.
        floatingActionButton = { ReturnToCardFab() },
    ) { outerPadding ->
        CompositionLocalProvider(LocalCardReturn provides cardReturn) {
            NavHost(
                navController = nav,
                startDestination = HOME_ROUTE,
                modifier = Modifier.padding(outerPadding),
            ) {
                composable(HOME_ROUTE) {
                    SearchScreen(
                        repository = repository,
                        query = query,
                        onQueryChange = { query = it },
                        onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                        onOpenWord = { wordId -> nav.navigate("word/${enc(wordId)}") },
                        tts = tts,
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
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("lists") {
                    ListsScreen(
                        hskLevels = hskLevels,
                        onOpenList = { listId -> nav.navigate("list/$listId") },
                        onPractice = { source -> nav.navigate(practiceRoute(source)) },
                        onOpenArchive = { nav.navigate("known") },
                        onOpenConfusables = { nav.navigate("confusables") },
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
                        onPractice = { source -> nav.navigate(practiceRoute(source)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                // Only a deck source travels in this route. The widget used to hand a specific card
                // in here too ("&item=…&reveal=true"); it now opens that character's detail page
                // instead, and the pinned card is how any surface resumes on a particular card.
                composable(
                    route = PRACTICE_ROUTE,
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    PracticeScreen(
                        repository = repository,
                        hskLevels = hskLevels,
                        tts = tts,
                        source = decodeSource(entry.arguments?.getString("source")),
                        onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                        onOpenWord = { wordId -> nav.navigate("word/${enc(wordId)}") },
                        onOpenConfusables = { nav.navigate("confusables") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("confusables") {
                    ConfusableDrillScreen(
                        repository = repository,
                        userRepository = userRepository,
                        onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
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
                            nav.navigate(practiceRoute(DeckSource.Series(componentId)))
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("known") {
                    KnownScreen(
                        userRepository = userRepository,
                        hskLevels = hskLevels,
                        onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
