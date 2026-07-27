package com.hanzidictionary.core

/**
 * How far a drag must travel to commit, given the distance the gesture nominally asks for
 * ([base]) and how much room the finger actually has ([available]) between where it went
 * down and the screen edge it is heading for.
 *
 * A flat threshold is unreachable whenever the grab starts near that edge. A tile in the
 * last column of the photo grid sits ~90% of the way across the screen, so its
 * 42%-of-screen commit distance ran off the glass: the drag ended when the finger did, short
 * of the threshold, and the action could never fire. Past that point the gesture has to mean
 * "drag it to the edge", which is always reachable.
 *
 * [EDGE_REACH] is under 1 because a drag ends the instant the finger leaves the glass, so
 * the last few dp at the bezel are never actually delivered.
 *
 * Lives in :core, not beside the gesture code, because it is the part worth pinning with a
 * test — this shipped broken twice.
 */
fun commitDistance(base: Float, available: Float): Float =
    minOf(base, (available * EDGE_REACH).coerceAtLeast(0f))

const val EDGE_REACH = 0.8f
