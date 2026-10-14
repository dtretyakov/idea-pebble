package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement

/**
 * Where in a `package.json` a string sits, as far as the `pebble` section is concerned.
 *
 * The npm schema the IDE applies to `package.json` is the only schema it will use for the file —
 * a second one for the `pebble` section is attached and then ignored — so this section gets its
 * completion and checks from the JSON tree directly, for the two values worth checking: the
 * platforms and the project type.
 */
internal object PebblePackageJson {

    enum class Slot { TARGET_PLATFORM, PROJECT_TYPE }

    /** The `pebble` object of a `package.json`, or null for any other file. */
    fun pebbleSection(file: JsonFile): JsonObject? {
        if (file.name != PebbleProjectInfo.FILE_NAME) return null
        return (file.topLevelValue as? JsonObject)?.findProperty("pebble")?.value as? JsonObject
    }

    /** Which value [literal] is, or null when it is not one of the two. */
    fun slotOf(literal: JsonStringLiteral): Slot? {
        val parent = literal.parent
        val property = when (parent) {
            is JsonArray -> parent.parent as? JsonProperty
            is JsonProperty -> parent.takeIf { it.value == literal }
            else -> null
        } ?: return null
        val section = property.parent as? JsonObject ?: return null
        val file = section.containingFile as? JsonFile ?: return null
        if (pebbleSection(file) != section) return null
        return when {
            property.name == "targetPlatforms" && parent is JsonArray -> Slot.TARGET_PLATFORM
            property.name == "projectType" && parent is JsonProperty -> Slot.PROJECT_TYPE
            else -> null
        }
    }

    /** The project type the section declares, as pebble-tool reads it: native when absent. */
    fun projectType(element: PsiElement): PebbleProjectInfo.ProjectType {
        val file = element.containingFile as? JsonFile ?: return PebbleProjectInfo.ProjectType.NATIVE
        val value = (pebbleSection(file)?.findProperty("projectType")?.value as? JsonStringLiteral)?.value
        return PebbleProjectInfo.ProjectType.of(value)
    }

    /** Every platform worth offering: the active SDK's, and the ones the plugin knows besides. */
    fun platforms(): List<String> =
        (PebbleSdkService.getInstance().currentSdk()?.platforms().orEmpty() + PebblePlatform.entries.map { it.id })
            .distinct()

    val PROJECT_TYPES = listOf(
        PebbleProjectInfo.ProjectType.NATIVE to "C",
        PebbleProjectInfo.ProjectType.ALLOY to "Alloy, JavaScript on the watch",
        PebbleProjectInfo.ProjectType.PACKAGE to "a library for other projects",
        PebbleProjectInfo.ProjectType.ROCKY to "Rocky.js",
    )
}
