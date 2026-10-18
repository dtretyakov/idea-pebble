package com.github.dtretyakov.pebble.library

import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.PebbleSettings
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * The SDK under External Libraries: the active platform's `pebble.h` and its neighbours, and for
 * an Alloy project, the typings its JavaScript and TypeScript are checked against.
 *
 * Everything a Pebble project depends on lives outside it, in pebble-tool's data directory; this
 * is what makes it browsable, searchable and — for Alloy — what the IDE's own JavaScript and
 * TypeScript support resolves `import Poco from "commodetto/Poco"` against.
 */
class PebbleLibraryProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        libraries(project).also { lastRoots[project] = it.flatMap(SyntheticLibrary::getSourceRoots) }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        libraries(project).flatMap { it.sourceRoots }

    private fun libraries(project: Project): List<PebbleLibrary> {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot() ?: return emptyList()
        val sdk = PebbleSdkService.getInstance().currentSdk() ?: return emptyList()
        val platform = model.activePlatform(root)

        return listOfNotNull(
            platform?.let { library("Pebble SDK ${sdk.version} ($it)", sdk.includeDir(it)) },
            sdk.toolchain.resolve("moddable/typings")
                .takeIf { model.infoAt(root)?.projectType == PebbleProjectInfo.ProjectType.ALLOY }
                ?.let { library("Alloy typings", it) },
            // Where the IDE has JavaScript, the typings are its library instead (PebbleJsLibraries):
            // only that one is resolved against, and two of the same name would be one too many.
            pebbleKitJs?.takeIf { Files.isDirectory(root.resolve("src/pkjs")) && !IdeCapabilities.hasJavaScript() }
                ?.let { PebbleLibrary("PebbleKit JS", it) },
        )
    }

    /**
     * The plugin's own typings for PebbleKit JS, which the SDK does not ship: the `Pebble` object
     * the phone half of an app is written against. Read from the plugin's jar where it is.
     */
    private val pebbleKitJs: VirtualFile? by lazy {
        PebbleLibraryProvider::class.java.getResource(PEBBLEKIT_JS)?.let(VfsUtil::findFileByURL)?.parent
    }

    private fun library(name: String, directory: Path): PebbleLibrary? =
        LocalFileSystem.getInstance().findFileByNioFile(directory)?.let { PebbleLibrary(name, it) }

    /** The include directory is the platform's, so a switch changes the libraries. */
    class PlatformListener : PebbleSettings.Listener {
        override fun activePlatformChanged(project: Project) = refresh(project)
    }

    companion object {
        const val PEBBLEKIT_JS = "/typings/pebblekit-js/pebblekit-js.d.ts"

        /**
         * Tells the IDE the libraries changed, when they have — the platform or the SDK moved — so
         * External Libraries and every index over it follow.
         */
        fun refresh(project: Project) {
            val provider = EP_NAME.findExtension(PebbleLibraryProvider::class.java) ?: return
            val before = provider.lastRoots[project].orEmpty()
            val after = provider.libraries(project).flatMap { it.sourceRoots }
            if (before == after) return
            ApplicationManager.getApplication().invokeLater({
                WriteAction.run<RuntimeException> {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Pebble SDK", before, after, "Pebble SDK")
                }
            }, project.disposed)
        }
    }

    /** What the IDE was last given, so a switch can say what it replaced. */
    private val lastRoots: MutableMap<Project, List<VirtualFile>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
}

private class PebbleLibrary(private val name: String, private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {

    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun getPresentableText(): String = name

    override fun getIcon(unused: Boolean): Icon = PebbleIcons.PEBBLE

    override fun equals(other: Any?): Boolean = other is PebbleLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()
}
