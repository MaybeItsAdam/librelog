package uk.co.maybeitssoftware.librelog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.librelog.ui.LibrelogTheme

class MainActivity : ComponentActivity() {
    private val model get() = (application as LibrelogApplication).model
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LibrelogTheme { RootScreen(model) }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // First launch adopts the account; returning to the foreground pulls.
        model.scope.launch {
            if (started) model.sync.syncNow() else model.sync.start()
            started = true
        }
    }

    /** The sign-in email's link: librelog://auth#access_token=… */
    private fun handle(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (uri.startsWith("librelog://auth")) model.scope.launch { model.sync.handle(uri) }
    }
}
