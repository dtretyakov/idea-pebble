package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PebbleLogFilterTest : IdeTestCase() {

    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("pebble-log")
        root.resolve("src/c").createDirectories().resolve("main.c").writeText("int main(void) {}\n")
        root.resolve("src/pkjs").createDirectories().resolve("index.js").writeText("console.log(1);\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
    }

    fun `test a watch log line links its file and line`() {
        val line = "[14:02:11] main.c:42> Hello\n"
        val result = PebbleLogFilter(project, root).applyFilter(line, 100 + line.length)!!
        val item = result.resultItems.single()
        assertEquals("main.c:42", line.substring(item.highlightStartOffset - 100, item.highlightEndOffset - 100))
        assertEquals(41, (item.hyperlinkInfo as OpenFileHyperlinkInfo).descriptor!!.line)
    }

    fun `test firmware files are left as text`() {
        assertNull(PebbleLogFilter(project, root).applyFilter("[14:02:11] ocess_manager.c:487> Heap Usage\n", 50))
    }

    fun `test a PebbleKit JS line links the script`() {
        val line = "[14:02:11] pkjs> ./src/pkjs/index.js:3:0 Hello\n"
        val item = PebbleLogFilter(project, root).applyFilter(line, line.length)!!.resultItems.single()
        assertEquals("./src/pkjs/index.js:3:0", line.substring(item.highlightStartOffset, item.highlightEndOffset))
    }
}
