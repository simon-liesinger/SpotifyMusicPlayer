package com.musicdownloader.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.musicdownloader.app.ui.components.MiniPlayer
import com.musicdownloader.app.ui.screens.HomeScreen
import com.musicdownloader.app.ui.screens.ImportScreen
import com.musicdownloader.app.ui.screens.NowPlayingScreen
import com.musicdownloader.app.ui.screens.PlaylistDetailScreen
import com.musicdownloader.app.ui.viewmodel.MainViewModel
import com.musicdownloader.app.ui.viewmodel.PlayerViewModel

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.playerState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Main navigation content
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.weight(1f)
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = mainViewModel,
                    onPlaylistClick = { id -> navController.navigate("playlist/$id") },
                    onImportClick = { navController.navigate("import") }
                )
            }

            composable("import") {
                ImportScreen(
                    viewModel = mainViewModel,
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        mainViewModel.resetImportState()
                        navController.popBackStack()
                    }
                )
            }

            composable(
                "playlist/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    mainViewModel = mainViewModel,
                    playerViewModel = playerViewModel,
                    onBack = { navController.popBackStack() },
                    onNowPlayingClick = { navController.navigate("nowplaying") }
                )
            }

            composable("nowplaying") {
                NowPlayingScreen(
                    playerViewModel = playerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Persistent mini player bar at the bottom
        if (playerState.isActive) {
            MiniPlayer(
                playerState = playerState,
                onPlayPause = { playerViewModel.togglePlayPause() },
                onClick = { navController.navigate("nowplaying") }
            )
        }
    }
}
