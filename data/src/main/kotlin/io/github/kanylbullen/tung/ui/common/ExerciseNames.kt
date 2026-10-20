package io.github.kanylbullen.tung.ui.common

import android.annotation.SuppressLint
import android.content.Context
import io.github.kanylbullen.tung.data.Exercise
import java.util.concurrent.ConcurrentHashMap

/**
 * Catalogue exercises are named by the string resource `ex_<nameKey>`, so the
 * name follows the phone's language. Custom exercises have no key and show
 * what the user typed. A key without a resource falls back to [Exercise.name].
 *
 * The `ex_*` strings are generated into each app's own resources (`app/` and
 * `wear/`, by `catalog/tools/gen_android.py`); the lookup is by name in the
 * running app's package, so this works in both. The composable wrapper,
 * `exerciseDisplayName`, is in the phone app.
 */
object ExerciseNames {
    /** nameKey → string resource id, 0 when there is none. Resource ids never change at runtime. */
    private val ids = ConcurrentHashMap<String, Int>()

    @SuppressLint("DiscouragedApi") // The key is data; this is the one lookup, cached per key.
    private fun resId(context: Context, key: String): Int =
        ids.getOrPut(key) { context.resources.getIdentifier("ex_$key", "string", context.packageName) }

    fun resolve(context: Context, nameKey: String?, fallback: String): String {
        if (nameKey == null) return fallback
        val id = resId(context, nameKey)
        return if (id == 0) fallback else context.getString(id)
    }
}

fun Exercise.displayName(context: Context): String = ExerciseNames.resolve(context, nameKey, name)
