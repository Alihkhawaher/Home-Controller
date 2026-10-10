package com.technlov.homecontroller.data

import java.time.DayOfWeek
import java.util.Locale

/**
 * The user's weekend, for the "Workdays" and "Weekend" shortcuts in schedules and for how their days are named.
 * Saved only when the user picks one; until then it follows the phone's region ([forRegion]).
 */
enum class Weekend(val days: Set<DayOfWeek>) {
    FRI_SAT(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)),
    SAT_SUN(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
    SUN(setOf(DayOfWeek.SUNDAY)),
    FRI(setOf(DayOfWeek.FRIDAY)),
    ;

    /** Every day that is not the weekend. */
    val workdays: Set<DayOfWeek> get() = DayOfWeek.entries.toSet() - days

    companion object {
        /**
         * Countries whose weekend is Friday and Saturday (ISO 3166 codes): Saudi Arabia, the other Gulf states
         * except the UAE (Saturday and Sunday since 2022), and others in the Middle East, North Africa and South Asia.
         */
        private val FRIDAY_SATURDAY = setOf(
            "SA", "BH", "KW", "OM", "QA", "YE", "IQ", "JO", "SY", "PS", "IL", "EG", "LY", "DZ", "SD", "BD", "MV",
        )

        /** The usual weekend in [region] (an ISO country code, any case): Friday and Saturday there, else Saturday and Sunday. */
        fun forRegion(region: String?): Weekend =
            if (region?.trim()?.uppercase(Locale.ROOT) in FRIDAY_SATURDAY) FRI_SAT else SAT_SUN
    }
}

/** The editor's quick picks for a schedule's days. */
enum class DayPreset {
    EVERY_DAY,
    WORKDAYS,
    WEEKEND,
    ;

    /** The days this pick stands for (every day is stored as no days at all, as the engine reads it). */
    fun days(weekend: Weekend): Set<DayOfWeek> = when (this) {
        EVERY_DAY -> emptySet()
        WORKDAYS -> weekend.workdays
        WEEKEND -> weekend.days
    }

    companion object {
        /** Which pick [days] amount to, or null for any other set of days. */
        fun of(days: Set<DayOfWeek>, weekend: Weekend): DayPreset? = when {
            days.isEmpty() || days.size == 7 -> EVERY_DAY
            days == weekend.workdays -> WORKDAYS
            days == weekend.days -> WEEKEND
            else -> null
        }
    }
}
