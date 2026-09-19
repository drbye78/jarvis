package com.jarvis.assistant.ui

import android.content.Context
import com.google.android.material.textfield.TextInputLayout

/**
 * Attaches [FieldValidation] errors to the offending input (U7).
 *
 * `TextInputLayout.setError` renders the message inline, under the field it
 * belongs to, keeps it until the input is corrected, and exposes it to
 * accessibility services as the field's own error — none of which a Toast
 * does. The caller owns the clear step (see [clearAll]) so a corrected field
 * does not keep showing a stale message.
 */
object FieldErrorRenderer {

    /**
     * Clears every layout, then attaches the supplied errors. Clearing first
     * is what makes a corrected save stop showing the previous message.
     */
    fun render(
        errors: List<FieldValidation.FieldError>,
        layouts: Map<FieldValidation.Field, TextInputLayout>,
        context: Context,
    ) {
        clearAll(layouts)
        for (error in errors) {
            val layout = layouts[error.field] ?: continue
            layout.error = context.getString(error.messageRes)
        }
    }

    fun clearAll(layouts: Map<FieldValidation.Field, TextInputLayout>) {
        for (layout in layouts.values) layout.error = null
    }
}
