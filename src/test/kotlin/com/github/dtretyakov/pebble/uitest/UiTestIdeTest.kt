package com.github.dtretyakov.pebble.uitest

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Scenarios inside the IDE: the file, the run configuration, and a run in the test runner. */
class UiTestIdeTest : IdeTestCase() {

    fun `test a scenario is text, and its mistakes are errors`() {
        assertEquals(PlainTextFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByExtension(SCENARIO_EXTENSION))
        myFixture.enableInspections(ScenarioInspection())
        myFixture.configureByText(
            "menu.pbtest",
            "install\n<error descr=\"'press' takes a button: back, up, select or down\">press middle</error>\nexpect home\n",
        )
        myFixture.checkHighlighting()
    }

    fun `test a run reports a suite per platform and a test per expectation`() {
        val root = project()
        val scenario = root.resolve("tests/ui/menu.pbtest")
        scenario.parent.createDirectories()
        scenario.writeText("platforms emery\ninstall\nexpect home\npress select\nexpect menu\n")

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(scenario)!!
        val psi = PsiManager.getInstance(project).findFile(virtualFile)!!
        val context = ConfigurationContext.getFromContext(
            MapDataContext().apply {
                put(CommonDataKeys.PROJECT, project)
                put(LangDataKeys.MODULE, module)
                put(Location.DATA_KEY, PsiLocation(psi))
            },
            "test",
        )
        val configuration = RunConfigurationProducer.getInstance(PebbleUiTestConfigurationProducer::class.java)
            .createConfigurationFromContext(context)!!.configuration as PebbleUiTestConfiguration
        assertEquals("UI test menu", configuration.name)
        assertEquals("tests/ui/menu.pbtest", configuration.options.scenario)
        assertEquals(scenario, configuration.scenarioPath())

        val emulator = object : ScenarioRunner.Emulator {
            override fun pebble(platform: String, arguments: List<String>) = 0 to ""
            override fun tap(platform: String, x: Int, y: Int) = Unit
            override fun screenshot(platform: String, file: Path): Pair<Int, String> {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
                return 0 to ""
            }
        }
        val handler = ScenarioProcessHandler(listOf(scenario), root, listOf("basalt"), emulator)
        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
            PebbleUiTestState.FRAMEWORK,
            handler,
            object : SMTRunnerConsoleProperties(configuration, PebbleUiTestState.FRAMEWORK, executor) {
                override fun getTestLocator(): SMTestLocator = ScenarioLocator
            },
        ) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        handler.startNotify()
        val deadline = System.currentTimeMillis() + 10_000
        while (!handler.isProcessTerminated && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        UIUtil.dispatchAllInvocationEvents()

        val tests = console.resultsViewer.testsRootNode.allTests
        assertEquals(listOf("menu.pbtest", "emery", "home", "menu"), tests.drop(1).map { it.name })
        assertTrue(tests.filter { it.isLeaf }.all { it.isPassed })
        assertTrue("recorded", Files.exists(root.resolve("tests/ui/screenshots/emery/home.png")))
        assertEquals("${ScenarioLocator.PROTOCOL}://$scenario:5", tests.last().locationUrl)
    }

    private fun project(): Path {
        val root = Files.createTempDirectory("ui-tests").toRealPath().resolve("app")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText("""{"name": "app", "pebble": {"targetPlatforms": ["basalt"]}}""")
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        return root
    }
}
