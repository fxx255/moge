package com.moge.app.ui

import androidx.navigation.NavHostController

/** A swipe belongs to one pair of navigation entries, including a swipe that pops the stack. */
internal data class SwipeTransition(
    val from: String, val to: String, val direction: Int,
    val stationaryComposer: Boolean = false,
) {
    fun directionFor(from: String, to: String): Int =
        if (this.from == from && this.to == to) direction else 0
}

internal fun navigateWithSwipe(nav: NavHostController, direction: Int, navigate: () -> Unit): SwipeTransition? {
    val source = nav.currentBackStackEntry
    val from = source?.id
    navigate()
    val to = nav.currentBackStackEntry?.id
    return if (direction != 0 && from != null && to != null && from != to) SwipeTransition(from, to, direction,
        stationaryComposer = source?.destination?.route == Routes.SOLVE && nav.currentDestination?.route == Routes.SOLVE) else null
}
