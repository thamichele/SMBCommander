package io.github.thamichele.smbcommander.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
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
import io.github.thamichele.smbcommander.ui.theme.Arancio

/** The optional app lock: fingerprint, face or the screen lock of the phone. */
object AppLock {
    /** Strong biometrics or the screen lock; older Android versions only accept weak biometrics in this pair. */
    val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    } else {
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }

    /** False when the phone has no fingerprint, face or screen lock set: turning the lock on would lock out. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

/** Covers the whole app until it is unlocked; touches do not reach the screens below. */
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
        Button(onClick = onUnlock, shape = RoundedCornerShape(8.dp)) { Text("Sblocca") }
    }
}
