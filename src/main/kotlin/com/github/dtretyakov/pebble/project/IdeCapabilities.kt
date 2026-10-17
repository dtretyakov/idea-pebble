package com.github.dtretyakov.pebble.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType

/**
 * What the IDE the plugin finds itself in can already do, asked at run time rather than compiled
 * against — which is what lets one archive serve IDEA, CLion and WebStorm.
 *
 * Asked through public API only. The plugin manager's lookups would answer more directly, and are
 * internal API the Marketplace refuses a plugin for.
 */
object IdeCapabilities {

    /**
     * Whether `.c` belongs to a real C language here — CLion's, classic or Nova — rather than to a
     * TextMate grammar or to nothing. Where it does, clangd would only compete with it.
     */
    fun hasNativeC(): Boolean {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("c") as? LanguageFileType ?: return false
        return type.language.id !in NOT_A_C_ENGINE
    }

    /** LSP4IJ, which runs clangd. An optional dependency, so it may well not be there. */
    fun hasLsp4ij(): Boolean = ApplicationManager.getApplication().extensionArea.hasExtensionPoint(LSP4IJ_SERVERS)

    /** The IDE's JavaScript, which takes PebbleKit JS's typings as a library of its own. */
    fun hasJavaScript(): Boolean = ApplicationManager.getApplication().extensionArea.hasExtensionPoint(JS_PREDEFINED_LIBRARIES)

    /** TextMate's highlighting and plain text: a file type for `.c`, but no understanding of C. */
    private val NOT_A_C_ENGINE = setOf("textmate", "TEXT")

    private const val LSP4IJ_SERVERS = "com.redhat.devtools.lsp4ij.server"
    private const val JS_PREDEFINED_LIBRARIES = "JavaScript.predefinedLibraryProvider"
}
