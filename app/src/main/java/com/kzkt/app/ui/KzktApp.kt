package com.kzkt.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kzkt.app.ui.theme.ColorSaver
import com.kzkt.app.ui.theme.KzktTheme
import com.kzkt.app.ui.theme.DefaultThemeColor

private enum class BottomTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TRANSLATE("translate", "Translate", Icons.Filled.Translate, Icons.Outlined.Translate),
    HISTORY("history", "History", Icons.Filled.History, Icons.Outlined.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
private fun UpdateDialogHost(viewModel: MainViewModel) {
    val state = viewModel.updateState.value
    if (state.checking || state.info != null || state.downloading || state.error != null || state.upToDate) {
        com.kzkt.app.ui.component.UpdateDialog(
            checking = state.checking,
            info = state.info,
            downloading = state.downloading,
            downloadProgress = state.downloadProgress,
            error = state.error,
            upToDate = state.upToDate,
            onDownload = { viewModel.downloadAndInstallUpdate() },
            onDismiss = { viewModel.dismissUpdateDialog() },
        )
    }
}

@Composable
fun KzktApp(
    // Files shared into the app via ACTION_SEND (see MainActivity) — loaded once
    // on first composition into the Translate tab's file list.
    initialSharedFiles: List<String> = emptyList(),
) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    LaunchedEffect(initialSharedFiles) {
        if (initialSharedFiles.isNotEmpty()) {
            viewModel.addFiles(initialSharedFiles)
        }
    }

    // ── Self-update dialog (global — visible from any tab) ──
    // Reads updateState inside its own small composable so download-progress
    // ticks (~100/s max) don't recompose the whole Scaffold/NavHost.
    UpdateDialogHost(viewModel)

    val initialDarkTheme = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(initialDarkTheme) }
    var pureBlack by rememberSaveable { mutableStateOf(false) }
    var themeColor by rememberSaveable(stateSaver = ColorSaver) { mutableStateOf(DefaultThemeColor) }

    KzktTheme(
        darkTheme = darkTheme,
        pureBlack = pureBlack,
        themeColor = themeColor,
    ) {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = BottomTab.TRANSLATE.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(BottomTab.TRANSLATE.route) {
                    MainScreen(viewModel = viewModel)
                }
                composable(BottomTab.HISTORY.route) {
                    HistoryScreen(viewModel = viewModel)
                }
                composable(BottomTab.SETTINGS.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        darkTheme = darkTheme,
                        onDarkThemeChange = { darkTheme = it },
                        pureBlack = pureBlack,
                        onPureBlackChange = { pureBlack = it },
                        themeColor = themeColor,
                        onThemeColorChange = { themeColor = it },
                        onNavigateToGlossary = { navController.navigate("glossary") }
                    )
                }
                composable("glossary") {
                    GlossaryScreen(repository = viewModel.glossaryRepo)
                }
            }
        }
    }
}
