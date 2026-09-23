package com.example.timespeaker

import java.time.LocalTime

/**
 * Turns a wall-clock time into a phrase a text-to-speech engine reads naturally.
 *
 * Numbers are spelled out rather than passed as digits: engines read "7:05" inconsistently
 * ("seven zero five", "seven colon zero five"), while "seven oh five" is unambiguous.
 */
object TimeSpeech {

    private val HOURS = arrayOf(
        "twelve", "one", "two", "three", "four", "five",
        "six", "seven", "eight", "nine", "ten", "eleven"
    )

    private val UNITS = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )

    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty")

    fun phraseFor(time: LocalTime): String = phraseFor(time.hour, time.minute)

    fun phraseFor(hour24: Int, minute: Int): String {
        val hour = HOURS[hour24 % 12]
        val meridiem = if (hour24 < 12) "A M" else "P M"

        return when {
            minute == 0 -> "It's $hour o'clock $meridiem"
            minute < 10 -> "It's $hour oh ${UNITS[minute]} $meridiem"
            else -> "It's $hour ${minuteWords(minute)} $meridiem"
        }
    }

    /**
     * A bare number, for the countdown between announcements.
     *
     * Spelled out for the same reason as the times: engines read a lone digit inconsistently,
     * sometimes as an ordinal or a year.
     */
    fun number(value: Int): String = minuteWords(value.coerceIn(0, 59))

    /**
     * A count of half-minutes, spoken as "two" or "two point five".
     *
     * A lone half is "half a minute" rather than "zero point five", which is what a person
     * would actually say.
     */
    fun halfMinutes(halves: Int): String {
        val whole = halves / 2
        val hasHalf = halves % 2 == 1

        return when {
            !hasHalf -> number(whole)
            whole == 0 -> "half a minute"
            else -> "${number(whole)} point five"
        }
    }

    private fun minuteWords(minute: Int): String {
        if (minute < 20) return UNITS[minute]
        val tens = TENS[minute / 10]
        val unit = minute % 10
        return if (unit == 0) tens else "$tens ${UNITS[unit]}"
    }
}
