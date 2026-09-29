package com.vista.photoeditor.ui.hidden

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/** Empreinte (ou visage) de niveau fort, ou à défaut le code, schéma ou mot de passe de l'appareil. */
private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

/**
 * Le dossier masqué n'a de sens que si le téléphone est verrouillé : sans empreinte ni code,
 * n'importe qui pourrait l'ouvrir.
 */
fun canProtect(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/** Demande l'empreinte ou le code de l'appareil ; [onSuccess] seulement si l'identité est confirmée. */
fun unlock(context: Context, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Dossier masqué")
        .setSubtitle("Confirmez votre identité pour l'ouvrir")
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    prompt.authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Annulé par l'utilisateur : rien à signaler.
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    onFailure(errString.toString())
                }
            }
        },
    )
}
