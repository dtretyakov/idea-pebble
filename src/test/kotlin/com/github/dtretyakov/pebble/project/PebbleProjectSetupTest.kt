package com.github.dtretyakov.pebble.project

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A project the New Project wizard made, which has no module: the layout a folder opened as a
 * project gets — and the module to hold it, kept in `.idea` like the one the IDE makes.
 */
class PebbleProjectSetupTest : HeavyPlatformTestCase() {

    /** No module, as the wizard leaves it. */
    override fun setUpModule() {}

    fun `test a project without a module gets one, with its sources marked and the build left out`() {
        val root = Path.of(project.basePath!!).createDirectories()
        root.resolve("package.json").writeText("""{"name": "app", "pebble": {"targetPlatforms": ["emery"]}}""")
        root.resolve("src/c").createDirectories().resolve("main.c").writeText("")
        root.resolve("build").createDirectories()
        // Seen by the IDE, as the wizard leaves the project it made.
        VfsUtilCore.visitChildrenRecursively(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!, object : VirtualFileVisitor<Unit>() {})
        assertEmpty(ModuleManager.getInstance(project).modules)

        PebbleProjectSetup.configureNow(project)

        val module = ModuleManager.getInstance(project).modules.single()
        val roots = ModuleRootManager.getInstance(module)
        assertEquals(listOf(root.resolve("src")), roots.sourceRoots.map { it.toNioPath() })
        assertTrue(roots.excludeRootUrls.toList().toString(), roots.excludeRootUrls.any { it.endsWith("/build") })
        assertTrue(PebbleSettings.getInstance(project).rootsConfigured)

        PlatformTestUtil.saveProject(project, true)
        val iml = root.resolve(".idea/${root.fileName}.iml")
        assertTrue("$iml was not written", Files.isRegularFile(iml))
        assertTrue(Files.readString(iml), Files.readString(iml).contains("\$MODULE_DIR\$/../src") || Files.readString(iml).contains("/src\""))
    }
}
