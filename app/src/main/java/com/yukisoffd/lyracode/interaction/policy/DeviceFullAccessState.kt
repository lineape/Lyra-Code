package com.yukisoffd.lyracode.interaction.policy

/** Only a successful, current system-authentication attempt can persist a grant. */
internal class DeviceFullAccessState(
    private val deviceSecure: () -> Boolean,
    private val readGrant: () -> Boolean,
    private val writeGrant: (Boolean) -> Unit,
) {
    private var generation = 0L
    private var pending: Long? = null

    @Synchronized fun isEnabled(): Boolean {
        if (!readGrant()) return false
        if (!deviceSecure()) {
            disable()
            return false
        }
        return true
    }

    @Synchronized fun beginAuthentication(): Long? {
        if (!deviceSecure()) {
            disable()
            return null
        }
        return (++generation).also { pending = it }
    }

    @Synchronized fun completeAuthentication(attempt: Long): Boolean {
        if (pending != attempt) return false
        pending = null
        if (!deviceSecure()) {
            disable()
            return false
        }
        writeGrant(true)
        return readGrant()
    }

    @Synchronized fun cancelAuthentication(attempt: Long) {
        if (pending == attempt) pending = null
    }

    @Synchronized fun disable() {
        pending = null
        if (readGrant()) writeGrant(false)
    }
}
