package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleProjectInfo.ProjectType
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor

/**
 * The mistakes in a `package.json`'s `pebble` section that pebble-tool would only report at the
 * next build, or not at all: a platform that does not exist, Alloy asked to run where it cannot,
 * a project type the SDK cannot build, a resource whose file is not there — a Python traceback
 * from waf otherwise — and a message key nothing uses.
 */
class PebblePackageJsonInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val projectDir = holder.file.virtualFile?.parent
        // Read once per pass over the file, and only if it declares a message key.
        val identifiers by lazy { projectDir?.let(PebbleProjectFiles::identifiers) }
        return object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                when (PebblePackageJson.slotOf(literal)) {
                    PebblePackageJson.Slot.TARGET_PLATFORM -> checkPlatform(literal, holder)
                    PebblePackageJson.Slot.PROJECT_TYPE -> checkProjectType(literal, holder)
                    PebblePackageJson.Slot.RESOURCE_FILE -> if (projectDir != null) checkResource(literal, projectDir, holder)
                    PebblePackageJson.Slot.MESSAGE_KEY -> identifiers?.let { checkMessageKey(literal, it, holder) }
                    null -> Unit
                }
            }
        }
    }

    private fun checkResource(literal: JsonStringLiteral, projectDir: VirtualFile, holder: ProblemsHolder) {
        val declared = literal.value
        if (declared.isBlank() || PebbleProjectFiles.resourceExists(projectDir, declared)) return
        holder.registerProblem(
            literal,
            "No ${PebbleProjectFiles.RESOURCES}/$declared, nor a variant of it such as ${PebbleProjectFiles.variantOf(declared)}: the build will fail.",
            ProblemHighlightType.GENERIC_ERROR,
        )
    }

    private fun checkMessageKey(literal: JsonStringLiteral, identifiers: Set<String>, holder: ProblemsHolder) {
        val name = PebblePackageJson.messageKeyName(literal.value)
        if (name.isEmpty() || name in identifiers) return
        holder.registerProblem(literal, "Message key '$name' is used nowhere in src/ or worker_src/.", ProblemHighlightType.LIKE_UNUSED_SYMBOL)
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
