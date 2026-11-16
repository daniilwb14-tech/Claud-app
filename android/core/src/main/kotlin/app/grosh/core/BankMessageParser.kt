package app.grosh.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId

/** Разбор денежных сумм в любом написании: «1 234,56», «1,234.56», «-349,50», «₽1 500», «+50 000.00 ₽». */
object AmountParser {

    /** Сумма в копейках со знаком; `null`, если в тексте нет числа. */
    fun parseMinor(text: String): Long? {
        var negative = false
        val body = StringBuilder()
        for (ch in text) {
            when {
                ch in '0'..'9' -> body.append(ch)
                ch == '.' || ch == ',' -> body.append(ch)
                (ch == '-' || ch == '−' || ch == '–') && body.isEmpty() -> negative = true
            }
        }
        val trimmed = body.trim('.', ',').toString()
        if (trimmed.isEmpty()) return null
        val value = normalized(trimmed) ?: return null
        val minor = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        return if (negative) -minor else minor
    }

    private fun normalized(raw: String): BigDecimal? {
        var s = raw
        val hasDot = '.' in s
        val hasComma = ',' in s
        if (hasDot && hasComma) {
            // Десятичным считается тот разделитель, который стоит последним.
            s = if (s.lastIndexOf('.') > s.lastIndexOf(',')) s.replace(",", "") else s.replace(".", "").replace(',', '.')
        } else if (hasComma) {
            val parts = s.split(',')
            s = if (parts.size == 2 && parts[1].length != 3) parts[0] + "." + parts[1] else s.replace(",", "")
        } else if (hasDot) {
            if (s.split('.').size > 2) s = s.replace(".", "")
        }
        return s.toBigDecimalOrNull()
    }
}

/**
 * Разбирает текст SMS/пуша банка («Покупка 349р PYATEROCHKA Баланс: 12 345р») в операцию.
 * Работает со Сбером, Т-Банком, Альфой, ВТБ, Mir Pay и большинством похожих форматов.
 */
object BankMessageParser {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private const val NUMBER = """(?<![\d.,:*])(?:\d{1,3}(?:[ \u00A0\u202F,]\d{3})+|\d+)(?:[.,]\d{1,2})?"""
    private const val CURRENCY = """₽|руб(?:лей|ля|\.)?|р\.?|rub|rur|usd|eur|kzt|byn|\$|€|₸|£"""

    /** «349р», «1 500 RUB», «1 234,56 ₽» */
    private val suffixAmount = rx("($NUMBER)\\s?($CURRENCY)(?![a-zа-яё])")

    /** «$12.34», «₽1 500» */
    private val prefixAmount = rx("""(\$|€|₽|£|₸)\s?($NUMBER)""")

    private val balance = rx("""баланс|доступн|остаток|balance|available|avail\b""")
    private val ignore = rx(
        """никому не сообщайте|код подтверждения|одноразовый|verification code|\botp\b|отказ|отклонен|недостаточно средств|declined|insufficient""",
    )
    private val income = rx("""зачислен|пополнен|поступлен|возврат|входящий перевод|перевод от|получен|зарплат|refund|received|deposit""")
    private val operation = rx(
        """покупк|оплат|списан|зачислен|пополнен|поступлен|возврат|перевод|плат[её]ж|снятие|выдача|получен|зарплат|purchase|payment|paid|refund|received|withdraw""",
    )
    private val promo = rx("""скидк|акци|кэшб[эе]к до|кешб[эе]к до|получите|оформите|одобрен|предложени|до \d+\s?%|выгод|бонус|промокод|розыгрыш""")
    private val card = rx(
        """(?:карт[аеыу]|card|сч[её]т|ecmc|mir|мир|visa|mastercard|maestro)\s?[-–№#]?\s?[*•·]{0,4}(\d{4})(?!\d)|[*•·]{1,4}\s?(\d{4})(?!\d)""",
    )
    private val time = rx("""(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)""")
    private val dateLike = rx("""(?<!\d)\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?(?!\d)""")
    private val fullDate = rx("""(?<!\d)(\d{2})\.(\d{2})\.(\d{4}|\d{2})(?!\d)""")
    private val clock = rx("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""")
    private val leadingWord = rx("""^(?:в|at|in|от|from|магазин|to|на)\s+""")
    private val trailingWord = rx("""\s+(?:on|в|at)$""")
    private val sentenceEnd = rx("""[.;]\s""")
    private val operationWords = rx(
        """покупка|оплата|списание|списано|зачисление|зачислено|перевод|пополнение|платеж|платёж|purchase|payment|you paid|paid|успешно|выполнен[оа]?""",
    )
    private val trimChars = charArrayOf(' ', '.', ',', ';', ':', '-', '–', '—', '*', '\u00A0')

