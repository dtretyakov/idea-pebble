package com.github.dtretyakov.pebble.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Settings that belong to the machine rather than to any one project: where pebble-tool is.
 *
 * Not roamed — a path to an executable on one computer means nothing on another.
 */
@Service(Service.Level.APP)
@State(
    name = "Pebble",
    storages = [Storage("pebble.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.TOOLS,
)
class PebbleAppSettings : PersistentStateComponent<PebbleAppSettings> {

    /** Path to the `pebble` executable. Empty means "find it". */
    var pebbleToolPath: String = ""

    override fun getState(): PebbleAppSettings = this

    override fun loadState(state: PebbleAppSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): PebbleAppSettings = service()
    }
}
