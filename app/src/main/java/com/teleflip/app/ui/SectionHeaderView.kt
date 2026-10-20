package com.teleflip.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat

/**
 * Custom TextView component representing a section title or category header.
 * Automatically marks the view as an accessibility heading to conform to
 * WCAG 2.2 SC 1.3.1 (Info and Relationships) and SC 2.4.6 (Headings and Labels).
 */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        ViewCompat.setAccessibilityHeading(this, true)
    }
}
