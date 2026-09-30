package com.ration.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.ration.app.domain.importing.ImportLimits
import com.ration.app.ui.nav.AppRoot
import com.ration.app.ui.theme.RationTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /** Маршрут из уведомления, текст из «Поделиться». */
    val pendingRoute = mutableStateOf<String?>(null)
    val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { RationTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // Недоверенный ввод: только текст, с ограничением размера.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(ImportLimits.MAX_TEXT_BYTES / 2)
            if (!text.isNullOrBlank()) {
                sharedText.value = text
                pendingRoute.value = "import"
            }
            return
        }
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return
        if (ROUTE_WHITELIST.any { route == it || route.startsWith("$it/") }) {
            val slot = intent.getLongExtra(EXTRA_SLOT_ID, 0)
            pendingRoute.value = if (route.startsWith("log/") && slot > 0) "log/$slot" else route
        }
    }

    fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_SLOT_ID = "slotId"
        private val ROUTE_WHITELIST = setOf("today", "tomorrow", "shopping", "preps", "prep_checklist", "health", "week", "log", "import", "cook", "inventory", "library")
    }
}
