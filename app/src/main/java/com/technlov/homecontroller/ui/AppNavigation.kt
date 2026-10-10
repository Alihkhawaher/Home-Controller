package com.technlov.homecontroller.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.technlov.home.core.provisioningSupport
import com.technlov.homecontroller.R
import com.technlov.homecontroller.app
import com.technlov.homecontroller.ui.pairing.PairingScreen
import com.technlov.homecontroller.ui.settings.DiagnosticsScreen
import com.technlov.homecontroller.ui.automations.AutomationEditorScreen
import com.technlov.homecontroller.ui.automations.AutomationsScreen
import com.technlov.homecontroller.ui.device.DeviceScreen
import com.technlov.homecontroller.ui.discovery.DiscoveryScreen
import com.technlov.homecontroller.ui.home.HomeScreen
import com.technlov.homecontroller.ui.settings.AboutScreen
import com.technlov.homecontroller.ui.settings.PluginsScreen
import com.technlov.homecontroller.ui.settings.RoomsScreen
import com.technlov.homecontroller.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val AUTOMATIONS = "automations"
    const val SETTINGS = "settings"
    const val DISCOVER = "discover"
    const val DEVICE = "device/{key}"
    const val AUTOMATION = "automation/{id}?device={device}"
    const val PLUGINS = "settings/plugins"
    const val ABOUT = "settings/about"
    const val ROOMS = "settings/rooms"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val PAIR = "pair"
    const val NEW = "new"

    fun device(key: String) = "device/${Uri.encode(key)}"
    fun automation(id: String?, device: String? = null) =
        "automation/${Uri.encode(id ?: NEW)}" + (device?.let { "?device=${Uri.encode(it)}" } ?: "")
}

private data class TopLevel(val route: String, val label: Int, val selected: ImageVector, val unselected: ImageVector)

private val topLevels = listOf(
    TopLevel(Routes.HOME, R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home),
    TopLevel(Routes.AUTOMATIONS, R.string.nav_automations, Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome),
    TopLevel(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
)

/** Goes back once, ignoring repeated taps while the screen is already leaving. */
private fun NavHostController.backFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}

@Composable
fun HomeControllerApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    // "Set up a new device" shows only when an installed plugin can adopt devices.
    val canPair = remember { context.app.registry.all.any { it.provisioningSupport != null } }
    val pair: (() -> Unit)? = if (canPair) ({ nav.navigate(Routes.PAIR) }) else null
    val current by nav.currentBackStackEntryAsState()
    val destination = current?.destination
    val showBar = topLevels.any { top -> destination?.hierarchy?.any { it.route == top.route } == true }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(visible = showBar, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                NavigationBar {
                    topLevels.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.route == top.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(top.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) top.selected else top.unselected, contentDescription = null) },
                            label = { Text(stringResource(top.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(260)) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.97f) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenDevice = { nav.navigate(Routes.device(it)) },
                    onFindDevices = { nav.navigate(Routes.DISCOVER) },
                    onPairDevice = pair,
                )
            }
            composable(Routes.AUTOMATIONS) {
                AutomationsScreen(onEdit = { nav.navigate(Routes.automation(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenRooms = { nav.navigate(Routes.ROOMS) },
                    onOpenPlugins = { nav.navigate(Routes.PLUGINS) },
                    onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                    onOpenAbout = { nav.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.DISCOVER) { entry ->
                DiscoveryScreen(
                    onBack = { nav.backFrom(entry) },
                    onOpenDevice = { nav.navigate(Routes.device(it)) },
                    onPairDevice = pair,
                )
            }
            composable(Routes.PAIR) { entry ->
                PairingScreen(
                    onBack = { nav.backFrom(entry) },
                    onOpenDevice = { key ->
                        nav.navigate(Routes.device(key)) {
                            popUpTo(Routes.PAIR) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.DEVICE, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                val key = entry.arguments?.getString("key").orEmpty()
                DeviceScreen(
                    deviceKey = key,
                    onBack = { nav.backFrom(entry) },
                    onOpenSchedule = { id -> nav.navigate(Routes.automation(id, device = key)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                Routes.AUTOMATION,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("device") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("id")?.takeIf { it != Routes.NEW }
                AutomationEditorScreen(
                    automationId = id,
                    onDone = { nav.backFrom(entry) },
                    prefillDevice = entry.arguments?.getString("device"),
                )
            }
            composable(Routes.PLUGINS) { entry -> PluginsScreen(onBack = { nav.backFrom(entry) }) }
            composable(Routes.ABOUT) { entry -> AboutScreen(onBack = { nav.backFrom(entry) }) }
            composable(Routes.ROOMS) { entry -> RoomsScreen(onBack = { nav.backFrom(entry) }) }
            composable(Routes.DIAGNOSTICS) { entry -> DiagnosticsScreen(onBack = { nav.backFrom(entry) }) }
        }
    }
}
