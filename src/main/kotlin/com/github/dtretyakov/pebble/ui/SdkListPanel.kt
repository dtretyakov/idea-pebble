package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.github.dtretyakov.pebble.sdk.PebbleSdkList
import com.github.dtretyakov.pebble.sdk.PebbleSdkReleases
import com.github.dtretyakov.pebble.sdk.SdkVersion
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import kotlin.math.roundToLong

/**
 * The installed SDKs, a row each: a check mark on the active one and nothing on the others, the
 * version, the room it takes, and the platforms it builds for — in red, one the project targets
 * that it does not. Whether a newer SDK is out is said once, under the table.
 *
 * Install and Remove are pebble-tool commands run at once, in the background, as the Plugins page
 * installs. Which SDK is active is a setting like the page's others: chosen here — double-click,
 * Set Active, or the row's menu — and made so by Apply; Cancel leaves it as it was.
 */
internal class SdkListPanel(private val project: Project?, parent: Disposable) {

    private val model = ListTableModel<PebbleSdk>()

    /** The SDK chosen to be active, until Apply makes it so; null when the choice is the active one. */
    private var chosen: String? = null

    /** The versions pebble-tool offers to install; null until asked, or when it could not say. */
    @Volatile
    private var available: List<String>? = null

    /** The platforms each installed SDK builds for, by version: read with the list, not as it is drawn. */
    private var platforms: Map<String, List<String>> = emptyMap()

    /** The target platforms each installed SDK lacks, by version: read with the list. */
    private var missing: Map<String, List<String>> = emptyMap()

    @Volatile
    private var disposed = false

    private val table = TableView(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        emptyText.text = "No SDKs installed"
    }

