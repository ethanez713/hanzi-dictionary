package com.hanzidictionary.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hanzidictionary.app.audio.SyllableAudioPlayer
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository
import java.net.URLDecoder
import java.net.URLEncoder

/** Route arguments can be CJK chars or "ids:⿰..." pseudo-ids, neither URL-safe, so
 * every id crossing a route boundary is URL-encoded here and decoded in the screen. */
private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

@Composable
fun AppRoot(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    audioPlayer: SyllableAudioPlayer,
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "search") {
        composable("search") {
            SearchScreen(
                repository = repository,
                onOpenCharacter = { glyphId -> nav.navigate("character/${enc(glyphId)}") },
                onOpenKnown = { nav.navigate("known") },
                onOpenPhotos = { nav.navigate("photos") },
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
                audioPlayer = audioPlayer,
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
