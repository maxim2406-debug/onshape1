package com.ration.app.ui.health

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.ration.app.MainActivity
import com.ration.app.ui.components.BackTopBar
import com.ration.app.ui.nav.SecureScreen

/**
 * 20.7: разделы «Состояние» и «Документы» открываются только после биометрии или PIN устройства.
 * Разблокировка живёт 60 секунд после ухода с экрана (сворачивание приложения или выход из раздела).
 */
object HealthLock {
    private const val GRACE_MS = 60_000L
    @Volatile private var unlocked = false
    @Volatile private var leftAt = 0L

    fun isUnlocked(): Boolean = unlocked && (leftAt == 0L || SystemClock.elapsedRealtime() - leftAt < GRACE_MS)
    fun unlock() { unlocked = true; leftAt = 0L }
    fun left() { if (unlocked) leftAt = SystemClock.elapsedRealtime() }
    fun returned() { if (isUnlocked()) leftAt = 0L else unlocked = false }
}

@Composable
fun HealthGate(nav: NavController, activity: MainActivity, title: String, content: @Composable () -> Unit) {
    SecureScreen(activity)
    var open by remember { mutableStateOf(HealthLock.isUnlocked()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> HealthLock.left()
                Lifecycle.Event.ON_START -> { HealthLock.returned(); open = HealthLock.isUnlocked() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); HealthLock.left() }
    }
    if (open) { content(); return }

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuth = remember { BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS }
    fun prompt() {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { HealthLock.unlock(); open = true }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Данные о здоровье")
                .setAllowedAuthenticators(authenticators).build(),
        )
    }
    LaunchedEffect(Unit) { if (canAuth) prompt() }
    Scaffold(topBar = { BackTopBar(title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Раздел защищён", style = MaterialTheme.typography.titleLarge)
            if (canAuth) {
                Button(onClick = { prompt() }, modifier = Modifier.padding(top = 16.dp)) { Text("Разблокировать") }
            } else {
                Text("На устройстве не настроены биометрия или PIN — защитить раздел нельзя. Настройте блокировку экрана в системе.",
                    modifier = Modifier.padding(top = 16.dp))
                Button(onClick = { HealthLock.unlock(); open = true }, modifier = Modifier.padding(top = 16.dp)) { Text("Открыть без защиты") }
            }
        }
    }
}
