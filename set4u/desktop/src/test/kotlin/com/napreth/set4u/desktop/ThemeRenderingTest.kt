package com.napreth.set4u.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.napreth.set4u.core.bridge.*
import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.settings.SystemSettingsScreen
import com.napreth.set4u.ui.*
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class ThemeRenderingTest {
    @Test fun sharedThemePageAndDialogsRenderWithEveryLibrary() {
        SwingUtilities.invokeAndWait {
            for (theme in AppTheme.entries) for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
                val controller = AppController(Platform(theme), Dispatchers.Swing.immediate)
                controller.selectThemeMode(mode)
                controller.selectTab(2)
                val scene = ImageComposeScene(620, 800, Density(1f), coroutineContext = Dispatchers.Swing.immediate) { Set4uApp(controller) }
                try {
                    val image = scene.render(1_000_000_000L)
                    val texts = scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }
                    assertTrue("Missing app preferences in $theme: $texts", listOf("界面风格", "主题", "语言", theme.title).all { it in texts })
                    val directory = File(root(), "build/reports/themes-ui").apply { mkdirs() }
                    image.encodeToData()!!.use { File(directory, "${theme.id}-${mode.id}-desktop.png").writeBytes(it.bytes) }
                    image.close()
                    val row = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first { "界面风格" in text(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    row.config[SemanticsActions.OnClick].action!!.invoke()
                    scene.render(1_500_000_000L).close()
                    val choices = scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }
                    assertTrue("Missing dropdown choices: $choices", AppTheme.entries.all { it.title in choices })
                    val option = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.first {
                        theme.title in text(it) && "界面风格" !in text(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    option.config[SemanticsActions.OnClick].action!!.invoke()
                    controller.selectLanguage(AppLanguage.English)
                    scene.render(1_800_000_000L).close()
                    val english = scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }
                    assertTrue("Missing translated app preferences: $english", listOf("About", "Interface style", "Theme", "Language").all { it in english })
                    controller.showLicense()
                    // Register the overlay, compose its host, then advance its entrance animation.
                    repeat(6) { frame -> scene.render(2_000_000_000L + frame * 500_000_000L).close() }
                    val dialog = scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }
                    assertTrue("Dialog is not visible in $theme: $dialog", "MIT License" in dialog && "Close" in dialog)
                    controller.dismissDialog()
                    scene.render(5_000_000_000L).close()
                    controller.selectTab(1)
                    scene.render(6_000_000_000L).close()
                    val workTexts = scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }
                    assertFalse("Shell-only switch leaked into ADB mode", "Run as shell using root" in workTexts)
                    val workNodes = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    val modeRow = workNodes.first { it.config.getOrNull(SemanticsProperties.StateDescription) == "ADB" }
                    val status = workNodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Status" } }
                    val enable = workNodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Get access" } }
                    val actionLabel = workNodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Authorization" } }
                    assertTrue("Work mode must precede status", modeRow.boundsInRoot.bottom <= status.boundsInRoot.top)
                    assertTrue("Enable action must be below status", status.boundsInRoot.bottom <= enable.boundsInRoot.top)
                    assertTrue("Action label and button must share the third row", kotlin.math.abs(actionLabel.boundsInRoot.center.y - enable.boundsInRoot.center.y) < 4f)
                    assertTrue("Action button must be on the right", actionLabel.boundsInRoot.right < enable.boundsInRoot.left)
                    controller.selectMode(WorkMode.Root)
                    repeat(3) { frame -> scene.render(7_000_000_000L + frame * 500_000_000L).close() }
                    assertTrue(scene.semanticsOwners.flatMap { text(it.unmergedRootSemanticsNode) }.contains("Run as shell using root"))
                    scene.render(9_000_000_000L).use { preview -> preview.encodeToData()!!.use { File(directory, "${theme.id}-${mode.id}-work-mode-desktop.png").writeBytes(it.bytes) } }
                } finally { scene.close(); controller.close() }
            }
        }
    }

    private fun text(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun root(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private class Platform(private val theme: AppTheme) : AppPlatform {
        override val desktop = true
        override val uiFontFamily get() = DesktopFonts.ui
        override val version = "test"
        override val systemLocale = "zh-CN"
        override fun readTheme() = theme
        override fun saveTheme(theme: AppTheme) {}
        override fun createBridge(mode: WorkMode, serial: String, emulateShell: Boolean) = object : IAdbBridge {
            override val connectionState = MutableStateFlow(BridgeState.Disconnected)
            override suspend fun execute(request: ShellRequest): ShellResult = error("No device operation expected")
            override suspend fun close() {}
        }
        override suspend fun connect(bridge: IAdbBridge, requestPermission: Boolean) {}
        override suspend fun openSystem(session: CoreSession, screen: SystemSettingsScreen) {}
        override fun openLink(url: String) {}
        override fun license() = "MIT License\nCopyright (c) 2026 Napreth"
    }
}
