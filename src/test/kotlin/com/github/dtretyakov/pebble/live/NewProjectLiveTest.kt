package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.project.NewPebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.testing.IdeTestCase
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/** Projects made as the wizard makes them — into a directory that already exists — build. */
class NewProjectLiveTest : IdeTestCase() {

    fun `test a C watchface and an Alloy app are made and build`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val parent = Files.createTempDirectory("pebble-wizard")

        val face = parent.resolve("my-face").createDirectories()
        Files.createDirectories(face.resolve(".idea")) // what the IDE has already put there
        NewPebbleProject.generate(tool, face, NewPebbleProject.Options(watchface = true))
        val info = PebbleProjectInfo.parse(face.resolve("package.json").readText())!!
        assertTrue(info.watchface)
        assertEquals("my-face", info.name)
        assertEquals(0, LiveSdk.run(face, "build").exitCode)

        val alloy = parent.resolve("alloy-app")
        NewPebbleProject.generate(tool, alloy, NewPebbleProject.Options(NewPebbleProject.Language.ALLOY, watchface = false))
        val alloyInfo = PebbleProjectInfo.parse(alloy.resolve("package.json").readText())!!
        assertEquals(PebbleProjectInfo.ProjectType.ALLOY, alloyInfo.projectType)
        assertFalse(alloyInfo.watchface)
        val build = LiveSdk.run(alloy, "build")
        assertEquals(build.text.takeLast(2000), 0, build.exitCode)
    }
}
