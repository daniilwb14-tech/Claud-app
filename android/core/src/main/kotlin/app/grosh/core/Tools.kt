package app.grosh.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * QR-код с кассового чека (ФНС): «t=20240115T1405&s=1234.56&fn=…&i=…&fp=…&n=1».
 * Сумма и дата зашиты прямо в код, интернет не нужен.
 */
object ReceiptQrParser {
    fun parse(payload: String, zone: ZoneId = ZoneId.systemDefault()): ParsedTxn? {
        val fields = payload.trim().split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0].lowercase() to kv[1] else null
        }.toMap()
        val amount = fields["s"]?.let(AmountParser::parseMinor)?.takeIf { it > 0 } ?: return null
        if (fields["fn"] == null) return null
        val date = fields["t"]?.let { t ->
            try {
                val pattern = if (t.length >= 15) "yyyyMMdd'T'HHmmss" else "yyyyMMdd'T'HHmm"
                LocalDateTime.parse(t, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        } ?: System.currentTimeMillis()
        // n=2 — чек возврата прихода.
        return ParsedTxn(amountMinor = amount, dateMillis = date, merchant = "", isIncome = fields["n"] == "2")
    }
}

/** Убирает операции, которые уже есть в базе (покупка пришла и пушем, и SMS, и в выписке). */
object Deduplicator {
    data class Existing(val amountMinor: Long, val isIncome: Boolean, val dateMillis: Long)

    private fun day(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Для выписок: совпадение по дню, сумме и типу. Каждая существующая запись «гасит» не более одной
     * входящей, поэтому две одинаковые покупки за день внутри одной выписки не схлопываются.
     */
    fun filterNewByDay(incoming: List<ParsedTxn>, existing: List<Existing>, zone: ZoneId = ZoneId.systemDefault()): List<ParsedTxn> {
        val counts = HashMap<Triple<Long, Long, Boolean>, Int>()
        for (e in existing) counts.merge(Triple(day(e.dateMillis, zone), e.amountMinor, e.isIncome), 1, Int::plus)
        return incoming.filter { txn ->
            val key = Triple(day(txn.dateMillis, zone), txn.amountMinor, txn.isIncome)
            val left = counts[key] ?: 0
            if (left > 0) {
                counts[key] = left - 1
                false
            } else {
                true
            }
        }
    }

    /** Для пушей и SMS: та же сумма в пределах нескольких минут — это одно и то же событие. */
    fun isNearDuplicate(txn: ParsedTxn, existing: List<Existing>, windowMillis: Long = 5 * 60_000L): Boolean =
        existing.any { it.amountMinor == txn.amountMinor && it.isIncome == txn.isIncome && abs(it.dateMillis - txn.dateMillis) <= windowMillis }
}

/** Находит регулярные списания (подписки) по истории трат. */
object SubscriptionDetector {
    data class Charge(val merchant: String, val amountMinor: Long, val dateMillis: Long)

    data class Subscription(
        val key: String,
        val merchant: String,
        val amountMinor: Long,
        val periodDays: Int,
        val lastDateMillis: Long,
        val nextDateMillis: Long,
        val chargeCount: Int,
    ) {
        val monthlyCostMinor: Long get() = amountMinor * 30 / periodDays
        val periodTitle: String
            get() = when (periodDays) {
                7 -> "раз в неделю"
                365 -> "раз в год"
                else -> "раз в месяц"
            }
    }

    private val periods = listOf(7 to 6..8, 30 to 27..33, 365 to 355..375)

    fun detect(charges: List<Charge>, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Subscription> {
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val found = mutableListOf<Subscription>()

        for ((key, group) in charges.groupBy { key(it.merchant) }) {
            if (key.isEmpty()) continue
            val sorted = group.sortedBy { it.dateMillis }
            val knownService = MerchantClassifier.category(sorted[0].merchant) == SpendCategory.SUBSCRIPTIONS
            if (sorted.size < (if (knownService) 2 else 3)) continue
            val last = sorted.last()

            // Суммы должны быть близки (цена подписки меняется редко).
            val amounts = sorted.map { it.amountMinor }.sorted()
            val median = amounts[amounts.size / 2].toDouble()
            if (median <= 0 || amounts.any { abs(it - median) / median > 0.15 }) continue

            val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(day(a.dateMillis), day(b.dateMillis)).toInt() }
            val period = periods.firstOrNull { (_, range) -> gaps.all { it in range } } ?: continue

            // Давно не списывалось — подписку, видимо, отменили.
            val sinceLast = ChronoUnit.DAYS.between(day(last.dateMillis), day(nowMillis))
            if (sinceLast > period.first * 1.6) continue

            found += Subscription(
                key = key, merchant = last.merchant, amountMinor = last.amountMinor, periodDays = period.first,
                lastDateMillis = last.dateMillis, nextDateMillis = last.dateMillis + period.first * 86_400_000L,
                chargeCount = sorted.size,
            )
        }
        return found.sortedByDescending { it.monthlyCostMinor }
    }

    /** Название без цифр и мусора: «YANDEX*PLUS 4411» и «YANDEX PLUS» — один продавец. */
    fun key(merchant: String): String =
        MerchantClassifier.normalize(merchant).split(' ').filter { t -> t.isNotEmpty() && !t.all { it.isDigit() } }.joinToString(" ")
}

object Insights {
    /** Сколько ещё можно потратить сегодня, чтобы уложиться в бюджет месяца (в копейках). */
    fun safeToSpendToday(budgetMinor: Long, spentBeforeTodayMinor: Long, spentTodayMinor: Long, daysLeftIncludingToday: Int): Long {
        if (daysLeftIncludingToday <= 0) return 0
        val perDay = (budgetMinor - spentBeforeTodayMinor) / daysLeftIncludingToday
        return maxOf(0, perDay - spentTodayMinor)
    }

    /** «Цена в часах работы»: сколько часов нужно отработать ради этой покупки. */
    fun workHours(amountMinor: Long, hourlyRateMinor: Long): Double? =
        if (hourlyRateMinor > 0 && amountMinor > 0) amountMinor.toDouble() / hourlyRateMinor else null

    fun workHoursText(amountMinor: Long, hourlyRateMinor: Long): String? {
        val hours = workHours(amountMinor, hourlyRateMinor) ?: return null
        return when {
            hours < 1 -> "${maxOf(1, (hours * 60).roundToInt())} мин работы"
            hours < 10 -> String.format("%.1f", hours).replace('.', ',') + " ч работы"
            else -> "${hours.roundToInt()} ч работы"
        }
    }
}
