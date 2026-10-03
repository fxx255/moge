package com.moge.app.ui

import androidx.navigation.NavHostController

/** A swipe belongs to one pair of navigation entries, including a swipe that pops the stack. */
internal data class SwipeTransition(val from: String, val to: String, val direction: Int) {
    fun directionFor(from: String, to: String): Int =
        if (this.from == from && this.to == to) direction else 0
}

internal fun navigateWithSwipe(nav: NavHostController, direction: Int, navigate: () -> Unit): SwipeTransition? {
    val from = nav.currentBackStackEntry?.id
    navigate()
    val to = nav.currentBackStackEntry?.id
    return if (direction != 0 && from != null && to != null && from != to) SwipeTransition(from, to, direction) else null
}
