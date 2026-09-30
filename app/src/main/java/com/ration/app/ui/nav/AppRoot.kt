package com.ration.app.ui.nav

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ration.app.MainActivity
import com.ration.app.data.settings.SettingsRepository
import com.ration.app.domain.model.AppSettings
import com.ration.app.ui.blocks.BlockDetailScreen
import com.ration.app.ui.blocks.BlocksScreen
import com.ration.app.ui.health.HealthScreen
import com.ration.app.ui.health.ReportScreen
import com.ration.app.ui.more.MoreScreen
import com.ration.app.ui.more.TomorrowScreen
import com.ration.app.ui.more.WeekScreen
import com.ration.app.ui.pantry.PantryScreen
import com.ration.app.ui.preps.PrepChecklistScreen
import com.ration.app.ui.preps.PrepsScreen
import com.ration.app.ui.purchases.ImportScreen
import com.ration.app.ui.purchases.PurchasesScreen
import com.ration.app.ui.purchases.ShoppingScreen
import com.ration.app.ui.settings.SettingsScreen
import com.ration.app.ui.today.LogScreen
import com.ration.app.ui.today.TodayScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Сегодня", Icons.Filled.Home),
    Tab("pantry", "Кладовая", Icons.AutoMirrored.Filled.List),
    Tab("purchases", "Закупки", Icons.Filled.ShoppingCart),
    Tab("preps", "Заготовки", Icons.Filled.Build),
    Tab("more", "Ещё", Icons.Filled.Menu),
)

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val settings = settings.settings
}

@Composable
fun AppRoot(activity: MainActivity) {
    val vm: RootViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings(seeded = true))
    var unlocked by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) unlocked = false }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (settings.biometricLock && !unlocked) {
        LockScreen(activity) { unlocked = true }
        return
    }
    val nav = rememberNavController()
    val pending by activity.pendingRoute
    LaunchedEffect(pending) {
        pending?.let { route ->
            nav.navigate(route) { launchSingleTop = true }
            activity.pendingRoute.value = null
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (current in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad)) {
            composable("today") { TodayScreen(nav) }
            composable("pantry") { PantryScreen(nav) }
            composable("purchases") { PurchasesScreen(nav) }
            composable("preps") { PrepsScreen(nav) }
            composable("more") { MoreScreen(nav) }
            composable("tomorrow") { TomorrowScreen(nav) }
            composable("week") { WeekScreen(nav) }
            composable("health") { HealthScreen(nav, activity) }
            composable("report") { ReportScreen(nav, activity) }
            composable("settings") { SettingsScreen(nav) }
            composable("import") {
                val shared by activity.sharedText
                ImportScreen(nav, shared) { activity.sharedText.value = null }
            }
            composable("shopping") { ShoppingScreen(nav) }
            composable("prep_checklist") { PrepChecklistScreen(nav) }
            composable("blocks") { BlocksScreen(nav) }
            composable("block/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                BlockDetailScreen(nav, it.arguments?.getLong("id") ?: 0)
            }
            composable("log/{slotId}", arguments = listOf(navArgument("slotId") { type = NavType.LongType })) {
                LogScreen(nav, it.arguments?.getLong("slotId") ?: 0)
            }
        }
    }
}

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun LockScreen(activity: MainActivity, onUnlocked: () -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuth = remember { BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS }
    fun prompt() {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Рацион")
                .setSubtitle("Разблокировка приложения")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
    LaunchedEffect(Unit) { if (canAuth) prompt() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Приложение заблокировано", style = MaterialTheme.typography.titleLarge)
        if (canAuth) {
            Button(onClick = { prompt() }, modifier = Modifier.padding(top = 16.dp)) { Text("Разблокировать") }
        } else {
            Text("На устройстве не настроены биометрия или PIN — блокировка не может работать.", modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onUnlocked, modifier = Modifier.padding(top = 16.dp)) { Text("Продолжить") }
        }
    }
}

/** FLAG_SECURE на время показа экрана: нет скриншотов и превью в недавних. */
@Composable
fun SecureScreen(activity: MainActivity) {
    DisposableEffect(Unit) {
        activity.setSecure(true)
        onDispose { activity.setSecure(false) }
    }
}
