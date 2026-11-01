package com.paperlens.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes

/**
 * The app's text, from the `strings.xml` resources in the phone's (or the app's chosen) language.
 * Usable anywhere — screens, click handlers, toasts, enum labels — so text never has to be written
 * in code. A language change recreates the screens, which read the new text.
 */
object S {
    // The application context lives as long as the process: keeping it here leaks nothing.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun str(@StringRes id: Int, vararg args: Any?): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
}
