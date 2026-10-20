package com.github.dtretyakov.pebble.ui.wizard

import com.github.dtretyakov.pebble.agent.AgentSkill
import com.github.dtretyakov.pebble.project.NewPebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleSdkSetup
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.github.dtretyakov.pebble.ui.EnvironmentFixes
import com.github.dtretyakov.pebble.ui.SetupMessage
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.toMutableProperty
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.selected
import javax.swing.JRadioButton

/**
 * The choices a new Pebble project needs, shared by IDEA's New Project wizard and the directory
 * generator CLion and WebStorm show.
 */
internal object NewProjectOptionsUi {

    fun build(panel: Panel, options: NewPebbleProject.Options) {
        if (PebbleSdkService.getInstance().tool() == null) {
            // What is first in the way, which is not always Set Up: Node.js before the SDK, WSL
            // before anything on Windows.
            val problem = PebbleEnvironment.firstProblem(PebbleEnvironment.check())?.takeIf { it.fix != null && !it.pending }
            panel.row {
                // The wizard is built once, so it cannot change under the user when the install
                // finishes; it says so instead of leaving them waiting for it to.
                text(
                    if (problem == null || problem.fix == PebbleEnvironment.Fix.SET_UP) {
                        "The Pebble SDK is not set up, and the templates come from it. Set it up, then open this wizard again."
                    } else {
                        "${SetupMessage.title(problem)}. ${SetupMessage.body(problem)} Then set up the Pebble SDK, and open this wizard again."
                    },
                )
            }
            panel.row {
                val link = if (problem != null) ActionLink(EnvironmentFixes.label(problem)) { EnvironmentFixes.run(null, problem) }
                else ActionLink(PebbleSdkSetup.LABEL) { PebbleSdkSetup.start(null) }
                cell(link).validationOnApply { error("Install pebble-tool before creating a project.") }
            }
            return
        }
        (PebbleSdkService.getInstance().host() as? WslToolHost)?.let { wsl ->
            panel.row {
                comment(
                    "pebble-tool runs in WSL (${wsl.distribution}). A project inside it — " +
                        "<code>\\\\wsl.localhost\\${wsl.distribution}\\home\\…</code> — builds several times faster than one on a Windows drive.",
                )
            }
        }

        lateinit var c: JRadioButton
        panel.buttonsGroup("Language:") {
            row {
                c = radioButton(NewPebbleProject.Language.C.display, NewPebbleProject.Language.C)
                    .comment("Every Pebble, from the original to Pebble Round 2").component
            }
            row {
                radioButton(NewPebbleProject.Language.ALLOY.display, NewPebbleProject.Language.ALLOY)
                    .comment("Pebble Time 2 and Pebble Round 2 (emery, gabbro)")
            }
        }.bind(options::language.toMutableProperty(), NewPebbleProject.Language::class.java)

        panel.row {
            checkBox("Watchface").bindSelected(options::watchface)
                .comment("Shown as the watch's face rather than in its app menu")
        }

        val isC: ComponentPredicate = c.selected
        panel.row {
            checkBox("PebbleKit JS (code on the phone)").bindSelected(options::javascript)
        }.visibleIf(isC)
        panel.row {
            checkBox("Background worker").bindSelected(options::worker)
        }.visibleIf(isC)
        panel.row {
            checkBox("Minimal main() only").bindSelected(options::simple)
        }.visibleIf(isC)
        panel.row {
            checkBox("Skill for coding agents").bindSelected(options::agentSkill)
                .comment("In ${AgentSkill.WHERE}: how to build, run and check the app with the IDE's tools")
        }
    }
}
