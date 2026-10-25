package com.evilknivel.phonewhisper

/**
 * The text a field should hold after inserting [insert] at its caret, for `ACTION_SET_TEXT`.
 *
 * A field that shows its placeholder ([showingHint], or text equal to [hint]) counts as empty, so
 * "Message" isn't kept in front of the dictation. Returns null when the field has text but the
 * caret position is unknown: guessing could put the text in the wrong place or keep a placeholder
 * that isn't marked as one (Telegram does this), so the caller should paste instead.
 */
fun textAfterInsert(
    current: String,
    hint: String?,
    showingHint: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
    insert: String,
): String? {
    val existing = if (showingHint || (!hint.isNullOrEmpty() && current == hint)) "" else current
    if (existing.isEmpty()) return insert
    if (selectionStart < 0 || selectionEnd < 0 || selectionStart > existing.length || selectionEnd > existing.length) return null
    return existing.replaceRange(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd), insert)
}
