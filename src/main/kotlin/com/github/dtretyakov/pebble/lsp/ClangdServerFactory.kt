package com.github.dtretyakov.pebble.lsp

import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.Clangd
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.lsp4ij.DocumentMatcher
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
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

    override fun isEnabled(project: Project): Boolean =
        !IdeCapabilities.hasNativeC() && PebbleProject.getInstance(project).isPebbleProject()

    /** Turned off in LSP4IJ's own view; there is nothing of ours to remember about it. */
    override fun setEnabled(enabled: Boolean, project: Project) = Unit

    private class Connection(private val project: Project) : OSProcessStreamConnectionProvider() {
        override fun start() {
            val clangd = Clangd.locate(PebbleAppSettings.getInstance().clangdPath, EnvironmentUtil.getValue("PATH"))
                ?: throw CannotStartProcessException(
                    "clangd is not installed, so C has no code insight in this IDE. Install it: ${Clangd.INSTALL_URL}",
                )
            val root = PebbleProject.getInstance(project).primaryRoot()
                ?: throw CannotStartProcessException("No Pebble project here.")
            val sdk = PebbleSdkService.getInstance().currentSdk()

            commandLine = GeneralCommandLine(
                listOfNotNull(
                    clangd.toString(),
                    "--compile-commands-dir=$root",
                    // Lets clangd ask the SDK's gcc for its system headers — newlib's — rather than
                    // looking for the host's, which are for another architecture altogether.
                    sdk?.let { "--query-driver=${it.compilerGlob}" },
                    "--background-index",
                    "--header-insertion=never",
                    "--log=error",
                ),
            ).withWorkingDirectory(root)
            super.start()
        }
    }

    /** Only the C inside a Pebble project; a stray `.c` elsewhere is none of this server's business. */
    class Matcher : DocumentMatcher {
        override fun match(file: VirtualFile, project: Project): Boolean =
            PebbleProject.getInstance(project).rootFor(file) != null
    }

    companion object {
        const val SERVER_ID = "pebble-clangd"
    }
}
