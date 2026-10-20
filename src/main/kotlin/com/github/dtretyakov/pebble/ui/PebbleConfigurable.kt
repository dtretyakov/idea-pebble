package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.EmulatorCheck
import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.ToolHosts
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.github.dtretyakov.pebble.sdk.Clangd
import com.github.dtretyakov.pebble.sdk.EmulatorStart
import com.github.dtretyakov.pebble.sdk.PebbleTool
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.EditorNotifications
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.panel
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JEditorPane

/**
 * Settings | Languages & Frameworks | Pebble: where pebble-tool and clangd are, the SDKs, and
 * whether each of them is in place.
 *
 * Each line of the environment checklist sits under the control that fixes it. It used to be a
 * checklist of its own at the bottom of the page, which named pebble-tool a second time below its
 * own field, and whose rows — a label, an icon, a sentence and a link side by side — were wider
 * than the dialog, so the link that fixed the problem was the part cut off. Tools | Pebble | Check
 * Environment opens this page.
 */
class PebbleConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Pebble", "settings.pebble", ID) {

    /**
     * The status lines, by the concerns each one reports on, kept to be rewritten: they are true
     * when the page opens, and Apply, an SDK installed from the list or pebble-tool installed from
     * the link each change what they should say.
     */
    private val statuses = mutableMapOf<List<Concern>, Pair<Row, Cell<JEditorPane>>>()

    /**
     * Where a found tool is said: the empty field's placeholder, where it costs no line. The page
     * used to say "Found at …" and "Compiler at …" under every control, which on a working machine
     * was a paragraph of paths saying everything was fine.
     */
    private var toolField: JBTextField? = null
    private var clangdField: JBTextField? = null

    /** Node.js is only mentioned when it is missing: nothing to configure about it otherwise. */
    private var nodeGroup: Row? = null

    /** Usable only with pebble-tool: every button on it runs a `pebble sdk` command. */
    private var sdkList: Row? = null

    override fun createPanel(): DialogPanel {
        val settings = PebbleAppSettings.getInstance()
        val items = PebbleEnvironment.check()

        val page = panel {
            // At the top, about the whole page: at the bottom, under the SDK's status line, it read
            // as a link about the SDK.
            row {
                comment("Guides and the API reference: <a href='$DOCUMENTATION_URL'>Pebble developer documentation</a>")
            }

            // Windows: the SDK runs in WSL, and which distribution is the first thing to know.
            if (HostOs.current() == HostOs.WINDOWS) {
                group("WSL") {
                    row("Distribution:") {
                        val automatic = ToolHosts.automaticDistribution()?.let { "Automatic ($it)" } ?: "Automatic"
                        val choices = listOf("") + ToolHosts.distributionNames()
                            .let { names -> if (settings.wslDistribution.isNotBlank() && settings.wslDistribution !in names) names + settings.wslDistribution else names }
                        comboBox(choices, textListCellRenderer { if (it.isNullOrEmpty()) automatic else it })
                            .bindItem({ settings.wslDistribution }, { settings.wslDistribution = it.orEmpty() })
                    }
                    row {
                        comment(
                            "pebble-tool, the SDK and the emulator run inside this Linux distribution; projects can be in it " +
                                "(<code>\\\\wsl.localhost\\…</code>, where builds are fastest) or on a Windows drive.",
                        )
                    }
                    status(items, Concern.HOST)
                }
            }

            group("pebble-tool") {
                row("Executable:") {
                    pathField("Choose the pebble Executable") { toolField = it }
                        .bindText(settings::pebbleToolPath)
                        .validationOnApply { field ->
                            val given = field.text.trim().takeIf { it.isNotEmpty() }
                            // Refused here rather than reported at the first build: a typo and no
                            // pebble-tool at all would otherwise read the same.
                            if (given != null && !isExecutable(given) { PebbleTool.locate(given, null) != null }) {
                                error("No pebble executable here")
                            } else {
                                null
                            }
                        }
                }
                status(items, Concern.TOOL)
            }

            group("SDKs") {
                // Shown without pebble-tool too, disabled: a list that appears from nowhere once
                // pebble-tool is installed does not say it was the next step.
                // The page's own disposable: the list listens for SDK changes while it is shown.
                val sdks = SdkListPanel(project, disposable!!)
                sdkList = row {
                    cell(sdks.component).align(Align.FILL)
                }.resizableRow().enabled(PebbleSdkService.getInstance().tool() != null)
                // Which SDK is active is chosen in the list and made so by Apply, as the page's other settings.
                onIsModified { sdks.isModified() }
                onApply { sdks.apply() }
                onReset { sdks.reset() }
                status(items, Concern.SDK, Concern.TOOLCHAIN, Concern.EMULATOR)
            }

            if (!IdeCapabilities.hasNativeC()) {
                group("C Code Insight") {
                    row("clangd:") {
                        pathField("Choose clangd") { clangdField = it }
                            .bindText(settings::clangdPath)
                            .validationOnApply { field ->
                                val given = field.text.trim().takeIf { it.isNotEmpty() }
                                if (given != null && !isExecutable(given) { Clangd.locate(given, null) != null }) error("No clangd here") else null
                            }
                    }
                    status(items, Concern.C_INSIGHT)
                }
            }

            nodeGroup = group("Node.js") {
                status(items, Concern.NODE)
            }

            // The paths the page no longer shows, for whoever needs them: a bug report, a question
            // on the forum. One line, at the bottom, out of the way.
            row {
                comment("<a href='report'>Copy environment report</a>", action = HyperlinkEventAction { copyReport() })
            }
        }
        refresh()

        // Set Up, an SDK from the list, and the emulator's answer all arrive while the page is open.
        // `any`, because this page is in a modal dialog and the default would hold the update back
        // until it closed. It sets labels' text and touches no settings.
        val later = { ApplicationManager.getApplication().invokeLater(::refresh, ModalityState.any()) }
        ApplicationManager.getApplication().messageBus.connect(disposable!!).apply {
            subscribe(PebbleSdkService.TOPIC, PebbleSdkService.Listener { later() })
            subscribe(PebbleEnvironment.TOPIC, PebbleEnvironment.Listener { later() })
        }
        // Asked again on every visit while it fails: the user may have just run the command.
        EmulatorCheck.getInstance().refresh(PebbleSdkService.getInstance().currentSdk())
        return page
    }

    override fun apply() {
        val before = PebbleAppSettings.getInstance().let { Triple(it.wslDistribution, it.pebbleToolPath, it.clangdPath) }
        super.apply()
        val after = PebbleAppSettings.getInstance().let { Triple(it.wslDistribution, it.pebbleToolPath, it.clangdPath) }
        // Under WSL the tools are where the distribution says, and it is asked with the paths set here.
        if (before != after) ToolHosts.reset()
        refresh()
        // The banner and the setup balloon read the same settings, and may have nothing to say.
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach {
            EditorNotifications.getInstance(it).updateAllNotifications()
            PebbleSetupNotice.reconsider(it)
        }
    }

    override fun disposeUIResources() {
        statuses.clear()
        sdkList = null
        toolField = null
        clangdField = null
        nodeGroup = null
        super.disposeUIResources()
    }

    private fun refresh() {
        val items = PebbleEnvironment.check()
        statuses.forEach { (concerns, line) ->
            val text = statusText(items, concerns)
            line.second.component.text = text
            line.first.visible(text.isNotEmpty())
        }
        sdkList?.enabled(PebbleSdkService.getInstance().tool() != null)
        nodeGroup?.visible(PebbleEnvironment.of(items, Concern.NODE)?.status == PebbleEnvironment.Status.MISSING)

        val host = PebbleSdkService.getInstance().host()
        val wsl = host as? WslToolHost
        val inside = wsl?.let { " in ${it.distribution}" }.orEmpty()
        toolField?.emptyText?.text = host?.locate(HostTool.PEBBLE)?.let { "Found at ${shown(host, it)}$inside" }
            ?: "Looked for on the PATH$inside and in ~/.local/bin"
        clangdField?.emptyText?.text = host?.locate(HostTool.CLANGD)?.let { "Found at ${shown(host, it)}$inside" }
            ?: "Looked for on the PATH$inside"
    }

    /** A found tool, as the user would type it there: a Linux path under WSL, `~/…` for the home directory. */
    private fun shown(host: ToolHost, path: Path): String {
        if (host is LocalToolHost) return FileUtil.getLocationRelativeToUserHome(path.toString())
        val spelled = host.toolPath(path) ?: return path.toString()
        val home = (host as? WslToolHost)?.probe?.home
        return if (home != null && spelled.startsWith("$home/")) "~" + spelled.removePrefix(home) else spelled
    }

    /**
     * Whether a path typed into a field is an executable where the tools run: asked of the WSL
     * distribution, since a Linux path means nothing to Windows; locally, by [here].
     */
    private fun isExecutable(given: String, here: () -> Boolean): Boolean {
        val wsl = PebbleSdkService.getInstance().host() as? WslToolHost ?: return here()
        return wsl.isExecutable(given)
    }

    /** Every check with what it found, paths included, as plain text. */
    private fun copyReport() {
        val report = buildString {
            appendLine(ApplicationInfo.getInstance().fullApplicationName)
            appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            (PebbleSdkService.getInstance().host() as? WslToolHost)?.let { wsl ->
                val release = wsl.osRelease()?.lines()?.firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
                appendLine("WSL ${wsl.distribution}: ${release ?: "unknown release"}, ${wsl.arch}, networking ${wsl.probe?.networking ?: "unknown"}")
            }
            PebbleEnvironment.check().forEach { appendLine("${it.status} ${it.name}: ${it.detail.replace("`", "")}") }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(report))
    }

    /**
     * A path field as wide as the page allows and no wider, with what an empty one means as its
     * placeholder: the sentence used to be a comment under the field, which starts at the field's
     * column and so made the row pay for the label's width twice.
     */
    private fun Row.pathField(title: String, keep: (JBTextField) -> Unit): Cell<TextFieldWithBrowseButton> =
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(title), project)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent { (textField as? JBTextField)?.let(keep) }

    /**
     * A status line in a row of its own, so it wraps at the page's width rather than adding to a
     * row's; a comment attached to a field would begin at the field's column instead.
     */
    private fun Panel.status(items: List<PebbleEnvironment.Item>, vararg concerns: Concern) {
        val key = concerns.toList()
        lateinit var line: Cell<JEditorPane>
        val row = row {
            line = comment(statusText(items, key), action = HyperlinkEventAction { event ->
                EnvironmentFixes.follow(project, event.description)
            })
        }
        statuses[key] = row to line
    }

    /** Only what is wrong, and what fixes it: what is fine is shown by the controls themselves. */
    private fun statusText(items: List<PebbleEnvironment.Item>, concerns: List<Concern>): String =
        concerns.mapNotNull { PebbleEnvironment.of(items, it) }
            .filter { it.status == PebbleEnvironment.Status.MISSING }
            .joinToString(" ") { item -> listOfNotNull(EnvironmentFixes.text(item), EnvironmentFixes.link(item)).joinToString(" ") }

    companion object {
        const val ID = "settings.pebble"
        private const val DOCUMENTATION_URL = "https://developer.repebble.com/"
    }
}
