package io.github.thamichele.smbcommander.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme

/**
 * "Condividi" from any app: the shared files go into one of the user's folders on the NAS. The activity stays open
 * until the upload ends, because the permission to read what was shared lasts only as long as it does.
 */
class ShareActivity : ComponentActivity() {

    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.take(streams(intent), intent.getStringExtra(Intent.EXTRA_TEXT))
        setContent {
            ArancioTheme {
                ShareScreen(vm, onDone = { message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }, onCancel = ::finish)
            }
        }
    }

    /**
     * Only documents another app really hands over (content://, from someone else's provider). A file:// path or a
     * URI of our own provider would be read with this app's rights: another app could make it upload the app's
     * private files.
     */
    private fun streams(intent: Intent): List<Uri> = sharedUris(intent).filter {
        it.scheme == ContentResolver.SCHEME_CONTENT && it.authority != "$packageName.files"
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty()
        else -> emptyList()
    }
}
