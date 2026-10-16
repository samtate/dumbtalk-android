package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the demo build with hardware key events through MainActivity, the
 * same path a keypad phone uses. The screen matches the test phone: 640×960
 * at 240 dpi (427×640 dp).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w427dp-h640dp-hdpi", application = TestApp::class)
abstract class KeypadTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun manualClock() {
        // Text fields animate their cursor forever, so Compose never reports
        // idle under Robolectric. Step the clock by hand instead of waiting.
        compose.mainClock.autoAdvance = false
    }

    protected fun step(millis: Long = 50) {
        compose.mainClock.advanceTimeBy(millis)
        Thread.sleep(10) // let demo work on Dispatchers.Default run
    }

    protected fun press(vararg codes: Int) {
        for (code in codes) {
            compose.runOnUiThread {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            step(100)
        }
    }

    /** Everything on screen, for failure messages. */
    protected fun visibleTexts(): String =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .distinct().take(50).joinToString(" | ")

    protected fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun waitFor(text: String, timeoutMillis: Long = 15_000) {
        val matcher = hasText(text, substring = true)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!exists(matcher)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\". On screen: ${visibleTexts()}" }
            step()
        }
    }

    /** Steps the clock until [condition] holds, failing with [what] after the timeout. */
    protected fun waitUntil(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what. On screen: ${visibleTexts()}" }
            step()
        }
    }

    protected fun shoot(name: String) = step(500).let { compose.onRoot().captureRoboImage("../docs/screenshots/$name.png") }
}
