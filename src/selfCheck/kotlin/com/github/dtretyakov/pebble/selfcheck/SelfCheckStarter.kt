package com.github.dtretyakov.pebble.selfcheck

import com.github.dtretyakov.pebble.emulator.EmulatorToolWindowFactory
import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.intellij.openapi.wm.ToolWindowEP
import com.github.dtretyakov.pebble.lsp.ClangdServerFactory
import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.vfs.VfsUtil
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.run.PebbleBuildBeforeRunTaskProvider
import com.github.dtretyakov.pebble.run.PebbleRunConfigurationType
import com.github.dtretyakov.pebble.ui.MissingSdkNotification
import com.github.dtretyakov.pebble.ui.wizard.PebbleDirectoryProjectGenerator
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModernApplicationStarter
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorNotificationProvider
import kotlin.system.exitProcess

/**
 * A headless check that the plugin is whole inside a real IDE.
 *
 *     ./gradlew runSelfCheck
 *
 * Unit tests cannot see any of this: whether `plugin.xml` names classes that exist, whether an
 * action landed in the menu it was added to, whether the settings page is registered where the
 * action that opens it looks for it. Those failures are silent at run time — an `add-to-group`
 * naming a group this IDE has not got is one line in `idea.log` and a menu item that is not there.
 *
 * A plugin of its own, loaded beside Pebble only in the `runSelfCheck` sandbox and never shipped:
 * an application starter is internal API, which is fine for a tool that runs on a developer's
 * machine and grounds for the Marketplace to refuse the plugin that users install.
 */
class SelfCheckStarter : ModernApplicationStarter() {

    override val isHeadless: Boolean get() = true

    override suspend fun start(args: List<String>) {
        val problems = mutableListOf<String>()

        problems += menuProblems()

        val configurable = Configurable.PROJECT_CONFIGURABLE
            .getExtensions(ProjectManager.getInstance().defaultProject)
            .firstOrNull { it.id == "settings.pebble" }
        if (configurable == null) {
            problems += "the settings page is not registered, so Check Environment opens nothing"
        } else {
            println("[self-check] settings page: ${configurable.displayName} under ${configurable.parentId}")
        }

        val runType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { it is PebbleRunConfigurationType }
        if (runType == null) {
            problems += "the Pebble run configuration type is not registered"
        } else {
            println("[self-check] run configuration: ${runType.displayName}")
        }
        if (BeforeRunTaskProvider.EP_NAME.getExtensions(ProjectManager.getInstance().defaultProject)
                .none { it is PebbleBuildBeforeRunTaskProvider }
        ) {
            problems += "the Build Pebble app before-launch task is not registered"
        }

        if (ToolWindowEP.EP_NAME.extensionList.none { it.id == EmulatorToolWindowFactory.ID }) {
            problems += "the Pebble Emulator tool window is not registered"
        } else {
            println("[self-check] tool window: ${EmulatorToolWindowFactory.ID}")
        }

        if (GeneratorNewProjectWizard.EP_NAME.extensionList.none { it.id == "Pebble" }) {
            problems += "the New Project wizard is not registered"
        }
        val generators = ExtensionPointName<DirectoryProjectGenerator<*>>("com.intellij.directoryProjectGenerator")
        if (generators.extensionList.none { it is PebbleDirectoryProjectGenerator }) {
            problems += "the directory project generator for CLion and WebStorm is not registered"
        }

        if (LocalInspectionEP.LOCAL_INSPECTION.extensionList.none { it.shortName == "PebblePackageJson" }) {
            problems += "the package.json inspection is not registered"
        } else {
            println("[self-check] package.json inspection")
        }

        // From the packaged jar, which is where a user's IDE reads it: a resource that is in the
        // sources but not in the jar is a library that silently never appears.
        val typings = PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)
            ?.let(VfsUtil::findFileByURL)
        if (typings?.parent == null) {
            problems += "the PebbleKit JS typings are not readable from the plugin"
        } else {
            println("[self-check] PebbleKit JS typings: ${typings.url}")
        }

