package com.yukisoffd.lyracode

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRuntimeRecreationTest {
    @Test
    fun recreatedActivityUsesSameRuntimeAndController() {
        FirstUseConsentStore(InstrumentationRegistry.getInstrumentation().targetContext).accept()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var runtime: ChatRuntime
            lateinit var activity: MainActivity
            scenario.onActivity {
                activity = it
                runtime = checkNotNull(it.chatRuntime)
                assertTrue(runtime.isAttached(it))
            }
            val controller = runtime.controller
            scenario.recreate()
            scenario.onActivity {
                assertNotSame(activity, it)
                assertSame(runtime, it.chatRuntime)
                assertSame(controller, checkNotNull(it.chatRuntime).controller)
                assertTrue(runtime.isAttached(it))
            }
        }
    }
}
