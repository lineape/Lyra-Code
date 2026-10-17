package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.action.TextActionVerifier
import com.yukisoffd.lyracode.interaction.agent.DeviceInteractionPrompt
import com.yukisoffd.lyracode.interaction.agent.DeviceInteractionToolProvider
import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.policy.*
import com.yukisoffd.lyracode.interaction.session.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeviceFullAccessTest {
    @After fun cleanup() {
        ManualControlController.stop()
        ManualControlController.fullAccessEnabled = { false }
    }

    @Test fun insecureDevicesCannotStartOrCompleteAuthentication() {
        var secure = false
        var stored = false
        val state = DeviceFullAccessState({ secure }, { stored }, { stored = it })
        assertFalse(state.isEnabled())
        assertNull(state.beginAuthentication())
        assertFalse(state.completeAuthentication(1))
        secure = true
        val attempt = state.beginAuthentication()!!
        secure = false
        assertFalse(state.completeAuthentication(attempt))
        assertFalse(stored)
    }

    @Test fun canceledAndSupersededAuthenticationCannotGrantAccess() {
        var stored = false
        val state = DeviceFullAccessState({ true }, { stored }, { stored = it })
        val canceled = state.beginAuthentication()!!
        state.cancelAuthentication(canceled)
        assertFalse(state.completeAuthentication(canceled))
        val stale = state.beginAuthentication()!!
        val current = state.beginAuthentication()!!
        assertFalse(state.completeAuthentication(stale))
        assertFalse(state.isEnabled())
        assertTrue(state.completeAuthentication(current))
        assertFalse(state.completeAuthentication(current))
        assertTrue(state.isEnabled())
    }

    @Test fun disablingRevokesStoredGrantAndPendingCallbacks() {
        var stored = false
        val state = DeviceFullAccessState({ true }, { stored }, { stored = it })
        assertTrue(state.completeAuthentication(state.beginAuthentication()!!))
        val pending = state.beginAuthentication()!!
        state.disable()
        assertFalse(stored)
        assertFalse(state.completeAuthentication(pending))
        assertFalse(state.isEnabled())
    }

    @Test fun persistedGrantIsRevokedWhenLockCredentialsAreRemoved() {
        var secure = true
        var stored = false
        fun restored() = DeviceFullAccessState({ secure }, { stored }, { stored = it })
        val state = restored()
        assertTrue(state.completeAuthentication(state.beginAuthentication()!!))
        assertTrue(restored().isEnabled())
        secure = false
        assertFalse(restored().isEnabled())
        secure = true
        assertFalse(restored().isEnabled())
    }

    @Test fun fullAccessAllowsProtectedAppsFinancialControlsAndCredentialFields() {
        for (pkg in listOf("com.android.settings", "com.android.permissioncontroller", "com.android.systemui", "example.password.manager")) {
            assertFalse(DeviceTaskCoordinator.isAgentPackageAllowed(pkg))
            assertTrue(DeviceTaskCoordinator.isAgentPackageAllowed(pkg, fullAccess = true))
        }
        for (label in listOf("确认付款", "买入", "授权", "删除钱包", "验证码")) {
            val target = node().copy(text = label)
            assertTrue(DeviceActionPolicy.evaluate("example.app", target, ManualDeviceAction.ACTIVATE) is DevicePolicyDecision.Blocked)
            assertEquals(DevicePolicyDecision.Allowed,
                DeviceActionPolicy.evaluate("example.app", target, ManualDeviceAction.ACTIVATE, fullAccess = true))
        }
        val field = node().copy(editable = true, password = true, accessibilityDataSensitive = true,
            inputType = 0x12, actions = setOf(SemanticAction.SET_TEXT), textFingerprint = TextInputPolicy.fingerprint(""))
        assertTrue(DeviceActionPolicy.evaluate("example.app", field, ManualDeviceAction.SET_TEXT) is DevicePolicyDecision.Blocked)
        assertEquals(DevicePolicyDecision.Allowed,
            DeviceActionPolicy.evaluate("example.app", field, ManualDeviceAction.SET_TEXT, fullAccess = true))
    }

    @Test fun fullAccessStillRequiresMatchingVisibleEnabledNativeTargets() {
        for (target in listOf(node().copy(packageName = "other.app"), node().copy(visible = false),
            node().copy(enabled = false), node().copy(actions = emptySet()))) {
            assertTrue(DeviceActionPolicy.evaluate("example.app", target, ManualDeviceAction.ACTIVATE, fullAccess = true) is DevicePolicyDecision.Blocked)
        }
    }

    @Test fun authenticatedDeletionExecutesWithoutSecondApprovalButRevocationBlocksPendingSensitiveAction() {
        var enabled = true
        ManualControlController.fullAccessEnabled = { enabled }
        ManualControlController.start()
        ManualControlController.publish(snapshot(node().copy(text = "Delete wallet")))
        assertTrue(ManualControlController.select("target", ManualDeviceAction.ACTIVATE, automatic = true))
        val selection = ManualControlController.state.value.selection!!
        assertNotNull(ManualControlController.beginExecution("before", "target", selection.confirmationToken))
        ManualControlController.clearSelection()
        assertTrue(ManualControlController.select("target", ManualDeviceAction.ACTIVATE, automatic = true))
        val pending = ManualControlController.state.value.selection!!
        enabled = false
        assertNull(ManualControlController.beginExecution("before", "target", pending.confirmationToken))
        assertNull(ManualControlController.state.value.selection)
    }

    @Test fun fullAccessCanObserveSystemAppsAndRestoresRedactionAfterRevocation() = runBlocking {
        var enabled = true
        val provider = start { enabled }
        val field = node().copy(password = true, text = "visible test value")
        ManualControlController.publish(snapshot(field))
        val full = JSONObject(provider.execute("device_observe", JSONObject())).getJSONArray("nodes").getJSONObject(0)
        assertEquals("visible test value", full.getString("text"))
        assertFalse(full.getBoolean("redacted"))
        enabled = false
        val standard = JSONObject(provider.execute("device_observe", JSONObject())).getJSONArray("nodes").getJSONObject(0)
        assertTrue(standard.getBoolean("redacted"))
        assertEquals("[已遮蔽]", standard.getString("text"))
        enabled = true
        ManualControlController.publish(snapshot(node().copy(packageName = "com.android.settings"), "com.android.settings"))
        val system = JSONObject(provider.execute("device_observe", JSONObject()))
        assertEquals("com.android.settings", system.getString("package"))
    }

    @Test fun revokedGrantCannotAuthorizeAnAutomaticallySelectedSendEvenIfStandardPolicyAllowsItsControl() {
        var enabled = true
        ManualControlController.fullAccessEnabled = { enabled }
        ManualControlController.start()
        ManualControlController.publish(snapshot(node().copy(text = "Send")))
        assertTrue(ManualControlController.select("target", ManualDeviceAction.ACTIVATE, automatic = true, requiresFullAccess = true))
        val pending = ManualControlController.state.value.selection!!
        enabled = false
        assertNull(ManualControlController.beginExecution("before", "target", pending.confirmationToken))
        assertFalse(ManualControlController.select("target", ManualDeviceAction.ACTIVATE, automatic = true, requiresFullAccess = true))
        enabled = true
        assertTrue(ManualControlController.select("target", ManualDeviceAction.ACTIVATE, automatic = true, requiresFullAccess = true))
        val selected = ManualControlController.state.value.selection!!
        assertNotNull(ManualControlController.beginExecution("before", "target", selected.confirmationToken))
        enabled = false
        assertFalse(ManualControlController.dispatchIfCurrent(selected) { fail("Dispatch after revocation"); true })
    }

    @Test fun sensitiveActivationIsAutomaticAndStillRequiresVerifiedNativeResult() = runBlocking {
        val provider = start { true }
        ManualControlController.publish(snapshot(node().copy(text = "确认付款")))
        provider.execute("device_observe", JSONObject())
        val action = async { provider.execute("device_activate", JSONObject().put("snapshot_id", "before").put("element_handle", "target")) }
        withTimeout(2000) { while (ManualControlController.state.value.selection == null) delay(10) }
        val selection = ManualControlController.state.value.selection!!
        assertTrue(selection.automatic)
        assertFalse(action.isCompleted)
        val before = ManualControlController.beginExecution("before", "target", selection.confirmationToken)!!.second
        ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, ManualDeviceAction.ACTIVATE,
            "example.app", "example.app", "target", "node_action:16", before.uiFingerprint, "after", selection.requestId))
        assertEquals("SUCCEEDED", JSONObject(withTimeout(2000) { action.await() }).getString("status"))
    }

    @Test fun capabilityCheckWorksWithoutAccessibilityOrAnObservationAndStopsWithSession() = runBlocking {
        val provider = start { false }
        provider.deviceAvailable = { false }
        ManualControlController.clearDeviceState()
        var checks = 0
        provider.capabilityProbe = { checks++; "{\"adb_shell\":{\"available\":false},\"full_access_enabled\":false}" }
        val result = JSONObject(provider.execute("device_get_capabilities", JSONObject()))
        assertFalse(result.getJSONObject("adb_shell").getBoolean("available"))
        assertEquals(1, checks)
        ManualControlController.stop()
        assertTrue(runCatching { provider.execute("device_get_capabilities", JSONObject()) }.isFailure)
        assertEquals(1, checks)
    }

    @Test fun promptsChangeWithAuthorizationAndRequireShellCheckAndCombinedMethods() {
        var enabled = false
        val provider = start { enabled }
        assertTrue(provider.systemPrompt.contains("device_get_capabilities"))
        assertTrue(provider.systemPrompt.contains("ADB Shell + accessibility + image recognition"))
        assertTrue(provider.systemPrompt.contains("forbidden actions"))
        enabled = true
        assertTrue(provider.systemPrompt.contains("DEVICE FULL ACCESS IS ENABLED"))
        assertFalse(provider.systemPrompt.contains("forbidden actions"))
        assertFalse(provider.systemPrompt.contains("require two distinct approvals"))
        assertTrue(DeviceInteractionPrompt.build("example.app", false).contains("STANDARD DEVICE ACCESS"))
    }

    @Test fun fullAccessCanClearAndVerifyNumericSensitiveTextFields() {
        assertFalse(TextInputPolicy.isValidText(""))
        assertTrue(TextInputPolicy.isValidText("", fullAccess = true))
        val field = node().copy(editable = true, password = true, inputType = 0x12,
            actions = setOf(SemanticAction.SET_TEXT), textFingerprint = TextInputPolicy.fingerprint("123"))
        val before = snapshot(field)
        val after = snapshot(field.copy(textFingerprint = TextInputPolicy.fingerprint("")))
        val selection = ManualActionSelection("before", "target", ManualDeviceAction.SET_TEXT, "example.app", inputText = "")
        assertEquals(DeviceActionStatus.SUCCEEDED, TextActionVerifier.verify(selection, before, after, fullAccess = true))
        assertEquals(DeviceActionStatus.BLOCKED, TextActionVerifier.verify(selection, before, after))
    }

    private fun start(enabled: () -> Boolean): DeviceInteractionToolProvider {
        ManualControlController.fullAccessEnabled = enabled
        ManualControlController.start()
        ManualControlController.publish(snapshot(node()))
        ManualControlController.updateChat(DeviceChatState(running = true))
        return DeviceInteractionToolProvider(123, "example.app", ManualControlController.state.value.activeUntilEpochMillis,
            ExecutionBudget({ 0 }), {}, {}, {}).also { it.fullAccessEnabled = enabled }
    }

    private fun snapshot(node: SemanticNode, pkg: String = "example.app") = ScreenSnapshot("before",
        System.currentTimeMillis(), ScreenDisplay(0, 0, 1080, 1920), pkg, 1, emptyList(), listOf(node),
        "${node.text}:${node.password}:$pkg", false)

    private fun node() = SemanticNode("target", 1, null, 0, "button", "Button", "example.app", "Open details", null,
        "example.app:id/control", ScreenBounds(10, 10, 100, 100), setOf(SemanticAction.ACTIVATE),
        true, true, false, true, false, false, true, false, false, false, false, false, false)
}
