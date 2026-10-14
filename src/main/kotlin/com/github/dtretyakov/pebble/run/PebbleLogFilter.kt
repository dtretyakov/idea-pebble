package com.github.dtretyakov.pebble.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Turns the places in a Pebble log into links to the source.
 *
 * The watch names a file without its directory — `[14:02:11] main.c:42> Hello` — so the name is
 * looked up among the project's C sources. Firmware lines name files the project does not have
 * (`process_manager.c`), and stay text. PebbleKit JS lines carry a path from the project root,
 * `pkjs> ./src/pkjs/index.js:3:0 Hello`. An Alloy exception, as the debugger writes it, carries
 * the source's absolute path: `/…/src/embeddedjs/main.js:16: # Exception: …`.
 */
class PebbleLogFilter(private val project: Project, private val root: Path) : Filter {

    /** Every C source by file name, read once per run: a log can be long and a project is small. */
    private val sources: Map<String, List<Path>> by lazy {
        SOURCE_DIRECTORIES.map(root::resolve).filter { it.isDirectory() }.flatMap { directory ->
            Files.walk(directory).use { paths ->
                paths.asSequence().filter { it.name.endsWith(".c") || it.name.endsWith(".h") }.toList()
            }
        }.groupBy { it.name }
    }

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length

        WATCH.find(line)?.let { match ->
            val group = match.groups[1]!!
            val file = sources[group.value]?.singleOrNull() ?: return null
            val lineNumber = match.groupValues[2].toInt()
            return link(file, lineNumber - 1, 0, start + group.range.first, start + match.groups[2]!!.range.last + 1)
        }

        ALLOY.find(line)?.let { match ->
            val file = Path.of(match.groupValues[1])
            if (!Files.isRegularFile(file)) return null
            return link(file, match.groupValues[2].toInt() - 1, 0, start + match.range.first, start + match.groups[2]!!.range.last + 1)
        }

        PKJS.find(line)?.let { match ->
            val file = root.resolve(match.groupValues[1]).normalize()
            if (!Files.isRegularFile(file)) return null
            return link(
                file, match.groupValues[2].toInt() - 1, match.groupValues[3].toInt(),
                start + match.groups[1]!!.range.first, start + match.range.last + 1,
            )
        }
        return null
    }

    private fun link(file: Path, line: Int, column: Int, from: Int, to: Int): Filter.Result? {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return null
        return Filter.Result(from, to, OpenFileHyperlinkInfo(project, virtualFile, line.coerceAtLeast(0), column))
    }

    private companion object {
        val SOURCE_DIRECTORIES = listOf("src", "worker_src", "include")

        /** `[14:02:11] main.c:42> message` — the time is the host's, the rest is the watch's. */
        val WATCH = Regex("""^\s*\[\d{2}:\d{2}:\d{2}] ([\w.\-]+\.[ch]):(\d+)>""")

        /** `/…/src/embeddedjs/main.js:16: # Exception: …`, from the Alloy debugger. */
        val ALLOY = Regex("""^(/[^:]+\.(?:js|mjs|ts)):(\d+): """)

        /** `pkjs> ./src/pkjs/index.js:3:0 message`, source-mapped by pebble-tool. */
        val PKJS = Regex("""pkjs> (\.?/?src/[^\s:]+):(\d+):(\d+)""")
    }
}
