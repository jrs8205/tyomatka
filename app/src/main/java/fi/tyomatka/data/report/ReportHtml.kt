package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.WorkHistoryDay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Locale

/** Täysikokoisen kuvan rivin alku: tuonti tunnistaa kuvarivit tästä ja lukee ne ilman, että koko tiedosto on muistissa. */
const val PHOTO_LINE = "<img class=\"kuva\" id=\"kuva-"

/** Kuvarivin src-attribuutin alku: vienti kirjoittaa sen sellaisenaan, tuonti hakee sitä. */
const val PHOTO_DATA = "src=\"data:image/jpeg;base64,"

/** Gallerian pienoiskuvan rivin alku: tuonti ohittaa rivin, ettei 200 pienoiskuvaa jää muistiin. */
const val THUMB_LINE = "<img class=\"tn\""

/** Varmuuskopion rivin laji tuonnissa. */
enum class BackupLine { PHOTO, THUMBNAIL, TEXT }

/** Tuonti lukee raportin riveittäin: täysikokoinen kuva tiedostoksi, pienoiskuva ohi ja muu teksti JSONin hakuun. */
fun backupLine(line: String): BackupLine = when {
    line.startsWith(PHOTO_LINE) -> BackupLine.PHOTO
    line.startsWith(THUMB_LINE) -> BackupLine.THUMBNAIL
    else -> BackupLine.TEXT
}

/**
 * Kirjoittaa raportin virtaan: valmis HTML, jonka skripti vain tehostaa. Kuvat kirjoitetaan yksi kerrallaan, eikä koko
 * tiedosto ole muistissa. [transferJson] on varmuuskopion JSON, joka upotetaan loppuun.
 */
fun writeReport(
    out: OutputStream,
    model: ReportModel,
    strings: ReportStrings,
    format: ReportFormat,
    photos: ReportPhotos,
    transferJson: String,
    nowEpochSec: Long,
) {
    ReportWriter(out, model, strings, format, photos).write(transferJson, nowEpochSec)
}

