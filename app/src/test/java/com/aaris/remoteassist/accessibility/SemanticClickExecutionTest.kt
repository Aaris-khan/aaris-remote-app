package com.aaris.remoteassist.accessibility

import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aaris.remoteassist.ai.AiActionTranslator
import com.aaris.remoteassist.ai.AiSemanticClickResolver
import com.aaris.remoteassist.control.CommandGate
import com.aaris.remoteassist.control.SemanticClickCommand
import com.aaris.remoteassist.control.SemanticClickTarget
import com.aaris.remoteassist.session.LiveLease
import com.aaris.remoteassist.session.SessionRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SemanticClickExecutionTest {
    private lateinit var service: AssistAccessibilityService
    private lateinit var lease: LiveLease
    private lateinit var selectedLabel: AccessibilityNodeInfo
    private lateinit var target: SemanticClickTarget
    private var firstRowClicks = 0
    private var selectedRowClicks = 0
    private var clickAccepted = true

    @Before fun setUp() {
        CommandGate.reset()
        lease = SessionRuntime.activate("native-test", 1)
        service = Robolectric.buildService(AssistAccessibilityService::class.java).create().get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(7)
        fun node(id: String, text: String, bounds: Rect, clickable: Boolean): AccessibilityNodeInfo =
            AccessibilityNodeInfo.obtain().apply {
                packageName = "some.app"
                className = if (clickable) "android.widget.FrameLayout" else "android.widget.TextView"
                viewIdResourceName = id
                this.text = text
                isVisibleToUser = true
                isEnabled = true
                isClickable = clickable
                setBoundsInScreen(bounds)
                shadowOf(this).setAccessibilityWindowInfo(window)
                if (clickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
        val root = node("root", "", Rect(0, 0, 120, 240), false)
        val first = node("some.app:id/row", "", Rect(0, 80, 120, 112), true)
        val second = node("some.app:id/row", "", Rect(0, 120, 120, 152), true)
        selectedLabel = node("some.app:id/title", "resume-final.pdf", Rect(14, 128, 108, 144), false)
        shadowOf(first).addChild(node("some.app:id/title", "resume-old.pdf", Rect(14, 88, 108, 104), false))
        shadowOf(second).addChild(selectedLabel)
        shadowOf(root).addChild(first)
        shadowOf(root).addChild(second)
        shadowOf(first).setOnPerformActionListener { _, _ -> firstRowClicks++; true }
        shadowOf(second).setOnPerformActionListener { _, _ -> selectedRowClicks++; clickAccepted }
        shadowOf(service).setRootInActiveWindow(root)
        shadowOf(Looper.getMainLooper()).idle()
        target = requireNotNull(AiSemanticClickResolver.resolve(
            requireNotNull(AssistAccessibilityService.aiSnapshot()), 0.5, 0.56, 120, 240))
    }

    @After fun tearDown() {
        service.onDestroy()
        SessionRuntime.reset()
        CommandGate.reset()
    }

    private fun click(precondition: () -> Boolean = { true }): Boolean? {
        var applied: Boolean? = null
        val command = SemanticClickCommand(lease.sessionId, lease.leaseSecret, lease.displayGeneration, 1, target)
        AssistAccessibilityService.dispatch(command, precondition) { applied = it }
        shadowOf(Looper.getMainLooper()).idle()
        return applied
    }

    @Test fun nativeClickActivatesOnlyTheCorrectParentOnce() {
        assertEquals(true, click())
        assertEquals(0, firstRowClicks)
        assertEquals(1, selectedRowClicks)
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun nativeRefusalDoesNotTriggerGestureSelectFocusOrAnotherClick() {
        clickAccepted = false
        assertEquals(false, click())
        assertEquals(1, selectedRowClicks)
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun recycledFilenameIsRecheckedImmediatelyBeforeInput() {
        selectedLabel.text = "some-other-file.pdf"
        assertEquals(false, click())
        assertEquals(0, selectedRowClicks)
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun stopPreconditionPreventsNativeInput() {
        assertEquals(false, click { false })
        assertEquals(0, selectedRowClicks)
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }

    @Test fun ordinaryTapStillDispatchesTouchWithoutAnyNativeAction() {
        val command = AiActionTranslator.translate(
            JSONObject().put("action", "tap").put("x", 0.5).put("y", 0.56), lease, 1, 120, 240)
        AssistAccessibilityService.dispatch(command) { }
        shadowOf(Looper.getMainLooper()).idle()
        val gestures = shadowOf(service).gesturesDispatched
        assertEquals(1, gestures.size)
        assertEquals(0, selectedRowClicks)
        gestures.single().callback().onCompleted(gestures.single().description())
    }
}