    /** A check mark on the active SDK — or the one chosen to be, until Apply — and nothing on the others. */
    private inner class ActiveColumn : ColumnInfo<PebbleSdk, Boolean>("") {
        override fun valueOf(item: PebbleSdk): Boolean = item.version == shownActive()
        override fun getWidth(table: JTable): Int = JBUI.scale(28)
        override fun getRenderer(item: PebbleSdk): TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int) {
                if (value == true) icon = AllIcons.Actions.Checked
                toolTipText = if (value == true) "Active" else null
            }
        }
    }

    private inner class VersionColumn : ColumnInfo<PebbleSdk, String>("Version") {
        override fun valueOf(item: PebbleSdk): String = item.version
        override fun getWidth(table: JTable): Int = fit(table, "Version", "4.33.10")
    }

    /** The room the SDK takes, once measured. */
    private inner class SizeColumn : ColumnInfo<PebbleSdk, String>("Size") {
        override fun valueOf(item: PebbleSdk): String = sizes[item.root]?.let(::size).orEmpty()
        override fun getWidth(table: JTable): Int = fit(table, "Size", "1000 MB")
        override fun getRenderer(item: PebbleSdk): TableCellRenderer =
            DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
    }

    /** The platforms the SDK builds for, and in red those the project targets that it does not. */
    private inner class PlatformsColumn : ColumnInfo<PebbleSdk, PebbleSdk>("Platforms") {
        override fun valueOf(item: PebbleSdk): PebbleSdk = item
        override fun getRenderer(item: PebbleSdk): TableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int) {
                val sdk = value as? PebbleSdk ?: return
                val platforms = platforms[sdk.version].orEmpty()
                icon = PebbleIcons.PEBBLE
                platformsLine(platforms, missing[sdk.version].orEmpty()).forEachIndexed { index, part ->
                    append(if (index == 0) part.text else "   " + part.text, if (part.problem) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                toolTipText = platforms.joinToString("<br>", "<html>", "</html>") { id -> PebblePlatform.of(id)?.let { "$id — ${it.watches}" } ?: id }
            }
        }
    }

    /** Under the list: up to date, or the newer SDK with the way to it. Hidden until pebble-tool has said. */
    private val status = JPanel(HorizontalLayout(JBUI.scale(8))).apply {
        isVisible = false
        border = JBUI.Borders.emptyTop(6)
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            ToolbarDecorator.createDecorator(table)
                .setAddAction { button -> chooseVersionToInstall(button.preferredPopupPoint) }
                .setAddActionName("Install SDK…")
                .setRemoveAction { remove() }
                .setRemoveActionName("Remove SDK")
                .addExtraAction(SetActive())
                .disableUpDownActions()
                .setPreferredSize(java.awt.Dimension(0, 120))
                .createPanel(),
            BorderLayout.CENTER,
        )
        add(status, BorderLayout.SOUTH)
    }

    init {
        Disposer.register(parent) { disposed = true }
        PopupHandler.installPopupMenu(table, DefaultActionGroup(SetActive(), ReleaseNotes()), "PebbleSdkList")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                table.selectedObject?.let { choose(it.version) } ?: return false
                return true
            }
        }.installOn(table)

        model.columnInfos = arrayOf(ActiveColumn(), VersionColumn(), SizeColumn(), PlatformsColumn())
        // Again, now that there are columns: the table sizes them only as its model is set.
        table.setModelAndUpdateColumns(model)
        reload()
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
            PebbleSdkService.TOPIC,
            PebbleSdkService.Listener { onEdt(::reload) },
        )
        // What only pebble-tool and the disk can say, asked once the page is up rather than before.
        val installed = model.items.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            available = PebbleCommands.capture(listOf("sdk", "list"))?.let(PebbleSdkList::available)
            onEdt(::refreshStatus)
            installed.forEach { sdk ->
                if (disposed) return@executeOnPooledThread
                if (sizes.containsKey(sdk.root)) return@forEach
                sizeOf(sdk)?.let { sizes[sdk.root] = it }
                onEdt(table::repaint)
            }
        }
    }

    /** Whether an SDK other than the active one is chosen, for Apply. */
    fun isModified(): Boolean = chosen != null

    /** Makes the chosen SDK the active one: `pebble sdk activate`, in the background. */
    fun apply() {
        chosen?.let { PebbleCommands.activateSdk(project, it) }
        chosen = null
    }

    /** Back to the active SDK, as Cancel and Reset leave it. */
    fun reset() {
        chosen = null
        table.repaint()
    }

    internal fun choose(version: String) {
        chosen = version.takeIf { it != activeVersion() }
        table.repaint()
    }

    private fun reload() {
        val selected = table.selectedObject?.version
        val installed = PebbleSdkService.getInstance().sdks().installed
        val targets = targetPlatforms()
        platforms = installed.associate { sdk -> sdk.version to sdk.platforms() }
        missing = installed.associate { sdk -> sdk.version to targets.filterNot { it in platforms[sdk.version].orEmpty() } }
        if (installed.none { it.version == chosen }) chosen = null
        model.items = installed
        installed.firstOrNull { it.version == selected }?.let { table.selection = listOf(it) }
        refreshStatus()
    }

    /** The line under the list, from what is installed and what pebble-tool offers. */
    private fun refreshStatus() {
        status.removeAll()
        val latest = available?.let { PebbleSdkReleases.latest(model.items.map(PebbleSdk::version), it) }
        val newer = newer(latest)
        when {
            latest == null -> Unit
            newer != null -> {
                status.add(JBLabel("Pebble SDK $newer is available.", AllIcons.General.Information, JBLabel.LEFT))
                status.add(ActionLink("Install") { PebbleCommands.installSdk(project, newer) })
                status.add(ActionLink("What's new") { openNotes(newer) })
            }
            else -> status.add(JBLabel("Up to date: $latest is the latest.").apply { foreground = UIUtil.getContextHelpForeground() })
        }
        status.isVisible = latest != null
        status.revalidate()
        status.repaint()
        table.repaint()
    }

    /** [latest], when it is newer than every installed SDK. */
    private fun newer(latest: String?): String? = latest?.takeIf { version -> model.items.none { it.version == version } }

    private fun activeVersion(): String? = PebbleSdkService.getInstance().currentSdk()?.version

    /** The SDK the list shows as active: the chosen one, until Apply or Cancel. */
    private fun shownActive(): String? = chosen ?: activeVersion()

    /** The platforms the project names in `targetPlatforms`; none when it names none, which means all of them. */
    private fun targetPlatforms(): List<String> {
        val project = project ?: return emptyList()
        val model = PebbleProject.getInstance(project)
        return model.primaryRoot()?.let { model.infoAt(it)?.targetPlatforms }.orEmpty()
    }

    /** The versions to install, newest first, in a list at [where]: just under the add button. */
    private fun chooseVersionToInstall(where: RelativePoint) {
        val offered = available ?: ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<String>, RuntimeException> {
                PebbleSdkList.available(PebbleCommands.capture(listOf("sdk", "list")).orEmpty())
            },
            "Fetching Pebble SDK Versions",
            true,
            project,
        ).also { if (it.isNotEmpty()) available = it }
        val installed = model.items.map { it.version }
        val choices = offered.filterNot { it in installed }.sortedByDescending(SdkVersion::of)
        if (choices.isEmpty()) {
            Messages.showInfoMessage(where.component, "No other SDK versions are available, or the download server could not be reached.", "Install Pebble SDK")
            return
        }
        val latest = PebbleSdkReleases.latest(installed, offered)
        val newer = newer(latest)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Install Pebble SDK")
            .setRenderer(
                object : ColoredListCellRenderer<String>() {
                    override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, focused: Boolean) {
                        value ?: return
                        append(value)
                        if (value == newer) append("   latest", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                },
            )
            // Said once, where it is true of every version offered, rather than on each of them.
            .apply { if (newer == null && latest != null) setAdText("$latest, installed, is the latest.") }
            .setItemChosenCallback { PebbleCommands.installSdk(project, it) }
            .createPopup()
            .show(where)
    }

    private fun remove() {
        val sdk = table.selectedObject ?: return
        val frees = sizes[sdk.root]?.let { " It frees about ${size(it)}." }.orEmpty()
        val answer = Messages.showYesNoDialog(
            table,
            "Remove Pebble SDK ${sdk.version}, with its toolchain and its emulators' saved state?$frees",
            "Remove Pebble SDK",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) PebbleCommands.uninstallSdk(project, sdk.version)
    }

    /** The release notes of [version], in the browser: their address is the download server's to give. */
    private fun openNotes(version: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val url = PebbleSdkReleases.fetchNotesUrl(version)
            onEdt {
                if (url != null) BrowserUtil.browse(url)
                else Messages.showInfoMessage(table, "The download server has no release notes for Pebble SDK $version, or could not be reached.", "Release Notes")
            }
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!disposed) action() }, ModalityState.any())
    }

    private inner class SetActive : DumbAwareAction("Set Active", "Makes the selected SDK the one pebble-tool uses, on Apply", AllIcons.Actions.SetDefault) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val selected = table.selectedObject
            e.presentation.isEnabled = selected != null && selected.version != shownActive()
        }

        override fun actionPerformed(e: AnActionEvent) {
            table.selectedObject?.let { choose(it.version) }
        }
    }

    private inner class ReleaseNotes : DumbAwareAction("Release Notes", "Opens what changed in the selected SDK", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedObject != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            table.selectedObject?.let { openNotes(it.version) }
        }
    }

    /** One thing said of an SDK beside its version; a [problem] is said in red. */
    data class Detail(val text: String, val problem: Boolean = false)

    companion object {
        /** The Platforms column: what the SDK builds for, then in red what the project targets and it does not. */
        fun platformsLine(platforms: List<String>, missing: List<String>): List<Detail> = buildList {
            if (platforms.isNotEmpty()) add(Detail(platforms.joinToString()))
            if (missing.isNotEmpty()) add(Detail("no ${missing.joinToString()}", problem = true))
        }

        /** A column as wide as the wider of its [header] and a [sample] value, and no wider: the rest is the platforms'. */
        private fun fit(table: JTable, header: String, sample: String): Int {
            val metrics = table.getFontMetrics(table.font)
            return maxOf(metrics.stringWidth(header), metrics.stringWidth(sample)) + JBUI.scale(24)
        }

        /** A size as one would say it: whole megabytes, or gigabytes to one place. */
        fun size(bytes: Long): String {
            val megabytes = bytes / (1024.0 * 1024.0)
            return if (megabytes < 1024) "${megabytes.roundToLong()} MB" else "${(megabytes / 1024 * 10).roundToLong() / 10.0} GB"
        }

        /**
         * Each installed SDK's size on disk, by its directory, as it is measured: kept while the IDE
         * runs, since an installed SDK does not change and measuring one walks its whole tree.
         */
        private val sizes = ConcurrentHashMap<Path, Long>()

        /** The room an SDK takes: its toolchain, its emulator and its Node packages. */
        private fun sizeOf(sdk: PebbleSdk): Long? = runCatching {
            Files.walk(sdk.root).use { files -> files.filter(Files::isRegularFile).mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
        }.getOrNull()?.takeIf { it > 0 }
    }
}
