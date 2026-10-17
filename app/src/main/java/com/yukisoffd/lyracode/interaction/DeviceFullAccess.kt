package com.yukisoffd.lyracode.interaction

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Build
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.policy.DeviceFullAccessState

/** Device-local authorization; credentials and biometric data never enter the application. */
internal object DeviceFullAccess {
    private var authorization: DeviceFullAccessState? = null
    private var interactionEnabled: () -> Boolean = { false }

    fun isDeviceSecure(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    @Synchronized private fun state(context: Context): DeviceFullAccessState {
        authorization?.let { return it }
        val app = context.applicationContext
        val settings = AppSettings(app)
        interactionEnabled = { settings.deviceInteractionExperimentalEnabled }
        // Deliberately separate from AppSettings: never imported/exported or transferred in backups.
        val preferences = app.getSharedPreferences("lyra_device_full_access", Context.MODE_PRIVATE)
        return DeviceFullAccessState(
            deviceSecure = { DeviceInteractionAvailability.isSupported() && isDeviceSecure(app) },
            readGrant = { preferences.getBoolean("authenticated_grant", false) },
            writeGrant = { enabled ->
                check(preferences.edit().putBoolean("authenticated_grant", enabled).commit())
            },
        ).also { authorization = it }
    }

    fun isEnabled(context: Context): Boolean = state(context).isEnabled() && interactionEnabled()

    fun disable(context: Context) = state(context).disable()

    fun authenticate(context: Context, onResult: (Boolean, String?) -> Unit): CancellationSignal {
        val signal = CancellationSignal()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(false, context.getString(R.string.device_interaction_status_unsupported))
            return signal
        }
        val state = state(context)
        val attempt = state.beginAuthentication()
        if (attempt == null || !interactionEnabled()) {
            attempt?.let(state::cancelAuthentication)
            onResult(false, context.getString(R.string.device_full_access_requires_lock))
            return signal
        }
        signal.setOnCancelListener { state.cancelAuthentication(attempt) }
        try {
            BiometricPrompt.Builder(context)
                .setTitle(context.getString(R.string.device_full_access_auth_title))
                .setDescription(context.getString(R.string.device_full_access_auth_desc))
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (signal.isCanceled) return
                        val enabled = runCatching {
                            interactionEnabled() && state.completeAuthentication(attempt)
                        }.getOrDefault(false)
                        if (!enabled) state.cancelAuthentication(attempt)
                        onResult(enabled, if (enabled) null else context.getString(R.string.device_full_access_auth_failed))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        state.cancelAuthentication(attempt)
                        if (!signal.isCanceled) onResult(false, errString.toString())
                    }
                })
        } catch (_: Exception) {
            state.cancelAuthentication(attempt)
            onResult(false, context.getString(R.string.device_full_access_auth_failed))
        }
        return signal
    }
}
