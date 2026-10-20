package com.github.dtretyakov.pebble.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What is typed into the user's own shell for a command to run in WSL. */
class TerminalCommandTest {

    @Test
    fun `one line every Windows shell reads alike, in the project's directory`() {
        assertEquals(
            listOf("wsl -d Ubuntu -e bash -lc \"cd '/mnt/c/My App' && sudo apt update && sudo apt install nodejs npm\""),
            TerminalCommand.inWsl("Ubuntu", "/mnt/c/My App", "sudo apt update && sudo apt install nodejs npm"),
        )
        assertEquals(listOf("wsl -d Ubuntu -e bash -lc \"cd && npm rebuild\""), TerminalCommand.inWsl("Ubuntu", "~", "npm rebuild"))
    }

    @Test
    fun `a command a Windows shell would expand is typed into the distribution's shell instead`() {
        assertEquals(
            listOf("wsl -d Ubuntu", "cd /home/me && echo \"\$HOME\""),
            TerminalCommand.inWsl("Ubuntu", "/home/me", "echo \"\$HOME\""),
        )
    }
}
