package dev.otmgram.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The app's texts from its resources, for code outside composables (which use
 * stringResource). An interface so unit tests can read the same texts without Android.
 */
interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String

    /** The form of [id] for [count]; [args] fill it in, usually starting with the count. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

class AndroidStrings @Inject constructor(@ApplicationContext private val context: Context) : Strings {
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)
}
