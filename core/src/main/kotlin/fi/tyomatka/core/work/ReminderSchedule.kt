package fi.tyomatka.core.work

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Seuraava muistutuksen hetki; null, jos yhtään viikonpäivää ei ole valittu. Kesäaikaan siirryttäessä aukkoon osuva aika
 * siirtyy eteenpäin samalle päivälle, ja talviaikaan siirryttäessä kahdesti toistuva aika tulee vain kerran.
 */
fun nextReminder(now: ZonedDateTime, time: LocalTime, days: Set<DayOfWeek>): ZonedDateTime? {
    if (days.isEmpty()) return null
    return generateSequence(now.toLocalDate()) { it.plusDays(1) }
        .filter { it.dayOfWeek in days }
        .map { it.atTime(time).atZone(now.zone) }
        .first { it.isAfter(now) }
}