    private fun currencyCode(token: String) = when (token.lowercase()) {
        "$", "usd" -> "USD"
        "€", "eur" -> "EUR"
        "₸", "kzt" -> "KZT"
        "£" -> "GBP"
        "byn" -> "BYN"
        else -> "RUB"
    }

    /**
     * Похоже ли сообщение на операцию по счёту, а не на рекламу или код.
     * Используется для автозахвата уведомлений, где ложные срабатывания недопустимы.
     */
    fun looksLikeOperation(text: String): Boolean =
        operation.containsMatchIn(text) && !promo.containsMatchIn(text) && !ignore.containsMatchIn(text)

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ParsedTxn? {
        val text = raw.replace('\n', ' ').trim()
        if (text.isEmpty() || ignore.containsMatchIn(text)) return null

        // Сумма операции ищется только до слов «Баланс/Доступно», чтобы не принять остаток за трату.
        val head = balance.find(text)?.let { text.substring(0, it.range.first) } ?: text

        val suffix = suffixAmount.find(head)
        val prefix = prefixAmount.find(head)
        val usePrefix = prefix != null && (suffix == null || prefix.range.first < suffix.range.first)
        val match = (if (usePrefix) prefix else suffix) ?: return null
        val amountText = if (usePrefix) match.groupValues[2] else match.groupValues[1]
        val currencyToken = if (usePrefix) match.groupValues[1] else match.groupValues[2]
        val amount = AmountParser.parseMinor(amountText)?.takeIf { it > 0 } ?: return null

        val isIncome = income.containsMatchIn(text)
        val cardTail = card.find(text)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.ifEmpty { null }

        var merchant = cleanMerchant(head.substring(match.range.last + 1))
        if (merchant.isEmpty()) {
            merchant = cleanMerchant(operationWords.replace(head.substring(0, match.range.first), " "))
        }
        if (merchant.isEmpty()) merchant = if (isIncome) "Поступление" else "Покупка"

        return ParsedTxn(
            amountMinor = amount,
            currency = currencyCode(currencyToken),
            dateMillis = dateIn(text, nowMillis, zone),
            merchant = merchant,
            isIncome = isIncome,
            cardTail = cardTail,
        )
    }

    private fun cleanMerchant(raw: String): String {
        var s = card.replace(raw, " ")
        s = time.replace(s, " ")
        s = dateLike.replace(s, " ")
        s = s.trim(*trimChars)
        s = leadingWord.replace(s, "")
        sentenceEnd.find(s)?.let { s = s.substring(0, it.range.first) }
        s = trailingWord.replace(s, "")
        return s.replace(Regex("\\s{2,}"), " ").trim(*trimChars)
    }

    /** Дата берётся из сообщения, только если она указана полностью; иначе — текущий момент. */
    private fun dateIn(text: String, nowMillis: Long, zone: ZoneId): Long {
        val m = fullDate.find(text) ?: return nowMillis
        return try {
            val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            val c = clock.find(text)
            val dt = LocalDateTime.of(
                year, m.groupValues[2].toInt(), m.groupValues[1].toInt(),
                c?.groupValues?.get(1)?.toInt() ?: 12, c?.groupValues?.get(2)?.toInt() ?: 0,
            )
            val millis = dt.atZone(zone).toInstant().toEpochMilli()
            if (millis <= nowMillis + 86_400_000L) millis else nowMillis
        } catch (e: Exception) {
            nowMillis
        }
    }
}
