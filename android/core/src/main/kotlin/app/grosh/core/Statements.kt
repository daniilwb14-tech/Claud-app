package app.grosh.core

import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class StatementResult(val txns: List<ParsedTxn> = emptyList(), val skippedRows: Int = 0)

/** Импорт банковской выписки в CSV (Т-Банк, Альфа, любые выгрузки с колонками дата/сумма/описание). */
object CsvStatementParser {

    /** Выписки российских банков часто приходят в Windows-1251. */
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Символ замены означает, что файл не в UTF-8.
        val text = if ('\uFFFD' in utf8) String(bytes, Charset.forName("windows-1251")) else utf8
        return text.removePrefix("\uFEFF")
    }

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): StatementResult {
        val lines = text.split("\r\n", "\n", "\r").filter { it.isNotBlank() }
        if (lines.size < 2) return StatementResult()

        val delimiter = detectDelimiter(lines[0])
        val header = splitRow(lines[0], delimiter).map { it.lowercase() }
        fun column(vararg keys: String): Int? {
            for (key in keys) {
                val i = header.indexOfFirst { it.contains(key) }
                if (i >= 0) return i
            }
            return null
        }

        val dateCol = column("дата операции", "дата", "date", "time")
        val amountCol = column("сумма операции", "сумма платежа", "сумма", "amount", "sum")
        val expenseCol = column("расход", "списан", "debit", "withdraw")
        val incomeCol = column("приход", "зачисл", "credit", "deposit")
        val descCol = column("описание", "description", "merchant", "назначение", "контрагент", "payee", "details", "name")
        val currencyCol = column("валюта операции", "валюта", "currency")
        val statusCol = column("статус", "status")
        val cardCol = column("номер карты", "card")
        val typeCol = column("тип", "type")

        if (dateCol == null || (amountCol == null && expenseCol == null && incomeCol == null)) return StatementResult()

        var skipped = 0
        var sawNegative = false
        val rows = mutableListOf<Pair<ParsedTxn, String>>()

        for (line in lines.drop(1)) {
            val cells = splitRow(line, delimiter)
            fun cell(i: Int?) = if (i != null && i < cells.size) cells[i].trim() else ""

            val status = cell(statusCol).lowercase()
            if (listOf("fail", "отмен", "declin", "отклон").any { status.contains(it) }) {
                skipped++
                continue
            }
            val date = parseDate(cell(dateCol), zone)
            var signed: Long? = if (amountCol != null) AmountParser.parseMinor(cell(amountCol)) else null
            if (signed == null) AmountParser.parseMinor(cell(expenseCol))?.takeIf { it != 0L }?.let { signed = -abs(it) }
            if (signed == null) AmountParser.parseMinor(cell(incomeCol))?.takeIf { it != 0L }?.let { signed = abs(it) }
            val value = signed
            if (date == null || value == null || value == 0L) {
                skipped++
                continue
            }
            if (value < 0) sawNegative = true

            val currency = cell(currencyCol).uppercase()
            val cardDigits = cell(cardCol).filter { it.isDigit() }
            rows += ParsedTxn(
                amountMinor = abs(value),
                currency = if (currency.length == 3) (if (currency == "RUR") "RUB" else currency) else "RUB",
                dateMillis = date,
                merchant = cell(descCol).ifEmpty { "Операция" },
                isIncome = value > 0,
                cardTail = if (cardDigits.length >= 4) cardDigits.takeLast(4) else null,
            ) to cell(typeCol).lowercase()
        }

        // Если банк выгружает все суммы без знака — считаем расходами всё, кроме явно помеченных поступлений.
        val incomeMarks = listOf("доход", "приход", "пополн", "зачисл", "income", "credit", "deposit")
        val txns = rows.map { (txn, type) ->
            if (sawNegative) txn else txn.copy(isIncome = incomeMarks.any { type.contains(it) })
        }
        return StatementResult(txns, skipped)
    }

    fun detectDelimiter(line: String): Char = listOf(';', ',', '\t').maxByOrNull { d -> line.count { it == d } } ?: ';'

    /** Разбивает строку CSV с учётом кавычек и экранирования «""». */
    fun splitRow(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    private val dateTimeFormats = listOf("dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")
        .map { DateTimeFormatter.ofPattern(it) }
    private val dateFormats = listOf("dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "dd.MM.yy").map { DateTimeFormatter.ofPattern(it) }

    fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        for (f in dateTimeFormats) {
            try {
                return LocalDateTime.parse(s, f).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        for (f in dateFormats) {
            try {
                return LocalDate.parse(s, f).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
    }
}

/**
 * Разбор текста, извлечённого из PDF-выписки банка (Т-Банк, Сбер, Альфа и похожие).
 *
 * PDF не хранит таблицу — только строки текста, поэтому парсер опирается на общие признаки:
 * запись начинается с даты «дд.мм.гггг», в ней есть сумма, остальное — описание.
 */
object PdfStatementParser {

    private val date = Regex("""(?<!\d)(\d{2})\.(\d{2})\.(\d{4})(?!\d)""")
    private val startsWithDate = Regex("""^\s*\d{2}\.\d{2}\.\d{4}(?!\d)""")
    private val time = Regex("""(?<![\d.,])(\d{1,2}):(\d{2})(?::\d{2})?(?![\d.,])""")
    private const val NUM = """(?:\d{1,3}(?:[ \u00A0\u202F]\d{3})+|\d+)(?:[.,]\d{2})?"""

    /** «-349.50 ₽», «+50 000,00 RUB», «1 500 ₽» — сумма с валютой (Т-Банк, Альфа). */
    private val moneyWithCurrency = Regex("""([+\-−–]\s?)?(?<![\d.,])($NUM)\s?(₽|руб\.?|RUB|RUR|р\.)(?![a-zа-яё])""", RegexOption.IGNORE_CASE)

    /** «349,50», «+50 000,00» — сумма с копейками без валюты (Сбер). */
    private val moneyPlain = Regex("""([+\-−–]\s?)?(?<![\d.,:])(\d{1,3}(?:[ \u00A0\u202F]\d{3})*[.,]\d{2})(?![\d.,])""")

    private val cardTail = Regex("""(?:[*•·]{2,4}|\s)(\d{4})\s*$""")
    private val incomeWords = Regex("""пополнен|зачислен|поступлен|возврат|входящ|зарплат|кэшб[эе]к|проценты на остаток|refund""", RegexOption.IGNORE_CASE)
    private val leadingNoise = Regex("""^(?:оплата в|оплата|покупка в|покупка|операция по карте)\s+""", RegexOption.IGNORE_CASE)
    private val authCode = Regex("""(?<!\d)\d{6}(?!\d)""")

    /** Строки-итоги и колонтитулы, после которых записи не продолжаются. */
    private val stopLine = Regex(
        """^(?:итого|пополнения|расходы|остаток|баланс|страница|page|ао «|пао |с уважением|руководитель|дата формирования|входящий остаток|исходящий остаток)""",
        RegexOption.IGNORE_CASE,
    )

    private class Record(var text: String)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): StatementResult {
        // 1. Режем текст на записи: каждая начинается со строки, открывающейся датой.
        val records = mutableListOf<Record>()
        var open = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (startsWithDate.containsMatchIn(line)) {
                records += Record(line)
                open = true
            } else if (stopLine.containsMatchIn(line)) {
                open = false
            } else if (open) {
                records.last().text += " $line"
            }
        }

        // 2. Разбираем записи. Запись без суммы — это либо «висящая» дата перед следующей записью,
        //    либо вторая строка предыдущей (так устроена выписка Сбера).
        val txns = mutableListOf<ParsedTxn>()
        var skipped = 0
        var pendingDate: Long? = null

        for (record in records) {
            val recordDate = firstDate(record.text, zone)
            val money = moneyWithCurrency.find(record.text) ?: moneyPlain.find(record.text)
            if (money == null) {
                val extra = describe(record.text, null)
                if (extra.isEmpty()) {
                    if (pendingDate == null) pendingDate = recordDate
                } else if (txns.isNotEmpty()) {
                    val last = txns.last()
                    // У Сбера в первой строке категория, а продавец — во второй: он точнее.
                    txns[txns.size - 1] = last.copy(merchant = extra.take(80), isIncome = last.isIncome || incomeWords.containsMatchIn(extra))
                }
                continue
            }
            val amount = AmountParser.parseMinor(money.groupValues[2])
            // «Висящая» дата — это дата операции, если она не позже даты списания больше чем на 10 дней.
            val pending = pendingDate
            pendingDate = null
            val dateMillis = when {
                pending == null -> recordDate
                recordDate == null -> pending
                recordDate - pending in 0..(10 * 86_400_000L) -> pending
                else -> recordDate
            }
            if (amount == null || amount == 0L || dateMillis == null) {
                skipped++
                continue
            }
            val sign = money.groupValues[1].trim()
            val description = describe(record.text, money)
            val isIncome = when {
                sign == "+" -> true
                sign.isNotEmpty() -> false
                else -> incomeWords.containsMatchIn(description)
            }
            val tail = cardTail.find(record.text)?.groupValues?.get(1)
            txns += ParsedTxn(
                amountMinor = amount,
                dateMillis = dateMillis,
                merchant = description.take(80).ifEmpty { if (isIncome) "Поступление" else "Операция" },
                isIncome = isIncome,
                cardTail = tail,
            )
        }
        return StatementResult(txns, skipped)
    }

    private fun firstDate(text: String, zone: ZoneId): Long? {
        val d = date.find(text) ?: return null
        return try {
            val day = LocalDate.of(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt())
            val t = time.find(text)
            val clock = if (t != null) LocalTime.of(t.groupValues[1].toInt(), t.groupValues[2].toInt()) else LocalTime.NOON
            day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Описание = запись без дат, времени, сумм, кода авторизации и номера карты. */
    private fun describe(text: String, money: MatchResult?): String {
        var s = text
        if (money != null) {
            // Всё, что левее суммы, — служебные колонки (даты, код авторизации, категория у Сбера).
            val before = s.substring(0, money.range.first)
            val after = s.substring(money.range.last + 1)
            val beforeClean = clean(before)
            val afterClean = clean(after)
            s = if (afterClean.isNotEmpty()) afterClean else beforeClean
            return s
        }
        return clean(s)
    }

    private fun clean(raw: String): String {
        var s = date.replace(raw, " ")
        s = time.replace(s, " ")
        s = moneyWithCurrency.replace(s, " ")
        s = moneyPlain.replace(s, " ")
        s = authCode.replace(s, " ")
        s = cardTail.replace(s.trimEnd(), " ")
        s = s.replace(Regex("""[*•·]{2,}\d{0,4}"""), " ")
        s = s.replace(Regex("""\s{2,}"""), " ").trim(' ', '.', ',', ';', ':', '-', '–', '+')
        s = leadingNoise.replace(s, "")
        return s.trim()
    }
}
