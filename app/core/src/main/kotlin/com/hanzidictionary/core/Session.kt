package com.hanzidictionary.core

/**
 * How long the app may sit in the background before the trail it left behind stops being
 * worth restoring.
 *
 * Coming back to a dictionary after half an hour is a new errand, not a continuation of the
 * old one: the four pages deep you were is someone else's browse path by then, and Back
 * walking you through it is a maze. Long enough that putting the phone down mid-lookup —
 * a conversation, a bus stop — still returns you to where you were.
 */
const val SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * True when the app has been away long enough that its navigation history should be dropped.
 *
 * [lastActiveAt] is null on a cold start, which is already a clean stack — nothing to forget.
 * A clock that has moved backwards (timezone change, NTP correction) reads as *not* stale,
 * because the one thing worse than a stale back stack is one that vanishes while you're
 * looking at it.
 */
fun isStaleSession(
    lastActiveAt: Long?,
    now: Long,
    timeout: Long = SESSION_IDLE_TIMEOUT_MS,
): Boolean = lastActiveAt != null && now - lastActiveAt >= timeout
