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
 * completion and checks from the JSON tree directly, for the values worth checking: the
 * platforms, the project type, the resources' files and the message keys.
 */
internal object PebblePackageJson {

    enum class Slot { TARGET_PLATFORM, PROJECT_TYPE, RESOURCE_FILE, MESSAGE_KEY }

    /** The `pebble` object of a `package.json`, or null for any other file. */
    fun pebbleSection(file: JsonFile): JsonObject? {
        if (file.name != PebbleProjectInfo.FILE_NAME) return null
        return (file.topLevelValue as? JsonObject)?.findProperty("pebble")?.value as? JsonObject
    }

    /** Which value [literal] is, or null when it is none of them. */
    fun slotOf(literal: JsonStringLiteral): Slot? {
        val parent = literal.parent
        // The object form of the message keys, {"KEY": 10000}: the key is the property's name.
        if (parent is JsonProperty && parent.nameElement == literal) {
            val keys = (parent.parent as? JsonObject)?.parent as? JsonProperty
            return Slot.MESSAGE_KEY.takeIf { keys?.name == "messageKeys" && inSection(keys) }
        }
        val property = when (parent) {
            is JsonArray -> parent.parent as? JsonProperty
            is JsonProperty -> parent.takeIf { it.value == literal }
            else -> null
        } ?: return null
        // resources.media[].file
        if (property.name == "file" && parent is JsonProperty) {
            val media = ((property.parent as? JsonObject)?.parent as? JsonArray)?.parent as? JsonProperty
            val resources = (media?.parent as? JsonObject)?.parent as? JsonProperty
            return Slot.RESOURCE_FILE.takeIf { media?.name == "media" && resources?.name == "resources" && inSection(resources) }
        }
        if (!inSection(property)) return null
        return when {
            property.name == "targetPlatforms" && parent is JsonArray -> Slot.TARGET_PLATFORM
            property.name == "projectType" && parent is JsonProperty -> Slot.PROJECT_TYPE
            property.name == "messageKeys" && parent is JsonArray -> Slot.MESSAGE_KEY
            else -> null
        }
    }

    /** The name a message key gives its C constant: `KEY[4]`, an array of four, is `KEY`. */
    fun messageKeyName(declared: String): String = declared.substringBefore('[')

    private fun inSection(property: JsonProperty): Boolean {
        val section = property.parent as? JsonObject ?: return false
        val file = section.containingFile as? JsonFile ?: return false
        return pebbleSection(file) == section
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