private class ReportWriter(
    private val out: OutputStream,
    private val model: ReportModel,
    private val strings: ReportStrings,
    private val format: ReportFormat,
    private val photos: ReportPhotos,
) {
    private val writer = out.bufferedWriter(Charsets.UTF_8)

    // Vain kuvat, joiden tiedoston voi avata: pienoiskuva, kirjauksen kuvanappi ja täysikokoinen rivi kulkevat yhdessä.
    private val available = model.photos.filter { photo -> openPhoto(photo)?.use { true } ?: false }
    private val photosByFillup = available.groupBy { it.fillup.id }
    private val entryIndex = model.entries.withIndex().associate { (i, entry) -> entry.fillup.id to i }
    // Tyhjällä jaksolla otsikko seuraa jakson autojen käyttövoimaa: sähköautolle "Lataukset".
    private val entriesTitle = strings.entriesTitle(model.entries.map { it.fillup.kind }.ifEmpty { model.carProfiles.mapNotNull { it.fuel } })

    private fun w(text: String) = writer.write(text)

    fun write(transferJson: String, nowEpochSec: Long) {
        head()
        w("<body>\n<div class=\"report\" id=\"report\">\n")
        w("<a class=\"skip\" href=\"#main\">${esc(strings.skipToContent)}</a>\n")
        header(nowEpochSec)
        searchBox()
        w("<main id=\"main\" tabindex=\"-1\">\n")
        summary()
        days()
        periods()
        entries()
        gallery()
        explain()
        backup()
        w("</main>\n")
        drawer()
        lightbox()
        w("</div>\n")
        scriptTexts()
        w("<script>\n${resource("report.js")}</script>\n")
        fullPhotos()
        w("\n<script type=\"application/json\" id=\"tyomatka-data\">")
        w(transferJson.replace("<", LT_ESCAPE))
        w("</script></body></html>\n")
        writer.flush()
    }

    // ---------- Otsikko-osat ----------

    private fun head() {
        w("<!doctype html>\n<html lang=\"${strings.language.tag}\">\n<head>\n<meta charset=\"utf-8\">\n")
        w("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        w("<meta name=\"color-scheme\" content=\"light dark\">\n")
        w("<title>${esc("${strings.title} · ${periodLabel()}")}</title>\n")
        // Skriptin tila heti, ettei pitkä sivu välähdä ennen osio kerrallaan -näkymää.
        w("<script>document.documentElement.classList.add('js')</script>\n")
        w("<style>\n${resource("report.css")}</style>\n</head>\n")
    }

    private fun header(nowEpochSec: Long) {
        val today = Instant.ofEpochSecond(nowEpochSec).atZone(format.zone).toLocalDate().toEpochDay()
        val exported = strings.exported(format.date(today, capitalize = false), format.clock(nowEpochSec))
        val span = dataSpan()?.takeIf { model.period == ExportPeriod.Full }?.let { " · ${nw(it)}" } ?: ""
        w("<header class=\"bar\">\n")
        w(
            "<button class=\"iconbtn js-only\" id=\"menuBtn\" type=\"button\" aria-expanded=\"false\" aria-controls=\"drawer\" " +
                "aria-label=\"${esc(strings.menu)}\">$ICON_MENU</button>\n",
        )
        w("<div><h1>${esc(strings.title)}</h1>")
        w("<p class=\"period\">${nw(periodLabel())}$span · ${esc(exported)}</p>")
        w("<p class=\"where js-only\" id=\"where\" aria-hidden=\"true\"></p></div>\n</header>\n")
    }

    private fun searchBox() {
        w("<search class=\"searchwrap js-only\" id=\"searchwrap\">\n")
        w("<label for=\"q\">${esc(strings.searchLabel)}</label>\n<div class=\"search\">$ICON_SEARCH")
        w("<input id=\"q\" type=\"search\" placeholder=\"${esc(strings.searchPlaceholder)}\" aria-describedby=\"found\" autocomplete=\"off\">")
        w("<button class=\"iconbtn\" id=\"clearQ\" type=\"button\" aria-label=\"${esc(strings.clearSearch)}\" hidden>$ICON_CLOSE</button>")
        w("</div>\n<div class=\"found\" id=\"found\" role=\"status\" aria-live=\"polite\"></div>\n</search>\n")
    }

    private fun sectionStart(id: String, title: String, active: Boolean = false, count: String? = null) {
        val cls = if (active) " class=\"active\"" else ""
        w("<section id=\"s-$id\"$cls aria-labelledby=\"h-$id\" data-title=\"${esc(title)}\">\n")
        w("<h2 id=\"h-$id\" tabindex=\"-1\">${esc(title)}${count ?: ""}</h2>\n")
    }

    private fun countSpan(id: String, n: Int) = " <span class=\"count\" id=\"$id\">${wholeNumber(n.toLong(), strings.language)}</span>"

    // ---------- Yhteenveto ----------

    private fun summary() {
        sectionStart("summary", strings.summary, active = true)
        val total = model.total
        val workDays = total?.workDays ?: 0
        val workSec = total?.workSec ?: 0
        w("<div class=\"card\">\n<dl class=\"stats\">")
        stat(wholeNumber(workDays.toLong(), strings.language), strings.workDay.of(workDays))
        stat(nw(if (workSec > 0) format.duration(workSec) else "–"), strings.workTime, escaped = true)
        stat(nw(format.km(total?.km ?: 0)), strings.kilometres, escaped = true)
        stat(wholeNumber(available.size.toLong(), strings.language), strings.photo.of(available.size))
        w("</dl>\n")
        if (model.cars.isNotEmpty()) {
            w("<ul class=\"plain\">\n")
            model.cars.forEach(::carLine)
            w("</ul>\n")
        }
        w("</div>\n<nav class=\"jump\" aria-label=\"${esc(strings.sections)}\"><ul class=\"plain\">\n")
        sectionLinks().drop(1).forEach { w(it) }
        w("</ul></nav>\n</section>\n")
    }

    private fun stat(value: String, label: String, escaped: Boolean = false) {
        w("<div class=\"stat\"><dt>${esc(label)}</dt><dd>${if (escaped) value else esc(value)}</dd></div>")
    }

    private fun carLine(car: ReportCar) {
        val consumption = car.avgConsumption?.let { format.consumption(it, car.kind) } ?: "–"
        val costPerKm = car.costPerKm?.let { cost -> car.currency?.let { format.costPerKm(cost, it) } }
        val word = strings.joinList(strings.entryCounts(mapOf(fillGroup(car.kind) to car.count)))
        w("<li class=\"carline\"><div class=\"row\"><span><b class=\"nw\">${esc(car.plate)}</b> · ${esc(strings.kindName(car.kind))}</span>")
        w("<b class=\"nw\">${esc(consumption)}</b></div>\n")
        w("<div class=\"row sub\"><span>${esc(word)} · ${nw(format.amount(car.amount, car.kind))} · ${esc(format.moneyByCurrency(car.costs))}</span>")
        if (costPerKm != null) w("<span class=\"nw\">${esc(costPerKm)}</span>")
        w("</div></li>\n")
    }

    /** Osioiden linkit valikkoon ja Yhteenvedon loppuun; määrät päiville, kirjauksille ja kuville. */
    private fun sectionLinks(): List<String> = listOf(
        Triple("summary", strings.summary, null),
        Triple("days", strings.days, model.days.size),
        Triple("periods", strings.periods, null),
        Triple("fills", entriesTitle, model.entries.size),
        Triple("photos", strings.photos, available.size),
        Triple("explain", strings.explain, null),
        Triple("backup", strings.backup, null),
    ).map { (id, title, n) ->
        val count = n?.let { "<span class=\"n\">${wholeNumber(it.toLong(), strings.language)}</span>" } ?: ""
        "<li><a href=\"#s-$id\"><span class=\"l\">${esc(title)}</span>$count</a></li>\n"
    }

    // ---------- Päivät ----------

    private fun days() {
        sectionStart("days", strings.days, count = countSpan("daysCount", model.days.size))
        if (model.days.isEmpty()) {
            w("<p class=\"note\">${esc(strings.noDays)}</p>\n")
        } else {
            w("<p class=\"note\">${esc(strings.daysNote)}</p>\n<ul class=\"plain\" id=\"dayList\">\n")
            model.days.forEachIndexed(::dayCard)
            w("</ul>\n")
            w("<button class=\"more js-only\" id=\"moreDays\" type=\"button\" hidden>${esc(strings.moreDays)}</button>\n")
        }
        w("<p class=\"note js-only\" id=\"daysNoHits\" hidden>${esc(strings.noHits)}</p>\n</section>\n")
    }

    private fun dayCard(index: Int, day: WorkHistoryDay) {
        val totals = day.totals
        val plates = day.segments.mapNotNull { it.licensePlate }.distinct()
        // Kaikki päivän autot: kirjauksen auto näkyy, kun päivällä on useampi auto tai ei työjaksoa.
        val cars = (plates + day.fillups.map { it.licensePlate }).distinct()
        val showFillPlate = day.segments.isEmpty() || cars.size > 1
        val backdated = day.segments.isNotEmpty() && day.segments.all { it.startEpochSec == it.endEpochSec }
        val notes = buildList {
            if (totals == null) add(strings.noWorkEntry to false)
            else if (totals.endEpochSec == null) add(strings.inProgress to false)
            if (totals?.missingReadings == true) add(strings.missingReadings to true)
            if (cars.size > 1) add(strings.count(cars.size, strings.car) to false)
            if (day.segments.any { it.startEpochSec == it.endEpochSec }) add(strings.backdated to false)
        }
        val search = listOf(format.date(day.date), numericDates(day.date)) +
            plates.flatMap { plate -> listOfNotNull(plate, model.carKinds[plate]?.let(strings::kindName)) } +
            notes.map { it.first } +
            listOfNotNull(day.note.takeIf { it.isNotBlank() }) +
            day.fillups.flatMap { fillupWords(it) }
        w("<li class=\"card day\" id=\"d-$index\" tabindex=\"-1\" data-search=\"${searchText(search)}\">\n")
        w("<div class=\"row\"><h3>${nw(format.date(day.date))}</h3>")
        if (totals != null && totals.endEpochSec != null) w("<span class=\"km\">${esc(format.km(totals.totalKm))}</span>")
        w("</div>\n")
        if (totals != null && !backdated) {
            val end = totals.endEpochSec
            val clock = if (end != null) format.clockRange(totals.startEpochSec, end) else format.clockFrom(totals.startEpochSec)
            w("<div class=\"row sub\"><span class=\"nw\">${esc(clock)}</span>")
            totals.durationSec?.let { w("<span>${esc(strings.workTime)} ${nw(format.duration(it))}</span>") }
            w("</div>\n")
        }
        day.segments.forEach { segment ->
            val plate = segment.licensePlate
            val car = if (plate == null) "–" else nw(plate) + (model.carKinds[plate]?.let { " · ${esc(strings.kindName(it))}" } ?: "")
            val start = segment.odometerStartKm
            val end = segment.odometerEndKm
            val reading = when {
                start != null && end != null -> format.odometerRange(start, end)
                start != null -> "${format.km(start)}$NB–"
                else -> null
            }
            w("<div class=\"row\"><span>$car</span>")
            if (reading != null) w("<span class=\"sub\">${esc(strings.odometer)} ${esc(reading)}</span>")
            w("</div>\n")
        }
        // Päivän lisätiedot kerran jaksorivien jälkeen; rivinvaihdot säilyvät (pre-wrap).
        w(memo(day.note))
        if (notes.isNotEmpty()) {
            w("<p class=\"tags\">")
            notes.forEach { (text, warn) -> w("<span class=\"tag${if (warn) " warn" else ""}\">${esc(text)}</span> ") }
            w("</p>\n")
        }
        day.fillups.sortedByDescending { it.epochSec }.forEach { fillup ->
            w("<div class=\"fill\"><div class=\"what\">${fillWhat(fillup, day.fillups)}")
            if (showFillPlate) w(" · ${nw(fillup.licensePlate)}")
            if (!fillup.fullTank) w(" · <span class=\"tag\">${esc(strings.partial)}</span>")
            w("</div>${photoButton(fillup)}</div>\n")
            w(memo(fillup.note))
        }
        w("</li>\n")
    }

    /** "Tankattu 31,2 l · 75,45 €"; laji eteen, jos ryhmässä on useampi laji tai kyse on AdBluesta. */
    private fun fillWhat(fillup: FuelFillupEntity, sameDay: List<FuelFillupEntity>): String {
        val group = fillGroup(fillup.kind)
        val did = strings.did(group)
        val prefix = if (needsKindName(fillup.kind, sameDay.map { it.kind })) {
            "${esc(strings.kindName(fillup.kind))} · ${esc(did.replaceFirstChar { it.lowercaseChar() })}"
        } else {
            esc(did)
        }
        val money = format.money(fillup.totalCost, fillup.currency).takeIf { fillup.totalCost > 0 }
        return "$prefix <b class=\"nw\">${esc(format.amount(fillup.liters, fillup.kind))}</b>" + (money?.let { " · ${nw(it)}" } ?: "")
    }

    private fun needsKindName(kind: FuelKind, kinds: Collection<FuelKind>): Boolean =
        kind == FuelKind.ADBLUE || kinds.filter { fillGroup(it) == fillGroup(kind) }.distinct().size > 1

    private fun fillupWords(fillup: FuelFillupEntity): List<String> {
        val group = fillGroup(fillup.kind)
        return listOfNotNull(
            strings.did(group),
            strings.entryWord(group, 1),
            strings.kindName(fillup.kind),
            fillup.licensePlate,
            strings.partial.takeIf { !fillup.fullTank },
            fillup.note.takeIf { it.isNotBlank() },
        )
    }

    /** Lisätiedot-kappale; tyhjälle tekstille ei mitään. Rivinvaihdot säilyvät tyylin pre-wrap-asetuksella. */
    private fun memo(text: String): String = if (text.isBlank()) "" else "<p class=\"memo\">${esc(text)}</p>\n"

    private fun photoButton(fillup: FuelFillupEntity): String {
        val list = photosByFillup[fillup.id].orEmpty()
        if (list.isEmpty()) return ""
        val visible = strings.count(list.size, strings.photo)
        val label = "$visible: ${format.date(fillupDay(fillup))}, ${fillup.licensePlate}, ${strings.entryWord(fillGroup(fillup.kind), 1)}"
        return "<button class=\"photo-btn js-only\" type=\"button\" data-entry=\"${entryIndex[fillup.id]}\" " +
            "aria-label=\"${esc(label)}\">$ICON_CAMERA${esc(visible)}</button>"
    }

    // ---------- Viikot, kuukaudet ja vuodet ----------

    private fun periods() {
        sectionStart("periods", strings.periods)
        val panels = listOf(
            Triple("weeks", strings.weeks, model.weeks),
            Triple("months", strings.months, model.months),
            Triple("years", strings.years, model.years),
        )
        w("<div class=\"js-only\" role=\"tablist\" id=\"periodTabs\" aria-label=\"${esc(strings.periodLength)}\">\n")
        panels.forEachIndexed { i, (id, title, _) ->
            val selected = i == 0
            w("<button role=\"tab\" type=\"button\" id=\"tab-$id\" aria-selected=\"$selected\" aria-controls=\"p-$id\"")
            w("${if (selected) "" else " tabindex=\"-1\""}>${esc(title)}</button>\n")
        }
        w("</div>\n")
        panels.forEach { (id, title, groups) ->
            w("<div class=\"panel\" id=\"p-$id\"><h3 class=\"ptitle\">${esc(title)}</h3>\n")
            if (groups.isEmpty()) {
                w("<p class=\"note\">${esc(strings.noDays)}</p>\n")
            } else {
                w("<ul class=\"plain\">\n")
                groups.forEach { periodCard(id, it) }
                w("</ul>\n")
            }
            w("</div>\n")
        }
        w("</section>\n")
    }

    private fun periodCard(kind: String, group: ReportPeriodGroup) {
        val first = LocalDate.ofEpochDay(group.fromDay)
        val title = when (kind) {
            "weeks" -> format.weekTitle(group.isoWeek ?: 0, group.fromDay, group.toDay)
            "months" -> format.monthTitle(first.year, first.monthValue)
            else -> first.year.toString()
        }
        w("<li class=\"card period\"><div class=\"row\"><h4>${esc(title)}</h4><span class=\"km\">${esc(format.km(group.km))}</span></div>\n")
        w("<div class=\"row sub\"><span>${esc(strings.count(group.workDays, strings.workDay))}</span>")
        if (group.workSec > 0) w("<span>${esc(strings.workTime)} ${nw(format.duration(group.workSec))}</span>")
        w("</div>\n")
        val kinds = group.fuels.byKind.map { it.kind }
        group.fuels.byKind.forEach { fuelLine(it, kinds) }
        w("</li>\n")
    }

    private fun fuelLine(fuel: PeriodFuel, kinds: List<FuelKind>) {
        val group = fillGroup(fuel.kind)
        val did = strings.did(group)
        val prefix = if (needsKindName(fuel.kind, kinds)) {
            "${esc(strings.kindName(fuel.kind))} · ${esc(did.replaceFirstChar { it.lowercaseChar() })}"
        } else {
            esc(did)
        }
        val money = format.moneyByCurrency(fuel.costs)
        w("<div class=\"carline row\"><span>$prefix <b class=\"nw\">${esc(format.amount(fuel.liters, fuel.kind))}</b> · ${esc(money)}</span>")
        val consumption = fuel.consumption?.let { format.consumption(it, fuel.kind) }
        // AdBluen hinta kilometriltä on häviävän pieni eikä vertailukelpoinen polttoaineen kanssa.
        val costPerKm = fuel.costPerKm?.takeIf { fuel.kind != FuelKind.ADBLUE }?.let { cost -> fuel.currency?.let { format.costPerKm(cost, it) } }
        val right = listOfNotNull(consumption?.let { "<b>${esc(it)}</b>" }, costPerKm?.let(::esc))
        if (right.isNotEmpty()) w("<span class=\"nw\">${right.joinToString(" · ")}</span>")
        w("</div>\n")
    }

    // ---------- Kirjaukset ----------

    private fun entries() {
        sectionStart("fills", entriesTitle, count = countSpan("fillsCount", model.entries.size))
        if (model.entries.isEmpty()) {
            w("<p class=\"note\">${esc(strings.noEntries)}</p>\n")
        } else {
            w("<ul class=\"plain cards-when-narrow\" id=\"fillList\">\n")
            model.entries.forEachIndexed(::entryCard)
            // Vierityssäiliön nimi eroaa osion nimestä, jotta maamerkit ovat yksilöllisiä.
            w("</ul>\n<div class=\"table-scroll\" role=\"region\" tabindex=\"0\" aria-label=\"${esc("$entriesTitle: ${strings.table}")}\">")
            w("<table class=\"wide\" id=\"fillTable\"><caption class=\"vh\">${esc(entriesTitle)}</caption><thead><tr>")
            strings.tableColumns.forEachIndexed { i, column ->
                w("<th scope=\"col\"${if (i in 3..7) " class=\"n\"" else ""}>${esc(column)}</th>")
            }
            w("</tr></thead><tbody>\n")
            model.entries.forEachIndexed(::entryRow)
            w("</tbody></table></div>\n")
        }
        w("<p class=\"note js-only\" id=\"fillsNoHits\" hidden>${esc(strings.noHits)}</p>\n</section>\n")
    }

    private fun entrySearch(entry: ReportEntry): String {
        val day = fillupDay(entry.fillup)
        return searchText(listOf(format.date(day), numericDates(day)) + fillupWords(entry.fillup))
    }

    private fun entryCard(index: Int, entry: ReportEntry) {
        val f = entry.fillup
        val day = fillupDay(f)
        w("<li class=\"card entry\" id=\"e-$index\" tabindex=\"-1\" data-search=\"${entrySearch(entry)}\">\n")
        w("<div class=\"row\"><h3>${nw(format.date(day))}</h3><span class=\"sub\">${nw(f.licensePlate)} · ${esc(strings.kindName(f.kind))}</span></div>\n")
        w("<div class=\"fill first\"><div class=\"what\"><b class=\"nw\">${esc(format.amount(f.liters, f.kind))}</b>")
        if (f.totalCost > 0) w(" · ${nw(format.money(f.totalCost, f.currency))}")
        if (f.pricePerLiter > 0) w(" · ${nw(format.unitPrice(f.pricePerLiter, f.currency, f.kind))}")
        val details = listOfNotNull(
            "${esc(strings.odometer)} ${nw(format.km(f.odometerKm))}",
            esc(strings.partial).takeIf { !f.fullTank },
            entry.interval?.let { "${esc(strings.consumption)} ${nw(format.consumption(it.consumption, it.kind))}" },
        )
        w("\n<div class=\"sub\">${details.joinToString(" · ")}</div></div>${photoButton(f)}</div>\n")
        w(memo(f.note))
        w("</li>\n")
    }

    private fun entryRow(index: Int, entry: ReportEntry) {
        val f = entry.fillup
        val kind = esc(strings.kindName(f.kind)) + if (f.fullTank) "" else " (${esc(strings.partial)})"
        w("<tr id=\"r-$index\" tabindex=\"-1\" data-search=\"${entrySearch(entry)}\">")
        w("<th scope=\"row\" class=\"nw\">${esc(format.date(fillupDay(f)))}</th><td class=\"nw\">${esc(f.licensePlate)}</td><td>$kind</td>")
        w("<td class=\"n\">${esc(format.km(f.odometerKm))}</td><td class=\"n\">${esc(format.amount(f.liters, f.kind))}</td>")
        w("<td class=\"n\">${esc(format.money(f.totalCost, f.currency))}</td>")
        w("<td class=\"n\">${esc(format.unitPrice(f.pricePerLiter, f.currency, f.kind))}</td>")
        w("<td class=\"n\">${entry.interval?.let { esc(format.consumption(it.consumption, it.kind)) } ?: ""}</td>")
        w("<td class=\"memo\">${esc(f.note)}</td>")
        w("<td>${photoButton(f)}</td></tr>\n")
    }

    // ---------- Kuvat ----------

    private fun gallery() {
        sectionStart("photos", strings.photos, count = countSpan("photosCount", available.size))
        if (available.isEmpty()) {
            w("<p class=\"note\">${esc(strings.noPhotos)}</p>\n</section>\n")
            return
        }
        w("<p class=\"note\">${esc(strings.photosNote)}</p>\n<ul class=\"gallery\">\n")
        available.forEach { photo ->
            val f = photo.fillup
            val inEntry = photosByFillup.getValue(f.id)
            val position = inEntry.indexOf(photo)
            val day = fillupDay(f)
            val amount = format.amount(f.liters, f.kind)
            val alt = "${strings.photoAlt(fillGroup(f.kind))} ${position + 1}/${inEntry.size}: ${format.date(day)}, ${f.licensePlate}, $amount"
            val money = format.money(f.totalCost, f.currency).takeIf { f.totalCost > 0 }
            val cap = listOfNotNull(format.date(day), f.licensePlate, "${strings.kindName(f.kind)} $amount", money).joinToString(" · ")
            w("<li><button class=\"thumb\" type=\"button\" data-photo=\"${esc(photo.photo.backupId)}\" data-entry=\"${entryIndex[f.id]}\" ")
            w("data-alt=\"${esc(alt)}\" data-cap=\"${esc(cap)}\">")
            val thumbnail = runCatching { photos.thumbnail(photo.photo) }.getOrNull()
            if (thumbnail != null) {
                // Pienoiskuva omalla rivillään: tuonti ohittaa rivin ([backupLine]), ettei se jää muistiin.
                w("\n$THUMB_LINE alt=\"\" src=\"data:image/jpeg;base64,${Base64.getEncoder().encodeToString(thumbnail)}\">\n")
            } else {
                w("<span class=\"tn ph\" role=\"img\" aria-label=\"${esc(strings.noPreview)}\">$ICON_IMAGE</span>")
            }
            w("<span class=\"t\"><span class=\"vh\">${esc(strings.photoOf(position + 1, inEntry.size))}: </span>")
            w("${nw(format.shortDate(day))}<br><span class=\"plate\">${esc(f.licensePlate)}</span> · ")
            w("${esc(strings.kindName(f.kind))} ${nw(amount)}</span></button></li>\n")
        }
        w("</ul>\n</section>\n")
    }

    // ---------- Selitteet ja varmuuskopio ----------

    private fun explain() {
        sectionStart("explain", strings.explain)
        w("<div class=\"card explain\"><h3>${esc(strings.explainTitle)}</h3>\n")
        strings.explanations.forEach { w("<p>${esc(it)}</p>\n") }
        w("</div>\n</section>\n")
    }

    private fun backup() {
        sectionStart("backup", strings.backup)
        val range = model.period.bounds()?.let { (from, to) -> format.dayRange(from.toEpochDay(), to.toEpochDay()) }
        w("<div class=\"card\"><p>${esc(strings.backupText(range))}</p></div>\n</section>\n")
    }

    // ---------- Valikko, kuvanäkymä ja skriptin tekstit ----------

    private fun drawer() {
        w("<div class=\"scrim js-only\" id=\"scrim\"></div>\n")
        w("<nav class=\"drawer js-only\" id=\"drawer\" aria-label=\"${esc("${strings.sections} (${strings.menu.lowercase(Locale.ROOT)})")}\" inert>\n")
        w("<div class=\"head\"><div><b>${esc(strings.title)}</b><span class=\"sub\">${nw(periodLabel())}</span></div>")
        w("<button class=\"iconbtn\" id=\"drawerClose\" type=\"button\" aria-label=\"${esc(strings.closeMenu)}\">$ICON_CLOSE</button></div>\n")
        w("<ul>\n")
        sectionLinks().forEach { w(it) }
        w("</ul>\n</nav>\n")
    }

    private fun lightbox() {
        w("<div class=\"lightbox js-only\" id=\"lightbox\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"lbTitle\" aria-describedby=\"lbCap\" hidden>\n")
        w("<div class=\"lb-top\"><button class=\"iconbtn\" id=\"lbClose\" type=\"button\" aria-label=\"${esc(strings.closePhoto)}\">$ICON_CLOSE</button>")
        w("<div class=\"cap\"><div class=\"lb-title\" id=\"lbTitle\"></div><div id=\"lbCap\"></div></div></div>\n")
        w("<div class=\"lb-img\"><img id=\"lbImg\" alt=\"\">")
        w("<button class=\"iconbtn lb-nav prev\" id=\"lbPrev\" type=\"button\" aria-label=\"${esc(strings.previousPhoto)}\">$ICON_PREV</button>")
        w("<button class=\"iconbtn lb-nav next\" id=\"lbNext\" type=\"button\" aria-label=\"${esc(strings.nextPhoto)}\">$ICON_NEXT</button></div>\n")
        w("<div class=\"lb-bottom\"><button id=\"lbGo\" type=\"button\">${esc(strings.goToEntry)}</button></div>\n")
        w("<p class=\"vh\" id=\"lbStatus\" aria-live=\"polite\"></p>\n</div>\n")
    }

    /** Skriptin tilailmoitusten mallit; samat tekstit kuin Kotlin-puolella. */
    private fun scriptTexts() {
        val texts = buildJsonObject {
            put("hits", strings.hitsTemplate)
            put("shown", strings.shownTemplate)
            put("photoOf", strings.photoOfTemplate)
            putJsonArray("day") { add(strings.day.one); add(strings.day.many) }
            putJsonArray("entry") { add(strings.entry.one); add(strings.entry.many) }
        }
        w("<script type=\"application/json\" id=\"report-text\">${texts.toString().replace("<", LT_ESCAPE)}</script>\n")
    }

    /**
     * Täysikokoiset kuvat piilotetussa säiliössä (template: selain ei lataa niitä ennen kuvanäkymää). Jokainen kuva on
     * omalla rivillään. Kuva avataan ennen rivin kirjoitusta, joten kesken viennin poistettu kuva jää kokonaan pois.
     */
    private fun fullPhotos() {
        w("<template id=\"full-photos\">")
        available.forEach { photo ->
            val input = openPhoto(photo) ?: return@forEach
            input.use {
                val f = photo.fillup
                val alt = "${strings.photoOf(photo.index + 1, photo.count)}: ${format.date(fillupDay(f))}, ${f.licensePlate}"
                w("\n$PHOTO_LINE${esc(photo.photo.backupId)}\" alt=\"${esc(alt)}\" $PHOTO_DATA")
                writer.flush()
                out.writeBase64(it)
                w("\">")
            }
        }
        w("\n</template>")
    }

    // ---------- Apufunktiot ----------

    private fun openPhoto(photo: ReportPhoto): InputStream? = runCatching { photos.open(photo.photo) }.getOrNull()

    private fun fillupDay(fillup: FuelFillupEntity): Long =
        Instant.ofEpochSecond(fillup.epochSec).atZone(format.zone).toLocalDate().toEpochDay()

    private fun periodLabel(): String = when (val period = model.period) {
        is ExportPeriod.Month -> format.monthTitle(period.month.year, period.month.monthValue)
        is ExportPeriod.Year -> period.year.toString()
        is ExportPeriod.Range -> format.dayRange(period.from.toEpochDay(), period.to.toEpochDay())
        ExportPeriod.Full -> strings.fullBackup
    }

    private fun dataSpan(): String? =
        model.days.takeIf { it.isNotEmpty() }?.let { days -> format.dayRange(days.minOf { it.date }, days.maxOf { it.date }) }

    /** Numeromuotoiset päivät hakuun kaikilla kielillä: "16.10.2026 2026-10-16". */
    private fun numericDates(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.dayOfMonth}.${date.monthValue}.${date.year} $date"
    }

    private fun searchText(parts: List<String>): String =
        esc(parts.joinToString(" ").replace(NB, ' ').replace('\n', ' ').lowercase(Locale.ROOT))

    private fun nw(text: String) = "<span class=\"nw\">${esc(text)}</span>"

    /** Avattu tiedosto base64-muodossa suoraan virtaan; virta jää auki. */
    private fun OutputStream.writeBase64(input: InputStream) {
        val target = this
        val keepOpen = object : FilterOutputStream(target) {
            override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
            override fun close() = target.flush()
        }
        Base64.getEncoder().wrap(keepOpen).use { encoder -> input.copyTo(encoder) }
    }
}

