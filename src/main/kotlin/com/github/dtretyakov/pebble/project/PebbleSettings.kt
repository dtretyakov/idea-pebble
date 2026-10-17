package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Per-project choices that belong to the developer rather than to the code: which platform they
 * are looking at right now.
 *
 * In the workspace file, not with the project: the platforms an app is *for* are in its
 * `package.json` and shared; the one a developer happens to be testing is not, and committing it
 * would turn every switch into a change for the whole team.
 */
@Service(Service.Level.PROJECT)
@State(name = "PebbleWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PebbleSettings : PersistentStateComponent<PebbleSettings> {

    /** The platform last chosen; empty means "the default for this project". */
    var activePlatform: String = ""

    /**
     * Whether [PebbleProjectSetup] has laid out this project's roots. Once: someone who then
     * removes a source root meant to, and should not find it back on the next open.
     */
    var rootsConfigured: Boolean = false

    override fun getState(): PebbleSettings = this

    override fun loadState(state: PebbleSettings) = XmlSerializerUtil.copyBean(state, this)

    fun interface Listener {
        fun activePlatformChanged(project: Project)
    }

    companion object {
        /** Everything that reads the active platform: the compile database, runs, the emulator. */
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("Pebble active platform", Listener::class.java)

        fun getInstance(project: Project): PebbleSettings = project.service()
    }
}

/**
 * Which platform a project is being built, analysed and run for when nobody has said.
 *
 * The same default pebble-tool's `compile-commands` uses: emery when the project targets it — the
 * newest rectangular colour watch, and one of the two Alloy runs on — and otherwise the first
 * platform the project lists. A choice the project no longer targets is dropped rather than kept,
 * so removing a platform from `package.json` cannot leave the build pointed at it.
 */
object ActivePlatform {

    fun resolve(chosen: String, candidates: List<String>): String? = when {
        candidates.isEmpty() -> null
        chosen in candidates -> chosen
        PebblePlatform.EMERY.id in candidates -> PebblePlatform.EMERY.id
        else -> candidates.first()
    }

    /**
     * The platforms a project can be built for: those it names, or — when it names none, which
     * pebble-tool reads as all of them — every platform the SDK has, or failing that every one the
     * plugin knows.
     */
    fun candidates(targets: List<String>, sdkPlatforms: List<String>): List<String> = when {
        targets.isNotEmpty() -> targets
        sdkPlatforms.isNotEmpty() -> sdkPlatforms
        else -> PebblePlatform.entries.map { it.id }
    }
}
