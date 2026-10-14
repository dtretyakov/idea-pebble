package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.PlatformStubs
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * A call to an SDK function that some of the project's target platforms only stub out
 * ([PlatformStubs]), made where no `PBL_` condition chooses the platforms ([PlatformGuards]).
 *
 * The build cannot catch it: the stub compiles. So the app builds for aplite, installs, and the
 * call quietly does nothing there.
 *
 * Registered for no language in particular, because a C file is CLion's own language in CLion,
 * TextMate's in IDEA, and plain text in WebStorm, and the check reads only the text.
 */
class PlatformStubCallInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file.virtualFile?.extension !in C_EXTENSIONS) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) = check(file, holder)
        }
    }

    private fun check(file: PsiFile, holder: ProblemsHolder) {
        val model = PebbleProject.getInstance(file.project)
        val root = file.virtualFile?.let(model::rootFor) ?: return
        val sdk = PebbleSdkService.getInstance().currentSdk() ?: return
        val targets = model.platforms(root)
        val stubs = targets.associateWith { PlatformStubs.inert(sdk, it) }
        val names = stubs.values.flatten().toSet()
        if (names.isEmpty()) return
        for (call in PlatformGuards.unguardedCalls(file.text, names)) {
            val stubbedOn = targets.filter { call.name in stubs.getValue(it) }
            holder.registerProblem(file, TextRange(call.start, call.end), message(call.name, stubbedOn, stubbedOn.size == targets.size))
        }
    }

    companion object {
        private val C_EXTENSIONS = setOf("c", "h")

        fun message(name: String, platforms: List<String>, everywhere: Boolean): String =
            if (everywhere) {
                "$name() does nothing on any platform this project targets: the SDK stubs it out on ${platforms.joinToString()}."
            } else {
                "$name() does nothing on ${platforms.joinToString()}: the SDK stubs it out there. " +
                    "Guard the call with a PBL_ condition, or leave those platforms out of targetPlatforms."
            }
    }
}
