package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleProjectInfo.ProjectType
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Platform names in `targetPlatforms`, and the project types in `projectType`. */
class PebblePackageJsonCompletion : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            psiElement().withParent(JsonStringLiteral::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                    val literal = parameters.position.parent as? JsonStringLiteral ?: return
                    when (PebblePackageJson.slotOf(literal)) {
                        PebblePackageJson.Slot.TARGET_PLATFORM -> {
                            val alloy = PebblePackageJson.projectType(literal) == ProjectType.ALLOY
                            PebblePackageJson.platforms().forEach { id ->
                                val known = PebblePlatform.of(id)
                                if (alloy && known?.alloy == false) return@forEach
                                result.addElement(
                                    LookupElementBuilder.create(id)
                                        .withIcon(PebbleIcons.PEBBLE)
                                        .withTypeText(known?.watches, true),
                                )
                            }
                        }
                        PebblePackageJson.Slot.PROJECT_TYPE -> PebblePackageJson.PROJECT_TYPES.forEach { (type, text) ->
                            result.addElement(LookupElementBuilder.create(type.id).withTypeText(text, true))
                        }
                        PebblePackageJson.Slot.RESOURCE_FILE, PebblePackageJson.Slot.MESSAGE_KEY, null -> Unit
                    }
                }
            },
        )
    }
}
