/*
 * LockScreen.kt - the optional app lock: fingerprint, face or the screen lock of the phone
 *
 * Off unless turned on in the settings; asks Android's biometric prompt and covers the app until it answers.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.thamichele.smbcommander.data.Extras
import io.github.thamichele.smbcommander.ui.theme.Arancio

/** The optional app lock: fingerprint, face or the screen lock of the phone. */
object AppLock {
    /** Strong biometrics or the screen lock; older Android versions only accept weak biometrics in this pair. */
    val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    } else {
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }

    /**
     * available() - tells whether the app lock can be turned on
     * @param context any context
     *
     * False when the phone has no fingerprint, face or screen lock set: turning the lock on would lock the user out.
     *
     * Return: true when a way to unlock exists.
     */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * authenticate() - asks for the fingerprint, face or screen lock of the phone
     * @param activity the screen that shows the request
     * @param extras where the time of the last unlock is kept
     * @param onSuccess called once the owner is confirmed, after the unlock time is saved
     * @param onFailure called when the request is closed without unlocking (back, cancel, too many tries)
     *
     * Return: nothing; the answer arrives through the two callbacks, on the main thread.
     */
    fun authenticate(activity: FragmentActivity, extras: Extras, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                extras.lastUnlocked = System.currentTimeMillis()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(PROMPT_TITLE)
            .setSubtitle(PROMPT_SUBTITLE)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        val asked = runCatching { BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info) }
        if (asked.isFailure) onFailure()
    }

    /**
     * guard() - lets a screen opened from outside the app (Condividi, widget set-up) show only to the owner
     * @param activity the screen opened from outside
     * @param extras the optional functions, with the app lock
     * @param onOpen shows the screen: right away without the lock or shortly after an unlock, else after one
     *
     * Without the lock, or within the same window in which the file picker may reach the NAS, nothing is asked.
     * Otherwise the screen closes unless the owner unlocks: a phone left unlocked on a table must not let anyone
     * browse the folders of the NAS through another app.
     *
     * Return: nothing.
     */
    fun guard(activity: FragmentActivity, extras: Extras, onOpen: () -> Unit) {
        if (extras.pickerOpen()) {
            onOpen()
            return
        }
        authenticate(activity, extras, onSuccess = onOpen, onFailure = activity::finish)
    }

    private const val PROMPT_TITLE = "Sblocca SMBCommander"
    private const val PROMPT_SUBTITLE = "Impronta, volto o blocco schermo"
}

/**
 * LockScreen() - covers the whole app until it is unlocked
 * @param onUnlock called once the owner is confirmed
 * @param onExit called when the user leaves instead
 *
 * Touches do not reach the screens below.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, onExit: () -> Unit) {
    val c = Arancio.colors
    BackHandler(onBack = onExit)
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Server, contentDescription = null, tint = c.accent, modifier = Modifier.size(56.dp))
        Text("SMBCommander è bloccata", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sblocca con l'impronta, il volto o il blocco schermo del telefono.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(onClick = onUnlock) { Text("Sblocca") }
    }
}
