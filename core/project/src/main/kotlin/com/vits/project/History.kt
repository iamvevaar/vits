package com.vits.project

/**
 * Immutable undo/redo stack. Continuous gestures (dragging a curve point, a volume slider) pass the
 * same [coalesceKey] so the whole gesture becomes one undo step; [seal] ends the gesture.
 */
class History<T> private constructor(
    val present: T,
    private val past: List<T>,
    private val future: List<T>,
    private val openKey: String?,
    private val limit: Int,
) {
    constructor(initial: T, limit: Int = 200) : this(initial, emptyList(), emptyList(), null, limit)

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(next: T, coalesceKey: String? = null): History<T> = when {
        next === present || next == present -> this
        coalesceKey != null && coalesceKey == openKey -> History(next, past, emptyList(), openKey, limit)
        else -> History(next, (past + present).takeLast(limit), emptyList(), coalesceKey, limit)
    }

    fun seal(): History<T> = if (openKey == null) this else History(present, past, future, null, limit)

    fun undo(): History<T> =
        if (!canUndo) this else History(past.last(), past.dropLast(1), listOf(present) + future, null, limit)

    fun redo(): History<T> =
        if (!canRedo) this else History(future.first(), past + present, future.drop(1), null, limit)
}