private fun esc(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

/** JSONin kulmasulje koodattuna, ettei lohko pääty kesken. */
private val LT_ESCAPE = "\\" + "u003c"

private val resources = mutableMapOf<String, String>()

/** Tyylit ja skripti luokkapolusta; luetaan kerran. */
private fun resource(name: String): String = synchronized(resources) {
    resources.getOrPut(name) {
        val stream = requireNotNull(ReportPhotos::class.java.getResourceAsStream("/fi/tyomatka/report/$name")) { name }
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

private const val SVG = "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\" aria-hidden=\"true\" focusable=\"false\"><path d=\""
private const val ICON_MENU = "${SVG}M3 6h18v2H3zm0 5h18v2H3zm0 5h18v2H3z\"/></svg>"
private const val ICON_CLOSE =
    "${SVG}M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z\"/></svg>"
private const val ICON_SEARCH = "${SVG}M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 " +
    "4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14\"/></svg>"
private const val ICON_CAMERA = "${SVG}M12 15.2A3.2 3.2 0 1 0 12 8.8a3.2 3.2 0 0 0 0 6.4M9 2 7.17 4H4a2 2 0 0 0-2 2v12c0 " +
    "1.1.9 2 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-3.17L15 2z\"/></svg>"
private const val ICON_PREV = "${SVG}M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z\"/></svg>"
private const val ICON_NEXT = "${SVG}M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z\"/></svg>"
private const val ICON_IMAGE = "${SVG}M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2M8.5 13.5l2.5 " +
    "3 3.5-4.5 4.5 6H5z\"/></svg>"
