package io.github.thamichele.smbcommander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.thamichele.smbcommander.ui.AppRoot
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArancioTheme {
                AppRoot(viewModel, onExit = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // back from an editor: put changed files back on the NAS
        viewModel.syncEdits()
    }
}
