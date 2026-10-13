package com.github.dtretyakov.pebble.sdk

/**
 * The hardware platforms an app is built for, one binary each.
 *
 * Kept here, rather than only read from the SDK, because what the plugin needs to know about a
 * platform — its screen, and whether Alloy runs on it — is not in the SDK in any form a client can
 * read. The SDK's own list (`pebble/<platform>/` under `sdk-core`) stays authoritative for which
 * platforms exist: one this enum has not heard of is still offered, just without a description.
 */
enum class PebblePlatform(
    val id: String,
    val watches: String,
    val width: Int,
    val height: Int,
    val color: Boolean,
    val round: Boolean,
    val touch: Boolean,
    /** Whether Alloy (Moddable XS) apps run here; everywhere else is C only. */
    val alloy: Boolean,
) {
    APLITE("aplite", "Pebble Classic, Pebble Steel", 144, 168, color = false, round = false, touch = false, alloy = false),
    BASALT("basalt", "Pebble Time, Pebble Time Steel", 144, 168, color = true, round = false, touch = false, alloy = false),
    CHALK("chalk", "Pebble Time Round", 180, 180, color = true, round = true, touch = false, alloy = false),
    DIORITE("diorite", "Pebble 2", 144, 168, color = false, round = false, touch = false, alloy = false),
    FLINT("flint", "Pebble 2 Duo", 144, 168, color = false, round = false, touch = false, alloy = false),
    EMERY("emery", "Pebble Time 2", 200, 228, color = true, round = false, touch = true, alloy = true),
    GABBRO("gabbro", "Pebble Round 2", 260, 260, color = true, round = true, touch = true, alloy = true);

    override fun toString(): String = id

    companion object {
        fun of(id: String): PebblePlatform? = entries.firstOrNull { it.id == id }
    }
}
