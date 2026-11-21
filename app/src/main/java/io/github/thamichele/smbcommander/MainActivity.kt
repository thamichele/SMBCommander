/*
 * MainActivity.kt - the single activity of the app: the screens, the app lock and the links opened from outside
 *
 * Hosts AppRoot, covers it while the optional app lock waits for the owner, and turns the intents of the widget and
 * of
 * other apps into screens. Keeps no data of its own: everything lives in AppViewModel and SmbCommanderApp.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.thamichele.smbcommander.ui.AppLock
import io.github.thamichele.smbcommander.ui.LockScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.thamichele.smbcommander.ui.AppRoot
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme
import io.github.thamichele.smbcommander.ui.theme.LauncherIcon

class MainActivity : FragmentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** With the app lock on: covered until the fingerprint, face or screen lock confirms it is the owner. */
    private val locked = mutableStateOf(false)
    private var stoppedAt = 0L
    private val extras get() = (application as SmbCommanderApp).extras

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            ArancioTheme {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(viewModel, onExit = ::finish)
                    if (locked.value) LockScreen(onUnlock = ::askUnlock, onExit = ::finish)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // locked at every start, and when back after more than half a minute away
        if (extras.lock && (stoppedAt == 0L || System.currentTimeMillis() - stoppedAt > RELOCK_MS)) {
            locked.value = true
            askUnlock()
        }
    }

    private fun askUnlock() {
        if (!extras.lock) {
            locked.value = false
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked.value = false
                extras.lastUnlocked = System.currentTimeMillis()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca SMBCommander")
            .setSubtitle("Impronta, volto o blocco schermo")
            .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // back from an editor: put changed files back on the NAS
        viewModel.syncEdits()
        // back from a share: the temporary copies are not needed any more
        viewModel.cleanShared()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
        if (!locked.value && extras.lock) extras.lastUnlocked = stoppedAt
        // the launcher icon follows the colour chosen in the settings; changed only now that the app is in the
        // background, because some launchers close an app whose launcher entry changes while it is open
        LauncherIcon.sync(this)
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
        /** Away for longer than this, the app asks again. */
        private const val RELOCK_MS = 30_000L
        private const val EXTRA_OPEN = "open"
        private const val EXTRA_SHARE = "share"
        private const val EXTRA_PATH = "path"
        private const val OPEN_BACKUP = "backup"
        private const val OPEN_FOLDER = "folder"

        private fun base(context: Context) = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * open() - the intent that opens the app as it was
         * @param context any context
         *
         * Return: the intent.
         */
        fun open(context: Context): Intent = base(context)

        /**
         * openBackup() - the intent that opens the app on the backup settings
         * @param context any context
         *
         * Return: the intent.
         */
        fun openBackup(context: Context): Intent = base(context).putExtra(EXTRA_OPEN, OPEN_BACKUP)

        /**
         * openFolder() - the intent that opens the app on a folder of the NAS
         * @param context any context
         * @param share the share
         * @param path the folder
         *
         * Return: the intent.
         */
        fun openFolder(context: Context, share: String, path: String): Intent = base(context)
            .putExtra(EXTRA_OPEN, OPEN_FOLDER)
            .putExtra(EXTRA_SHARE, share)
            .putExtra(EXTRA_PATH, path)
            // one PendingIntent per folder: extras alone do not make intents different
            .setData(Uri.parse("smbcommander://folder/" + Uri.encode(share) + "/" + Uri.encode(path)))
    }
}
