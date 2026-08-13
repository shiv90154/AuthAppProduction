package com.example.myapplication

/**
 * Tracks whether a text field (e.g. the Rename Kit dialog) currently has
 * focus, so the hardware-keyboard pad shortcuts (Q W E R / A S D F) can be
 * suspended while the user is actually typing text.
 */
object KeyboardPlayState {
    @Volatile
    var textInputActive: Boolean = false
}
