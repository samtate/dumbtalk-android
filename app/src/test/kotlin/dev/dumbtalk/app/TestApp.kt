package dev.dumbtalk.app

import dev.dumbtalk.core.MessagingService
import dev.dumbtalk.core.ServiceId
import dev.dumbtalk.core.demo.DemoService

/** Demo services instead of the native bridges, which can't load on the JVM. */
class TestApp : DumbTalkApp() {
    override val usePixelFallbackFonts = false

    override fun createServices(): List<MessagingService> = ServiceId.entries.filter { it != ServiceId.SMS }.map { DemoService(it, scope) }
}
