package com.signalscope.ui

enum class Tab(val label: String, val title: String, val subtitle: String) {
    LIVE("Live", "Live", ""),
    TIMELINE("Timeline", "Incidents", "what broke, and why"),
    MAP("Map", "Coverage", "measured outcome, not bars"),
    DIAGNOSIS("Diagnosis", "Diagnosis", "what is wrong · what you can do about it"),

    /** Not in the bottom bar: reached from the gear in the header. */
    SETTINGS("Settings", "Settings", "how the app behaves · your data")
}
