package com.github.dtretyakov.pebble.lang

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * What the checks of a `package.json` look for beside it, through VFS so that a file just added,
 * or an edit not yet saved, counts.
 */
internal object PebbleProjectFiles {

    /**
     * Whether the build finds a resource's file: `resources/<file>` itself, or a variant of it
     * tagged for some platforms — `images/logo~color.png` and `images/logo~bw.png` for
     * `images/logo.png` — which waf picks per platform (`find_most_specific_filename`).
     */
    fun resourceExists(projectDir: VirtualFile, declared: String): Boolean {
        val resources = projectDir.findChild(RESOURCES) ?: return false
        resources.findFileByRelativePath(declared)?.let { if (!it.isDirectory) return true }
        val path = declared.substringBeforeLast('/', "")
        val directory = (if (path.isEmpty()) resources else resources.findFileByRelativePath(path)) ?: return false
        val (base, extension) = split(declared.substringAfterLast('/'))
        return directory.children.any { !it.isDirectory && it.name.startsWith("$base~") && it.name.endsWith(extension) }
    }

    /** `images/logo.png` as the colour variant waf would also accept: `images/logo~color.png`. */
    fun variantOf(declared: String): String {
        val path = declared.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val (base, extension) = split(declared.substringAfterLast('/'))
        return "$path$base~color$extension"
    }

    /**
     * Every identifier in the app's sources — C, PebbleKit JS, Alloy, and the JSON next to them,
     * where Clay's configuration names message keys — with `MESSAGE_KEY_` taken off C's names.
     */
    fun identifiers(projectDir: VirtualFile): Set<String> {
        val found = HashSet<String>()
        for (name in SOURCE_DIRECTORIES) {
            val directory = projectDir.findChild(name) ?: continue
            VfsUtilCore.iterateChildrenRecursively(directory, null) { file ->
                if (!file.isDirectory && file.extension in SOURCE_EXTENSIONS && file.length < MAX_SOURCE_SIZE) {
                    val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence
                        ?: runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
                    WORD.findAll(text).forEach { found += it.value.removePrefix(C_PREFIX) }
                }
                true
            }
        }
        return found
    }

    private fun split(name: String): Pair<String, String> {
        val base = name.substringBeforeLast('.', name)
        return base to name.substring(base.length)
    }

    const val RESOURCES = "resources"

    private const val C_PREFIX = "MESSAGE_KEY_"
    private const val MAX_SOURCE_SIZE = 1L shl 20
    private val SOURCE_DIRECTORIES = listOf("src", "worker_src")
    private val SOURCE_EXTENSIONS = setOf("c", "h", "js", "mjs", "ts", "json")
    private val WORD = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
}
