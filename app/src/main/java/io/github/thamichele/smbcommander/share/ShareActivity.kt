/*
 * ShareActivity.kt - Condividi from any app: the shared files go into a folder of the NAS
 *
 * Stays open until the upload ends, because the permission to read what was shared lasts only as long as it does.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.ui.AppLock
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme

/**
 * "Condividi" from any app: the shared files go into one of the user's folders on the NAS. The activity stays open
 * until the upload ends, because the permission to read what was shared lasts only as long as it does.
 */
class ShareActivity : FragmentActivity() {

    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.take(streams(intent), intent.getStringExtra(Intent.EXTRA_TEXT))
        // with the app lock on, the folders of the NAS show only to the owner
        AppLock.guard(this, (application as SmbCommanderApp).extras, ::show)
    }

    private fun show() {
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
     * URI of one of our own providers (the cached copies, the NAS in the file picker) would be read with this app's
     * rights: another app could make it upload the app's private files, or files of the NAS it cannot reach itself.
     */
    private fun streams(intent: Intent): List<Uri> = sharedUris(intent).filter {
        it.scheme == ContentResolver.SCHEME_CONTENT && !isOwnAuthority(it.authority)
    }

    /** Every provider of this app is named "<application id>.something"; the debug build has an id of its own. */
    private fun isOwnAuthority(authority: String?): Boolean =
        authority == null || authority == packageName || authority.startsWith("$packageName.")

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