        if (AdditionalLibraryRootsProvider.EP_NAME.extensionList.none { it is PebbleLibraryProvider }) {
            problems += "the SDK library provider is not registered"
        }

        // LSP4IJ is optional, and in the sandbox it is installed: so its server has to be there.
        if (IdeCapabilities.hasLsp4ij()) {
            val clangd = LanguageServersRegistry.getInstance().getServerDefinition(ClangdServerFactory.SERVER_ID)
            if (clangd == null) problems += "LSP4IJ is installed but the clangd server is not registered with it"
            else println("[self-check] language server: ${clangd.displayName}")
        } else {
            println("[self-check] LSP4IJ is not installed; no clangd")
        }

        if (EditorNotificationProvider.EP_NAME.getExtensions(ProjectManager.getInstance().defaultProject)
                .none { it is MissingSdkNotification }
        ) {
            problems += "the missing SDK banner is not registered"
        } else {
            println("[self-check] missing SDK banner")
        }

        // The machine's state is reported rather than failed: the plugin has to install and behave
        // on a machine with no Pebble SDK at all, which is what a CI runner is.
        val service = PebbleSdkService.getInstance()
        println("[self-check] pebble-tool: ${service.tool() ?: "not found"}")
        println("[self-check] data directory: ${service.dataDir() ?: "none on this system"}")
        val sdks = service.sdks()
        println("[self-check] SDKs: ${sdks.installed.joinToString { it.version }.ifEmpty { "none" }}; active: ${sdks.current?.version ?: "none"}")
        PebbleEnvironment.check().forEach { println("[self-check]   ${it.status} ${it.name}: ${it.detail}") }

        reportUnloadability()

        problems.forEach { println("[self-check] PROBLEM: $it") }
        println(if (problems.isEmpty()) "[self-check] OK" else "[self-check] FAILED")
        exitProcess(if (problems.isEmpty()) 0 else 1)
    }

    private fun menuProblems(): List<String> {
        val actions = ActionManager.getInstance()
        val problems = mutableListOf<String>()

        MENU_MEMBERSHIP.forEach { (group, members) ->
            val parent = actions.getAction(group) as? DefaultActionGroup
            if (parent == null) {
                problems += "there is no action group '$group', so ${members.joinToString()} are in no menu"
                return@forEach
            }
            val present = parent.getChildActionsOrStubs().mapNotNull { actions.getId(it) }.toSet()
            members.filterNot { it in present }.forEach {
                problems += "'$it' is not in '$group', so nothing in the menus reaches it"
            }
            println("[self-check] menu: $group carries ${members.count { it in present }}/${members.size}")
        }
        return problems
    }

    /**
     * Whether the platform would let this plugin be unloaded without restarting the IDE — reported,
     * not failed, because the sandbox cannot always manage it even when the answer is yes.
     *
     * Reflectively, because the method's parameter type differs between the IDEs `sinceBuild`
     * covers.
     */
    private fun reportUnloadability() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        if (descriptor == null) {
            println("[self-check] dynamic unload: the plugin descriptor could not be found")
            return
        }
        val canUnload = runCatching {
            DynamicPlugins::class.java
                .methods
                .first { it.name == "checkCanUnloadWithoutRestart" && it.parameterCount == 1 }
                .invoke(DynamicPlugins, descriptor) as Boolean
        }.getOrNull()
        println(
            when (canUnload) {
                true -> "[self-check] dynamic unload: yes"
                false -> "[self-check] dynamic unload: no, an extension point is not dynamic"
                null -> "[self-check] dynamic unload: this IDE does not answer the question"
            },
        )
    }

    private companion object {
        const val PLUGIN_ID = "com.github.dtretyakov.pebble"

        /** Which of the plugin's actions each menu group has to carry. */
        val MENU_MEMBERSHIP = mapOf(
            "ToolsMenu" to listOf("Pebble.Tools"),
            "RunToolbarMainActionGroup" to listOf("Pebble.SelectPlatform"),
            "BuildMenu" to listOf("Pebble.Build"),
            "Pebble.Tools" to listOf("Pebble.CheckEnvironment", "Pebble.TakeScreenshot"),
        )
    }
}
