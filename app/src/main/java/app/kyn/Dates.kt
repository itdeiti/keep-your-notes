package app.kyn

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates and times in the app's language and the device's 12/24 h setting. */
object Dates {
    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]

    private fun fmt(ctx: Context, skeleton: String): DateTimeFormatter {
        val l = locale(ctx)
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(l, skeleton), l)
    }

    /** "18.11." / "11/18" */
    fun dayMonth(ctx: Context, d: LocalDate): String = d.format(fmt(ctx, "dM"))

    /** "Mi., 18.11." / "Wed, 11/18"; with the year when it is not the current one. */
    fun weekdayDate(ctx: Context, d: LocalDate): String =
        d.format(fmt(ctx, if (d.year == LocalDate.now().year) "EEEdM" else "EEEdMy"))

    /** "14:05" / "2:05 PM" */
    fun time(ctx: Context, t: LocalTime): String =
        t.format(fmt(ctx, if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm"))
}
