package com.ration.app

import android.content.Intent
import android.net.Uri
import android.os.Build
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
    /** Документ из «Поделиться» (PDF/JPG/PNG) — читается сразу в «Документах», URI наружу не уходит. */
    val sharedDocument = mutableStateOf<Pair<Uri, String>?>(null)

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
        if (intent.action == Intent.ACTION_SEND && (intent.type ?: "") in DOC_MIMES) {
            val uri: Uri? = streamUri(intent)
            if (uri != null) {
                sharedDocument.value = uri to intent.type!!
                pendingRoute.value = "documents"
            }
            return
        }
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return
        val slot = intent.getLongExtra(EXTRA_SLOT_ID, 0)
        // Только точные маршруты из списка; номер слота — отдельным числовым extra (никаких строк извне в аргументах).
        pendingRoute.value = when {
            route == "slot" && slot > 0 -> "build/$slot/0"
            route == "slot" -> "today"
            route in ROUTE_WHITELIST -> route
            else -> null
        } ?: return
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_SLOT_ID = "slotId"
        private val ROUTE_WHITELIST = setOf("today", "tomorrow", "shopping", "preps", "prep_checklist", "health", "week", "import", "inventory", "library", "cook", "nutrition",
            "workouts", "form", "condition", "documents")
        private val DOC_MIMES = setOf("application/pdf", "image/jpeg", "image/png")
    }
}
