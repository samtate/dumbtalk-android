// Shared Dumb UI kit: generated from dumb-kit/ui/Screenshots.kt by sync.py. Edit it there.
package dev.dumbtalk.app.ui

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * README screenshots at a Qin F22 Pro's 640×960, driven by key presses under
 * Robolectric. Subclasses put made-up data in place, then [launch] and
 * [shoot]; images go to docs/screenshots. Needs Robolectric, Roborazzi and
 * the `roborazzi.test.record` system property.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w427dp-h640dp-hdpi")
abstract class Screenshots {
    @get:Rule val compose = createEmptyComposeRule()

    protected val context: Context get() = RuntimeEnvironment.getApplication()

    /** Starts [activity] past its first run, then runs [shots] with it open. */
    protected fun <A : Activity> launch(activity: Class<A>, shots: (ActivityScenario<A>) -> Unit) {
        sizeCheck?.let { RuntimeEnvironment.setQualifiers(it) }
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        ActivityScenario.launch(activity).use { scenario ->
            current = { code -> scenario.onActivity { it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) } }
            step(500)
            shots(scenario)
        }
    }

    private var current: (Int) -> Unit = {}

    protected fun press(vararg codes: Int) = codes.forEach { current(it); step(400) }

    /** Lets [millis] pass: the looper's posted work and Compose's frames both. */
    protected fun step(millis: Long = 50) {
        Thread.sleep(20) // background loads post back to the main thread
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    /** Steps until [text] is on screen: for work that takes real time, like a demo loading. */
    protected fun waitFor(text: String, timeoutMillis: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\"" }
            step(100)
        }
    }

    protected fun shoot(name: String) {
        step(500)
        val folder = sizeCheck?.let { "build/sizes/$it" } ?: "../docs/screenshots"
        compose.onRoot().captureRoboImage("$folder/$name.png")
    }

    /**
     * DUMB_SCREEN=w240dp-h320dp-mdpi (any Robolectric qualifiers) draws the same
     * screens at another size, into app/build/sizes, to check layouts hold up.
     */
    private val sizeCheck: String? = System.getenv("DUMB_SCREEN")?.takeIf { it.isNotBlank() }
}
