package com.signalscope.ui

enum class Tab(val label: String, val title: String, val subtitle: String) {
    LIVE("Live", "Live", ""),
    TIMELINE("Timeline", "Incidents", "what broke, and why"),
    MAP("Map", "Coverage", "measured outcome, not bars"),
    ACTIONS("Actions", "Actions", "what is wrong · what you can do · what is not measured")
}
