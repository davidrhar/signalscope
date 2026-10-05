package com.signalscope.ui

enum class Tab(val label: String, val title: String, val subtitle: String) {
    LIVE("Now", "Now", ""),
    /**
     * No longer in the bottom bar. What happened is history, and history belongs with the thing
     * it is the history OF -- so it opens from [LIVE] instead of sitting beside it as a peer.
     * Kept as a Tab because the screen is still reached, just not from the nav.
     */
    TIMELINE("History", "Incidents", "what broke, and why"),
    MAP("Map", "Coverage", "measured outcome, not bars"),
    DIAGNOSIS("Diagnosis", "Diagnosis", "what is wrong · what you can do about it"),

    /** Not in the bottom bar: reached from the gear in the header. */
    SETTINGS("Settings", "Settings", "how the app behaves · your data")
}
