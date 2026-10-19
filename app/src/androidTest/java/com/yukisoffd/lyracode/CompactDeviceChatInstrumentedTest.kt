package com.yukisoffd.lyracode

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.overlay.DeviceChatPanel
import com.yukisoffd.lyracode.interaction.overlay.TaskHudController
import com.yukisoffd.lyracode.interaction.session.DeviceApproval
import com.yukisoffd.lyracode.interaction.session.DeviceChatMessage
import com.yukisoffd.lyracode.interaction.session.DeviceChatState
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import org.junit.Assert.*
import org.junit.Test

class CompactDeviceChatInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun panel() = android.view.inspector.WindowInspector.getGlobalWindowViews()
        .flatMap(::descendants).filterIsInstance<DeviceChatPanel>().single()
    private fun button(panel: DeviceChatPanel, resource: Int) = descendants(panel).filterIsInstance<Button>()
        .single { it.contentDescription == panel.context.getString(resource) }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(250) }

    @Test fun liveAppearanceChangesResizeWithoutMovingAndSurviveIpc() {
        val original = ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, chat = DeviceChatState(running = true))
        val appearance = com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions(280, 120, 70, 8)
        val changed = original.copy(chat = original.chat.copy(compactOptions = appearance))
        assertEquals(appearance, com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol.decode(
            com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol.encode(changed))!!.chat.compactOptions)
        lateinit var hud: TaskHudController
        lateinit var panel: DeviceChatPanel
        val before = IntArray(2)
        instrumentation.runOnMainSync {
            hud = TaskHudController(context, {}, {}, {}, {}, {})
            hud.render(original)
            panel = panel()
            button(panel, R.string.floating_compact).performClick()
        }
        try {
            settle()
            instrumentation.runOnMainSync { panel.getLocationOnScreen(before); hud.render(changed) }
            settle()
            instrumentation.runOnMainSync {
                val after = IntArray(2); panel.getLocationOnScreen(after)
                assertArrayEquals(before, after)
                val density = panel.resources.displayMetrics.density
                assertEquals((280 * density).toInt(), panel.width)
                assertEquals((120 * density).toInt(), panel.height)
                val bar = descendants(panel).filterIsInstance<com.yukisoffd.lyracode.interaction.overlay.CompactChatBar>().single()
                val background = bar.background as android.graphics.drawable.GradientDrawable
                assertEquals(appearance.backgroundAlpha, android.graphics.Color.alpha(background.colors!![0]))
            }
        } finally { instrumentation.runOnMainSync { hud.destroy() } }
    }

    @Test fun compactCanDragAndRestoreWithoutLosingDraftOrWindowSize() {
        lateinit var hud: TaskHudController
        lateinit var panel: DeviceChatPanel
        lateinit var editor: EditText
        var fullWidth = 0
        var fullHeight = 0
        val originalLocation = IntArray(2)
        val idle = ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE)
        val running = idle.copy(chat = DeviceChatState(running = true, status = "使用工具 · device_observe",
            messages = listOf(DeviceChatMessage(1, "user", "Check the page"),
                DeviceChatMessage(2, "assistant", "正在检查当前页面，" + "较长的过渡说明".repeat(60)))))
        instrumentation.runOnMainSync {
            hud = TaskHudController(context, {}, {}, {}, {}, {})
            hud.render(idle)
            panel = panel()
            editor = descendants(panel).filterIsInstance<EditText>().single()
            editor.setText("保留草稿")
            assertFalse(button(panel, R.string.floating_compact).isEnabled)
        }
        try {
            settle()
            instrumentation.runOnMainSync {
                fullWidth = panel.width; fullHeight = panel.height
                panel.getLocationOnScreen(originalLocation)
                hud.render(running)
                assertTrue(button(panel, R.string.floating_compact).isEnabled)
                button(panel, R.string.floating_compact).performClick()
            }
            settle()
            val dragLocation = IntArray(2)
            var dragWidth = 0
            var dragHeight = 0
            instrumentation.runOnMainSync {
                assertTrue(panel.height < fullHeight / 2)
                assertEquals((80 * panel.resources.displayMetrics.density).toInt(), panel.height)
                assertFalse(editor.isShown)
                assertFalse(button(panel, R.string.label_new_conversation).isShown)
                assertFalse(button(panel, R.string.floating_more).isShown)
                val preview = descendants(panel).filterIsInstance<TextView>().single { it.isShown && it.text.startsWith("正在检查当前页面") }
                assertTrue(preview.text.endsWith("…"))
                assertEquals(android.text.TextUtils.TruncateAt.END, preview.ellipsize)
                assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.isShown && it.text == "使用工具 · device_observe" })
                preview.getLocationOnScreen(dragLocation)
                dragWidth = preview.width; dragHeight = preview.height
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            try { java.io.File(context.externalCacheDir, "floating-compact-preview.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val dy = if (action == MotionEvent.ACTION_DOWN) 0f else 120 * panel.resources.displayMetrics.density
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    dragLocation[0] + dragWidth / 2f, dragLocation[1] + dragHeight / 2f + dy, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            settle()
            instrumentation.runOnMainSync {
                val moved = IntArray(2); panel.getLocationOnScreen(moved)
                assertTrue("Compact bar did not move", moved[1] > originalLocation[1])
                hud.setChatVisible(false)
                assertFalse(panel.rootView.isShown)
                hud.setChatVisible(true)
                button(panel, R.string.floating_restore).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(fullWidth, panel.width); assertEquals(fullHeight, panel.height)
                val restored = IntArray(2); panel.getLocationOnScreen(restored)
                assertArrayEquals(originalLocation, restored)
                assertTrue(editor.isShown)
                assertSame(editor, descendants(panel).filterIsInstance<EditText>().single())
                assertEquals("保留草稿", editor.text.toString())
            }
        } finally { instrumentation.runOnMainSync { hud.destroy() } }
    }

    @Test fun stopCancelsOnlyOutputAndApprovalOrCompletionRestoresConversation() {
        lateinit var hud: TaskHudController
        lateinit var panel: DeviceChatPanel
        var stopped = 0
        var closed = 0
        val running = ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, chat = DeviceChatState(running = true))
        instrumentation.runOnMainSync {
            hud = TaskHudController(context, {}, {}, { closed++ }, {}, { stopped++ })
            hud.render(running)
            panel = panel()
        }
        try {
            settle()
            instrumentation.runOnMainSync {
                button(panel, R.string.floating_compact).performClick()
                button(panel, R.string.floating_stop_output).performClick()
                assertEquals(1, stopped); assertEquals(0, closed)
                hud.render(running.copy(approval = DeviceApproval("q", "Question", "Please answer", null, false, textInput = true)))
                assertTrue(descendants(panel).filterIsInstance<EditText>().single().isShown)
                assertFalse(button(panel, R.string.floating_compact).isEnabled)
                hud.render(running)
                button(panel, R.string.floating_compact).performClick()
                hud.render(running.copy(chat = DeviceChatState(running = false, status = "已完成")))
                assertTrue(descendants(panel).filterIsInstance<EditText>().single().isShown)
                assertFalse(button(panel, R.string.floating_compact).isEnabled)
            }
        } finally { instrumentation.runOnMainSync { hud.destroy() } }
    }
}
