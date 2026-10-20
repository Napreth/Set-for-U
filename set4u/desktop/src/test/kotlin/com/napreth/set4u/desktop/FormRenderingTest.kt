package com.napreth.set4u.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.form.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.ui.*
import com.napreth.set4u.ui.form.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
class FormRenderingTest {
    @Test fun inlineFormsUsePrivateRenderersAndFallbacksInAllThemes() {
        SwingUtilities.invokeAndWait {
            for (theme in AppTheme.entries) {
                val name = TextField("name", "Network name", rendererKey = "name", validate = { if (it.isBlank()) "Name required" else null })
                val show = ToggleField("show", "Show password")
                val secret = PasswordField("password", "Password", visibleWhen = { it[show] })
                val other = TextField("other", "Fallback", rendererKey = "unknown", enabledWhen = { false })
                val definition = FormDefinition("example", "Example", listOf(name, show, secret, other)) { it[name] }
                val session = FormSession(definition)
                val renderers = FormRenderers(mapOf("example" to mapOf("name" to { _, default -> Column { ThemeText("Private input"); default() } })))
                val scene = ImageComposeScene(650, 1100, Density(1f), coroutineContext = Dispatchers.Swing.immediate) {
                    CompositionLocalProvider(LocalAppFontFamily provides DesktopFonts.ui) { AppThemeContent(theme, false) { FormRenderer(session, renderers = renderers) } }
                }
                val frames = Frames(scene)
                try {
                    assertTrue("Private input" in frames.text()); assertTrue("Fallback" in frames.text())
                    assertFalse(frames.nodes().any { it.config.getOrNull(SemanticsProperties.Password) != null })
                    session.validate(); assertTrue("Name required" in frames.text())
                    val input = frames.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null && it.config.getOrNull(SemanticsProperties.Disabled) == null }
                    assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Edited")))
                    assertEquals("Edited", session.state.value.values[name]); assertTrue(session.state.value.dirty)
                    assertFalse("Name required" in frames.text())
                    val toggle = frames.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Show password") == true && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    toggle.config[SemanticsActions.OnClick].action!!.invoke()
                    assertTrue(frames.nodes().any { it.config.getOrNull(SemanticsProperties.Password) != null })
                } finally { session.close(); scene.close() }
            }
        }
    }

    @Test fun dialogDraftSurvivesThemeChangesAndIsReleasedByItsOwner() {
        SwingUtilities.invokeAndWait {
            val controller = AppController(Platform(), Dispatchers.Swing.immediate)
            val password = PasswordField("password", "Password", rendererKey = "private")
            val definition = FormDefinition("shared", "Shared form", listOf(password)) { it[password] }
            controller.showForm(definition) { error("No device operation expected") }
            val first = controller.state.value.dialog as AppDialog.Form<*>
            first.session.editValue(password, "Draft-only-secret")
            val renderers = FormRenderers(mapOf("shared" to mapOf("private" to { _, default -> Column { ThemeText("Custom password"); default() } })))
            val scene = ImageComposeScene(620, 1000, Density(1f), coroutineContext = Dispatchers.Swing.immediate) { Set4uApp(controller, formRenderers = renderers) }
            val frames = Frames(scene)
            try {
                for (theme in AppTheme.entries) {
                    controller.selectTheme(theme)
                    assertTrue("Form renderer missing in $theme", "Custom password" in frames.text())
                    assertEquals("Draft-only-secret", first.session.state.value.values[password])
                    assertFalse(first.session.state.value.closed)
                }
                controller.showForm(definition) { }
                val second = controller.state.value.dialog as AppDialog.Form<*>
                assertTrue(first.session.state.value.closed); assertFalse(first.session.state.value.values.contains(password))
                controller.dismissDialog(first); assertSame(second, controller.state.value.dialog)
                controller.close(); assertTrue(second.session.state.value.closed)
            } finally { scene.close(); controller.close() }
        }
    }

    private class Frames(private val scene: ImageComposeScene) {
        private var frame = 0L
        fun nodes(): List<SemanticsNode> {
            repeat(6) { scene.render(1_000_000_000L + frame++ * 250_000_000L).close() }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        }
        fun text() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
    }
    private class Platform : AppPlatform {
        override val desktop = true
        override val version = "test"
        override fun readTheme() = AppTheme.Material
        override fun saveTheme(theme: AppTheme) {}
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean) = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Disconnected)
            override suspend fun execute(request: ShellRequest): ShellResult = error("No device command expected")
            override suspend fun close() {}
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {}
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {}
        override fun openLink(url: String) {}
        override fun license() = "MIT"
    }
}
