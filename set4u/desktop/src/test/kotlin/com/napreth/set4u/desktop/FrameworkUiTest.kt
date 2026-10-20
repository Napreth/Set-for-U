package com.napreth.set4u.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.compatibility.CompatibilityRegistry
import com.napreth.set4u.core.execution.*
import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.*
import com.napreth.set4u.core.settings.presentation.*
import com.napreth.set4u.core.state.SettingSnapshot
import com.napreth.set4u.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
class FrameworkUiTest {
    @Test fun privateRenderersDispatchThroughTheVmErrorsRemainVisibleAndRowsAreLazy() {
        val enabled = AtomicBoolean(false)
        val definition = SettingDefinition("custom.enabled", ValueType.Boolean, {
            object : SettingHandler {
                override suspend fun inspect(context: SettingContext) = SettingSnapshot(SettingValue.BooleanValue(enabled.get()))
                override suspend fun apply(context: SettingContext, desiredValue: SettingValue) { enabled.set((desiredValue as SettingValue.BooleanValue).value) }
            }
        })
        fun entry(id: String, renderer: String) = object : SettingEntry(definition, UiText.Literal(id), id = id) {
            override fun rows(snapshot: EntrySnapshot, texts: TextResources, locale: String, style: EntryStyle) =
                listOf(EntryRow(SettingRow(id, id, RowControl.Toggle, rendererKey = renderer)))
        }
        val custom = entry("custom", "private")
        val fallback = entry("fallback", "unknown")
        val page = object : SettingsPage("home", "设置") {
            override val entries = listOf(custom, fallback)
            override fun layout(rows: PageRows) = listOf(RowContainer("content", rows.rows(custom) + rows.rows(fallback) +
                (0..999).map { SettingRow("extra.$it", "Extra $it", RowControl.Value) }))
            override suspend fun onEnter(context: SettingsPageContext) { context.readAll(); error("Source failed after the page was loaded") }
        }
        val catalog = SettingsCatalog(page)
        lateinit var controller: AppController
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            controller = AppController(Platform(catalog), Dispatchers.Swing.immediate)
            val renderer: RowRenderer = { _, actions, standard ->
                Column { Text("Private renderer"); TextButton({ actions.changeValue(SettingValue.BooleanValue(true)) }) { Text("Apply custom") }; standard() }
            }
            val renderers = RowRenderers(mapOf("home" to mapOf("private" to renderer)))
            scene = ImageComposeScene(620, 800, Density(1f), coroutineContext = Dispatchers.Swing.immediate) { Set4uApp(controller, renderers) }
        }
        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("UI state was not published", predicate())
        }
        fun render(): List<SemanticsNode> {
            var nodes = emptyList<SemanticsNode>()
            SwingUtilities.invokeAndWait {
                scene.render(System.nanoTime()).close()
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            }
            return nodes
        }
        fun texts(nodes: List<SemanticsNode>) = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        try {
            await { controller.state.value.settings.error != null }
            val nodes = render(); val strings = texts(nodes)
            assertTrue("Private renderer was not dispatched: $strings", "Private renderer" in strings)
            assertTrue("Unknown renderer must fall back", "fallback" in strings)
            assertTrue("Loaded page errors must be visible", "部分内容加载失败" in strings)
            assertFalse("Large rows must be composed lazily", "Extra 999" in strings)
            assertTrue(strings.count { it.startsWith("Extra ") } < 30)
            SwingUtilities.invokeAndWait {
                fun includesAction(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Apply custom" } || node.children.any(::includesAction)
                nodes.first { includesAction(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    .config[SemanticsActions.OnClick].action!!.invoke()
            }
            await { enabled.get() && controller.state.value.settings.find("custom")?.value == SettingValue.BooleanValue(true) }
            SwingUtilities.invokeAndWait { controller.changeValue(controller.state.value.settings.find("custom")!!, SettingValue.TextValue("invalid")) }
            await { controller.state.value.notice != null }
            assertTrue(texts(render()).any { "操作未提交" in it })
        } finally { SwingUtilities.invokeAndWait { scene.close(); controller.close() } }
    }

    private class Platform(override val catalog: SettingsCatalog) : AppPlatform {
        override val desktop = true
        override val version = "test"
        override val uiFontFamily get() = DesktopFonts.ui
        override val compatibility = CompatibilityRegistry.Empty
        override val systemLocale = "zh-CN"
        override fun readTheme() = AppTheme.Material
        override fun saveTheme(theme: AppTheme) {}
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean) = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Ready)
            override suspend fun execute(request: ShellRequest): ShellResult = error("No transport operations expected")
            override suspend fun close() {}
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {}
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {}
        override fun openLink(url: String) {}
        override fun license() = "MIT"
    }
}
