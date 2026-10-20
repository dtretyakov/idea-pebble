package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The inspection inside a real IDE, over a project and an SDK on disk shaped like the real ones. */
class PlatformStubCallInspectionTest : IdeTestCase() {

    fun `test an unguarded call to a stub is reported, and a guarded one is not`() {
        val main = project(
            targets = """["aplite", "emery"]""",
            source = """
                #include <pebble.h>

                static void select(ClickRecognizerRef recognizer, void *context) {
                  light_set_color(GColorRed);
                #if defined(PBL_PLATFORM_EMERY)
                  light_set_color(GColorBlue);
                #endif
                  light_enable_interaction();
                }
            """,
        )
        assertEquals(
            listOf(PlatformStubCallInspection.message("light_set_color", listOf("aplite"), everywhere = false)),
            warnings(main),
        )
    }

    fun `test a project that targets only platforms with the function hears nothing`() {
        assertEmpty(warnings(project(targets = """["emery"]""", source = "void f(void) { light_set_color(GColorRed); }")))
    }

    /**
     * Through the IDE's inspection engine, which visits the file as highlighting would. Not
     * highlighting itself: opening the file starts clangd, whose diagnostics are not under test.
     */
    private fun warnings(file: Path): List<String> {
        val psiFile = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!)!!
        val context = InspectionManager.getInstance(project).createNewGlobalContext()
        return InspectionEngine.runInspectionOnFile(psiFile, LocalInspectionToolWrapper(PlatformStubCallInspection()), context)
            .map { it.descriptionTemplate }
    }

    /** A Pebble project, and an SDK in which aplite stubs out the RGB backlight and emery has it. */
    private fun project(targets: String, source: String): Path {
        val temp = Files.createTempDirectory("pebble-stubs")
        VfsRootAccess.allowRootAccess(testRootDisposable, temp.toString())

        val sdk = temp.resolve("data/SDKs/4.33.1")
        sdk.resolve("sdk-core").createDirectories().resolve("manifest.json").writeText("""{"version": "4.33.1"}""")
        header(sdk, "aplite", "#define light_set_color(...) (0)\nvoid light_enable_interaction(void);")
        header(sdk, "emery", "void light_set_color(GColor color);\nvoid light_enable_interaction(void);")
        Files.createSymbolicLink(sdk.resolveSibling("current"), sdk.fileName)
        PebbleSdkService.getInstance().useDataDir(temp.resolve("data"), testRootDisposable)

        val root = temp.resolve("app")
        root.createDirectories().resolve("package.json").writeText("""{"name": "app", "pebble": {"targetPlatforms": $targets}}""")
        val main = root.resolve("src/c").createDirectories().resolve("main.c").apply { writeText(source.trimIndent()) }
        // Seen by the IDE, as an opened project is: the UI thread is answered from what it has seen.
        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!)
        return main
    }

    private fun header(sdk: Path, platform: String, text: String) {
        sdk.resolve("sdk-core/pebble/$platform/include").createDirectories().resolve("pebble.h").writeText(text)
    }
}
