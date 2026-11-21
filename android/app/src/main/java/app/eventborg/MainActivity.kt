package app.eventborg

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.eventborg.ui.AppNav
import app.eventborg.ui.EventBorgTheme
import app.eventborg.ui.Lang

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EventBorgTheme { AppNav() } }
    }

    override fun onStart() {
        super.onStart()
        graph.repo.onStart()
    }

    override fun onStop() {
        graph.repo.onStop()
        super.onStop()
    }
}
