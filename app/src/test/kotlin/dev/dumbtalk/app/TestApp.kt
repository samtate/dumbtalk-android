package dev.dumbtalk.app

import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.demo.DemoService

/** Demo services instead of the native bridges, which can't load on the JVM. */
open class TestApp : DumbTalkApp() {
    override val usePixelFallbackFonts = false
    override val guidesUser = false

    /** How many times the app asked to restart (demo, backup); a real restart would end the test JVM. */
    var restarts = 0
    override fun restart(activity: android.app.Activity) {
        restarts++
    }

    override fun createServices(): List<MessagingService> = ServiceId.entries.filter { it != ServiceId.SMS }.map { DemoService(it, scope) }
}

/** A first run: the welcome comes before the inbox. */
class FirstRunTestApp : TestApp() {
    override val guidesUser = true
}
