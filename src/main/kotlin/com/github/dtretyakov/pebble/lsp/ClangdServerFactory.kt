package com.github.dtretyakov.pebble.lsp

import com.github.dtretyakov.pebble.project.CompileDatabase
import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.ToolCommandLine
import com.github.dtretyakov.pebble.sdk.Clangd
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import java.net.URI
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * clangd for a Pebble project's C, in the IDEs that have no C engine of their own.
 *
 * Declines in CLion, whose engine reads the same `compile_commands.json` natively — two engines on
 * one file would mean two sets of completions and two sets of errors.
 */
class ClangdServerFactory : LanguageServerFactory, LanguageServerEnablementSupport {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider = Connection(project)

    /**
     * And only once there is a compile database. Without one clangd knows neither the SDK's
     * include directory nor the headers waf generates, and answers a project never built with
     * "'pebble.h' file not found" on every file — errors about the IDE, not the code. It is started
     * when the first database is written ([DatabaseWritten]), while the project loads.
     */
    override fun isEnabled(project: Project): Boolean = runsFor(project)

    /**
     * Starts clangd once the first database is there; a running clangd reloads one by itself.
     * Asks [runsFor] first: LSP4IJ's `start` does not, and in CLion it would start clangd beside
     * CLion's own engine.
     */
    class DatabaseWritten : CompileDatabase.Written {
        override fun written(project: Project) {
            if (!runsFor(project)) return
            val servers = LanguageServerManager.getInstance(project)
            if (servers.getServerStatus(SERVER_ID) in STOPPED) servers.start(SERVER_ID)
        }

        private companion object {
            val STOPPED = setOf(ServerStatus.none, ServerStatus.stopped)
        }
    }

    /** Turned off in LSP4IJ's own view; there is nothing of ours to remember about it. */
    override fun setEnabled(enabled: Boolean, project: Project) = Unit

    /**
     * Under WSL clangd runs in the distribution and knows files by its paths, `file:///home/…` or
     * `file:///mnt/c/…`; the IDE's are `\\wsl.localhost\…` and `C:\…`. LSP4IJ asks this for the
     * URI of each file it sends, and for the file behind each URI clangd sends back.
     */
    override fun createClientFeatures(): LSPClientFeatures {
        val features = LSPClientFeatures()
        val host = PebbleSdkService.getInstance().host()
        if (host != null && host !is LocalToolHost) features.setFileUriSupport(ToolHostUris(host))
        return features
    }

    private class Connection(private val project: Project) : OSProcessStreamConnectionProvider() {
        override fun start() {
            val host = PebbleSdkService.getInstance().host()
                ?: throw CannotStartProcessException("The Pebble SDK runs on Windows inside WSL, which has no distribution here yet.")
            val clangd = host.locate(HostTool.CLANGD, PebbleAppSettings.getInstance().clangdPath)
                ?: throw CannotStartProcessException(
                    "clangd is not installed, so C has no code insight in this IDE. Install it: ${Clangd.INSTALL_URL}",
                )
            val root = PebbleProject.getInstance(project).primaryRoot()
                ?: throw CannotStartProcessException("No Pebble project here.")
            val sdk = PebbleSdkService.getInstance().currentSdk()
            val spelledRoot = host.toolPath(root) ?: throw CannotStartProcessException("clangd cannot reach $root from ${host.presentableName}.")

            commandLine = ToolCommandLine.build(
                clangd,
                listOfNotNull(
                    "--compile-commands-dir=$spelledRoot",
                    // Lets clangd ask the SDK's gcc for its system headers — newlib's — rather than
                    // looking for the host's, which are for another architecture altogether.
                    sdk?.compilerGlob(host::toolPath)?.let { "--query-driver=$it" },
                    "--background-index",
                    "--header-insertion=never",
                    "--log=error",
                ),
                root,
                host = host,
            )
            super.start()
        }
    }

    /** File URIs as the tools' host spells paths. */
    private class ToolHostUris(private val host: ToolHost) : FileUriSupport {
        override fun getFileUri(file: VirtualFile): URI? {
            val path = file.toNioPathOrNull() ?: return null
            val spelled = host.toolPath(path) ?: return null
            return runCatching { URI("file", "", spelled, null) }.getOrNull()
        }

        override fun findFileByUri(fileUri: String): VirtualFile? {
            val uri = runCatching { URI(fileUri) }.getOrNull()?.takeIf { it.scheme == "file" } ?: return null
            val path = uri.path?.takeIf { it.startsWith("/") } ?: return null
            return host.idePath(path)?.let(LocalFileSystem.getInstance()::findFileByNioFile)
        }

        override fun toString(file: VirtualFile): String? = getFileUri(file)?.toASCIIString()

        override fun toString(fileUri: URI, encoded: Boolean): String? = FileUriSupport.DEFAULT.toString(fileUri, encoded)
    }

    /** Only the C inside a Pebble project; a stray `.c` elsewhere is none of this server's business. */
    class Matcher : DocumentMatcher {
        override fun match(file: VirtualFile, project: Project): Boolean =
            PebbleProject.getInstance(project).rootFor(file) != null
    }

    companion object {
        const val SERVER_ID = "pebble-clangd"

        fun runsFor(project: Project): Boolean =
            !IdeCapabilities.hasNativeC() &&
                PebbleProject.getInstance(project).isPebbleProject() &&
                CompileDatabase.getInstance(project).exists()
    }
}
