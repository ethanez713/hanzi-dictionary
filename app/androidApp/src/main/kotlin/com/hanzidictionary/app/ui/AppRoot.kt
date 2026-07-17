package com.hanzidictionary.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository

/** Route arguments can be CJK chars or "ids:⿰..." pseudo-ids, neither URL-safe, so
 * every id crossing a route boundary is percent-encoded here and decoded in the screen.
 *
 * Uses android.net.Uri, not java.net.URLEncoder: URLEncoder does form-encoding, which
 * turns spaces into '+' and, on the way back, '+' into a space — corrupting glyph ids
 * like "U+597D". Uri.decode only reverses percent-escapes and leaves '+' alone, so it's
 * also idempotent if Navigation has already decoded the path argument. */
private fun enc(s: String): String = Uri.encode(s)
private fun dec(s: String): String = Uri.decode(s)

@Composable
fun AppRoot(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "search") {
        composable("search") {
            SearchScreen(
                repository = repository,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenPractice = { nav.navigate("practice") },
                onOpenPhotos = { nav.navigate("photos") },
            )
        }
        composable(
            route = "practice?component={componentId}",
            arguments = listOf(navArgument("componentId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            PracticeScreen(
                repository = repository,
                userRepository = userRepository,
                hskLevels = hskLevels,
                tts = tts,
                initialComponentId = entry.arguments?.getString("componentId")?.let { dec(it) },
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenConfusables = { nav.navigate("confusables") },
                onOpenArchive = { nav.navigate("known") },
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
                onPracticeSeries = { nav.navigate("practice?component=${enc(componentId)}") },
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
        composable("photos") {
            PhotosScreen(
                repository = repository,
                userRepository = userRepository,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
