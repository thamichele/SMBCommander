package io.github.thamichele.smbcommander

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        if (savedInstanceState == null) handle(intent)
        setContent {
            ArancioTheme {
                AppRoot(viewModel, onExit = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // back from an editor: put changed files back on the NAS
        viewModel.syncEdits()
    }

    /** Opened from the backup notification or a widget: go straight to the backup or to a folder. */
    private fun handle(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_BACKUP -> viewModel.openBackup()
            OPEN_FOLDER -> {
                val share = intent.getStringExtra(EXTRA_SHARE) ?: return
                viewModel.openFromOutside(share, intent.getStringExtra(EXTRA_PATH).orEmpty())
            }
        }
    }

    companion object {
        private const val EXTRA_OPEN = "open"
        private const val EXTRA_SHARE = "share"
        private const val EXTRA_PATH = "path"
        private const val OPEN_BACKUP = "backup"
        private const val OPEN_FOLDER = "folder"

        private fun base(context: Context) = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun openBackup(context: Context): Intent = base(context).putExtra(EXTRA_OPEN, OPEN_BACKUP)

        fun openFolder(context: Context, share: String, path: String): Intent = base(context)
            .putExtra(EXTRA_OPEN, OPEN_FOLDER)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_PATH, path)
            // one PendingIntent per folder: extras alone do not make intents different
            .setData(Uri.parse("smbcommander://folder/" + Uri.encode(share) + "/" + Uri.encode(path)))
    }
}
