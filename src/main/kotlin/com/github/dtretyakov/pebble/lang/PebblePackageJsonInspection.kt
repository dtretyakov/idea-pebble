package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleProjectInfo.ProjectType
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElementVisitor

/**
 * The mistakes in a `package.json`'s `pebble` section that pebble-tool would only report at the
 * next build, or not at all: a platform that does not exist, Alloy asked to run where it cannot,
 * and a project type the SDK cannot build.
 */
class PebblePackageJsonInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                when (PebblePackageJson.slotOf(literal)) {
                    PebblePackageJson.Slot.TARGET_PLATFORM -> checkPlatform(literal, holder)
                    PebblePackageJson.Slot.PROJECT_TYPE -> checkProjectType(literal, holder)
                    null -> Unit
                }
            }
        }

    private fun checkPlatform(literal: JsonStringLiteral, holder: ProblemsHolder) {
        val id = literal.value
        if (id !in PebblePackageJson.platforms()) {
            holder.registerProblem(
                literal,
                "Unknown platform '$id'. Pebble platforms are ${PebblePackageJson.platforms().joinToString()}.",
                ProblemHighlightType.GENERIC_ERROR,
            )
            return
        }
        if (PebblePackageJson.projectType(literal) == ProjectType.ALLOY && PebblePlatform.of(id)?.alloy == false) {
            holder.registerProblem(literal, "Alloy does not run on $id; it runs on emery and gabbro.")
        }
    }

    private fun checkProjectType(literal: JsonStringLiteral, holder: ProblemsHolder) {
        if (ProjectType.of(literal.value) != ProjectType.UNKNOWN) return
        holder.registerProblem(
            literal,
            "pebble-tool cannot build a '${literal.value}' project. " +
                "Project types are ${PebblePackageJson.PROJECT_TYPES.joinToString { it.first.id }}.",
            ProblemHighlightType.GENERIC_ERROR,
        )
    }
}
