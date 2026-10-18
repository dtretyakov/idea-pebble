package com.github.dtretyakov.pebble.uitest

import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/** Run a scenario from its file: the context menu, and the gutter on its first line. */
class PebbleUiTestConfigurationProducer : LazyRunConfigurationProducer<PebbleUiTestConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(PebbleUiTestConfigurationType::class.java).configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: PebbleUiTestConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val (root, file) = scenario(context) ?: return false
        configuration.options.scenario = file.relativeTo(root).invariantSeparatorsPathString
        configuration.name = "UI test ${file.fileName.toString().removeSuffix(".$SCENARIO_EXTENSION")}"
        return true
    }

    override fun isConfigurationFromContext(configuration: PebbleUiTestConfiguration, context: ConfigurationContext): Boolean {
        val (_, file) = scenario(context) ?: return false
        return configuration.scenarioPath() == file
    }

    private fun scenario(context: ConfigurationContext): Pair<Path, Path>? {
        val file = context.location?.virtualFile?.takeIf { it.extension == SCENARIO_EXTENSION } ?: return null
        val path = file.toNioPathOrNull() ?: return null
        val root = PebbleProject.getInstance(context.project).rootFor(file) ?: return null
        return root to path.normalize()
    }
}

/** The run icon on a scenario's first line. Plain text has one element, so one marker per file. */
class ScenarioRunLineMarker : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element.containingFile?.virtualFile?.extension != SCENARIO_EXTENSION) return null
        if (element.textRange.startOffset != 0 || element.firstChild != null) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run the scenario" }
    }
}

/** What the scenario parser does not accept, where it is, before a run finds out. */
class ScenarioInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file.virtualFile?.extension != SCENARIO_EXTENSION) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val text = file.text
                val lines = text.lines()
                for (problem in ScenarioParser.parse(text).problems) {
                    val start = lines.take(problem.line - 1).sumOf { it.length + 1 }
                    val range = TextRange(start, start + lines[problem.line - 1].length).takeIf { !it.isEmpty } ?: TextRange(0, text.length.coerceAtMost(1))
                    if (range.isEmpty) continue
                    holder.registerProblem(file, problem.message, ProblemHighlightType.GENERIC_ERROR, range)
                }
            }
        }
    }
}

/**
 * Takes a failed `expect`'s screenshot as its new reference: the screen changed on purpose.
 * In the test tree's context menu.
 */
class AcceptScreenshotAction : DumbAwareAction("Accept Screenshot", "Make this run's screenshot the reference", AllIcons.Actions.Commit) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (actual, reference) = target(e) ?: return
        Files.createDirectories(reference.parent)
        Files.copy(actual, reference, StandardCopyOption.REPLACE_EXISTING)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(reference))
    }

    /** The screenshot and its reference, for a failed test of a scenario. */
    private fun target(e: AnActionEvent): Pair<Path, Path>? {
        val project = e.project ?: return null
        val test = e.getData(AbstractTestProxy.DATA_KEY) ?: return null
        val url = test.getLocationUrl() ?: return null
        if (!test.isDefect || !url.startsWith("${ScenarioLocator.PROTOCOL}://")) return null
        val platform = test.parent?.name ?: return null
        val scenario = Path.of(url.removePrefix("${ScenarioLocator.PROTOCOL}://").substringBeforeLast(':'))
        val file = LocalFileSystem.getInstance().findFileByNioFile(scenario) ?: return null
        val root = PebbleProject.getInstance(project).rootFor(file) ?: return null
        val paths = ScenarioPaths(scenario, root)
        val actual = paths.result(platform, test.name)
        return (actual to paths.reference(platform, test.name)).takeIf { Files.exists(actual) }
    }
}
