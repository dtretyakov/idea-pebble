package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.impl.ModuleBuildTaskImpl
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Build Project on a Pebble app is `pebble build`: the runner is the IDE's choice for its module. */
class PebbleProjectTaskRunnerTest : IdeTestCase() {

    fun `test a module holding a Pebble project is built by pebble build, and only such a module`() {
        val runner = ProjectTaskRunner.EP_NAME.extensionList.filterIsInstance<PebbleProjectTaskRunner>().single()
        assertFalse("not before there is a Pebble project", runner.canRun(project, ModuleBuildTaskImpl(module, false), null))

        val root = Files.createTempDirectory("task").toRealPath().resolve("app")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText("""{"name": "app", "pebble": {"projectType": "native", "targetPlatforms": ["emery"]}}""")
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        assertTrue(runner.canRun(project, ModuleBuildTaskImpl(module, false), null))
        assertEquals(root, PebbleProjectTaskRunner.rootOf(module))

        // An app with no sources fails to build — or, with no pebble-tool, does not start — either
        // way through pebble build, and the IDE's build says so.
        val finished = mutableListOf<BuildResult>()
        project.messageBus.connect(testRootDisposable).subscribe(PebbleBuild.TOPIC, PebbleBuild.Listener { _, built, result -> if (built == root) finished += result })
        val promise = ProjectTaskManager.getInstance(project).buildAllModules()
        val deadline = System.currentTimeMillis() + 120_000
        while (promise.state == org.jetbrains.concurrency.Promise.State.PENDING && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
        }
        val result = promise.blockingGet(1, TimeUnit.SECONDS)!!
        assertTrue(result.hasErrors())
        assertTrue("pebble build ran, or said why not", finished.isNotEmpty() || com.github.dtretyakov.pebble.project.PebbleSdkService.getInstance().tool() == null)
    }
}
