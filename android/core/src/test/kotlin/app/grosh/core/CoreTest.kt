package app.grosh.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun amounts() {
        assertEquals(123456, AmountParser.parseMinor("1 234,56"))
        assertEquals(123456, AmountParser.parseMinor("1,234.56"))
        assertEquals(-34950, AmountParser.parseMinor("-349,50"))
        assertEquals(150000, AmountParser.parseMinor("₽1 500"))
        assertEquals(1234500, AmountParser.parseMinor("12,345"))
        assertEquals(50000, AmountParser.parseMinor("500 р."))
        assertEquals(5000000, AmountParser.parseMinor("+50 000.00 ₽"))
        assertNull(AmountParser.parseMinor("нет суммы"))
    }

    @Test fun sber() {
        val t = BankMessageParser.parse("ECMC1234 14:05 Покупка 349р PYATEROCHKA Баланс: 12 345.67р")!!
        assertEquals(34900, t.amountMinor); assertEquals("PYATEROCHKA", t.merchant)
        assertFalse(t.isIncome); assertEquals("1234", t.cardTail); assertEquals("RUB", t.currency)
    }

    @Test fun tbank() {
        val t = BankMessageParser.parse("Покупка, карта *1234. 1 500 RUB. OZON. Доступно 10 000 RUB")!!
        assertEquals(150000, t.amountMinor); assertEquals("OZON", t.merchant); assertEquals("1234", t.cardTail)
    }

    @Test fun alfaAndVtb() {
        val a = BankMessageParser.parse("*1234 Покупка 349,50 RUR PYATEROCHKA Остаток: 1 000,00 RUR")!!
        assertEquals(34950, a.amountMinor); assertEquals("PYATEROCHKA", a.merchant)
        val v = BankMessageParser.parse("Оплата 500.00р Карта*1234 WILDBERRIES Баланс 1000.00р 14:05")!!
        assertEquals(50000, v.amountMinor); assertEquals("WILDBERRIES", v.merchant)
        val s = BankMessageParser.parse("Покупка 1 234,56 ₽ в OZON. Карта *1234. Баланс: 5 000 ₽")!!
        assertEquals(123456, s.amountMinor); assertEquals("OZON", s.merchant)
    }

    @Test fun salaryWithDate() {
        val t = BankMessageParser.parse("MIR-1234 01.02.24 14:05 зачисление зарплаты 50 000р Баланс: 61 000р", at(2024, 2, 10), zone)!!
        assertEquals(5000000, t.amountMinor); assertTrue(t.isIncome)
        assertEquals(at(2024, 2, 1, 14, 5), t.dateMillis)
        assertEquals(SpendCategory.SALARY, MerchantClassifier.category(t.merchant, isIncome = true))
    }

    @Test fun english() {
        val t = BankMessageParser.parse("You paid $1,234.56 at STARBUCKS on 01/02")!!
        assertEquals(123456, t.amountMinor); assertEquals("USD", t.currency); assertEquals("STARBUCKS", t.merchant)
    }

    @Test fun ignored() {
        assertNull(BankMessageParser.parse("Баланс 5 000р"))
        assertNull(BankMessageParser.parse("Код подтверждения 1234. Никому не сообщайте его. Сумма 500р"))
        assertNull(BankMessageParser.parse("Отказ. Покупка 500р OZON. Недостаточно средств"))
        assertNull(BankMessageParser.parse("Привет, как дела?"))
    }

    @Test fun operationFilter() {
        assertTrue(BankMessageParser.looksLikeOperation("Покупка 349 ₽ Пятёрочка"))
        assertTrue(BankMessageParser.looksLikeOperation("Перевод 500 ₽ от Ивана И."))
        assertFalse(BankMessageParser.looksLikeOperation("Скидка 500 ₽ на первую покупку!"))
        assertFalse(BankMessageParser.looksLikeOperation("Вам одобрен кредит 300 000 ₽"))
        assertFalse(BankMessageParser.looksLikeOperation("Ваш заказ на 1 500 ₽ уже в пути"))
    }

    @Test fun marketplacesAndCategories() {
        assertEquals(Marketplace.OZON, MerchantClassifier.marketplace("OZON.RU MOSKVA"))
        assertNull(MerchantClassifier.marketplace("OZON BANK"))
        assertEquals(Marketplace.WILDBERRIES, MerchantClassifier.marketplace("WB RETAIL"))
        assertEquals(Marketplace.ALIEXPRESS, MerchantClassifier.marketplace("ALIEXPRESS.COM"))
        assertEquals(Marketplace.YANDEX_MARKET, MerchantClassifier.marketplace("Яндекс Маркет"))
        assertNull(MerchantClassifier.marketplace("PYATEROCHKA 1234"))
        assertEquals(SpendCategory.GROCERIES, MerchantClassifier.category("PYATEROCHKA 1234"))
        assertEquals(SpendCategory.GROCERIES, MerchantClassifier.category("Пятёрочка"))
        assertEquals(SpendCategory.MARKETPLACES, MerchantClassifier.category("OZON"))
        assertEquals(SpendCategory.SUBSCRIPTIONS, MerchantClassifier.category("YANDEX*PLUS"))
        assertEquals(SpendCategory.TRANSPORT, MerchantClassifier.category("YANDEX.TAXI"))
        assertEquals(SpendCategory.GROCERIES, MerchantClassifier.category("METRO C&C"))
        assertEquals(SpendCategory.OTHER, MerchantClassifier.category("Неизвестный магазин"))
    }

    @Test fun tbankCsv() {
        val csv = """
            "Дата операции";"Дата платежа";"Номер карты";"Статус";"Сумма операции";"Валюта операции";"Сумма платежа";"Валюта платежа";"Описание"
            "31.01.2024 14:05:33";"01.02.2024";"*1234";"OK";"-349,50";"RUB";"-349,50";"RUB";"Пятёрочка"
            "30.01.2024 10:00:00";"30.01.2024";"*1234";"OK";"50000,00";"RUB";"50000,00";"RUB";"Зарплата; аванс"
            "29.01.2024 09:00:00";"";"*1234";"FAILED";"-100,00";"RUB";"-100,00";"RUB";"OZON"
        """.trimIndent()
        val r = CsvStatementParser.parse(csv, zone)
        assertEquals(2, r.txns.size); assertEquals(1, r.skippedRows)
        assertEquals(34950, r.txns[0].amountMinor); assertFalse(r.txns[0].isIncome)
        assertEquals("Пятёрочка", r.txns[0].merchant); assertEquals("1234", r.txns[0].cardTail)
        assertTrue(r.txns[1].isIncome); assertEquals("Зарплата; аванс", r.txns[1].merchant)
    }

    @Test fun csvWithoutSignsAndCp1251() {
        val r = CsvStatementParser.parse("Date,Description,Amount,Type\n2024-03-01,Coffee,4.50,expense\n2024-03-02,Paycheck,1000,income\n", zone)
        assertEquals(2, r.txns.size); assertFalse(r.txns[0].isIncome); assertTrue(r.txns[1].isIncome)
        val bytes = "Дата;Сумма;Описание\n01.02.2024;-100,00;Лента\n".toByteArray(charset("windows-1251"))
        assertEquals("Лента", CsvStatementParser.parse(CsvStatementParser.decode(bytes), zone).txns.single().merchant)
    }

    @Test fun tbankPdfSingleLine() {
        val text = """
            Справка о движении средств
            Движение средств за период с 01.02.2024 по 29.02.2024
            Дата и время операции Дата списания Сумма в валюте операции Сумма операции в валюте карты Описание операции Номер карты
            01.02.2024 03.02.2024 -349.50 ₽ -349.50 ₽ Оплата в PYATEROCHKA 1234 Moskva RUS 5678
            14:05 10:12
            05.02.2024 05.02.2024 +50 000.00 ₽ +50 000.00 ₽ Пополнение. Зарплата 5678
            09:00 09:01
            07.02.2024 08.02.2024 -1 500.00 ₽ -1 500.00 ₽ Оплата в OZON.RU 5678
            20:15 03:00
            Пополнения: 50 000.00 ₽
            Расходы: 1 849.50 ₽
            АО «ТБанк» универсальная лицензия
        """.trimIndent()
        val r = PdfStatementParser.parse(text, zone)
        assertEquals(3, r.txns.size)
        assertEquals(34950, r.txns[0].amountMinor); assertFalse(r.txns[0].isIncome)
        assertEquals(at(2024, 2, 1, 14, 5), r.txns[0].dateMillis)
        assertEquals(SpendCategory.GROCERIES, MerchantClassifier.category(r.txns[0].merchant))
        assertEquals(5000000, r.txns[1].amountMinor); assertTrue(r.txns[1].isIncome)
        assertEquals(150000, r.txns[2].amountMinor)
        assertEquals(Marketplace.OZON, MerchantClassifier.marketplace(r.txns[2].merchant))
    }

    @Test fun tbankPdfStackedColumns() {
        val text = "01.02.2024\n14:05\n03.02.2024\n10:12\n-349.50 ₽\n-349.50 ₽\nОплата в PYATEROCHKA 1234\n5678\n" +
            "05.02.2024\n09:00\n05.02.2024\n09:01\n+1 000.00 ₽\n+1 000.00 ₽\nПополнение. Система быстрых платежей\n5678\n"
        val r = PdfStatementParser.parse(text, zone)
        assertEquals(2, r.txns.size)
        assertEquals(at(2024, 2, 1, 14, 5), r.txns[0].dateMillis)
        assertEquals(34950, r.txns[0].amountMinor); assertTrue(r.txns[0].merchant.startsWith("PYATEROCHKA"))
        assertTrue(r.txns[1].isIncome); assertEquals(100000, r.txns[1].amountMinor)
    }

    @Test fun sberPdf() {
        val text = """
            Выписка по счёту дебетовой карты
            ДАТА ОПЕРАЦИИ (МСК) КАТЕГОРИЯ СУММА В ВАЛЮТЕ СЧЁТА ОСТАТОК СРЕДСТВ
            01.02.2024 14:05 123456 Супермаркеты 349,50 12 345,67
            02.02.2024 PYATEROCHKA 1234 Moskva RUS. Операция по карте ****1234
            03.02.2024 09:00 654321 Прочие операции +50 000,00 62 345,67
            03.02.2024 Заработная плата. Операция по карте ****1234
        """.trimIndent()
        val r = PdfStatementParser.parse(text, zone)
        assertEquals(2, r.txns.size)
        assertEquals(34950, r.txns[0].amountMinor); assertFalse(r.txns[0].isIncome)
        assertTrue(r.txns[0].merchant.startsWith("PYATEROCHKA"))
        assertEquals(at(2024, 2, 1, 14, 5), r.txns[0].dateMillis)
        assertEquals(5000000, r.txns[1].amountMinor); assertTrue(r.txns[1].isIncome)
    }

    @Test fun receiptQr() {
        val t = ReceiptQrParser.parse("t=20240115T1405&s=1234.56&fn=9289000100123456&i=12345&fp=1234567890&n=1", zone)!!
        assertEquals(123456, t.amountMinor); assertEquals(at(2024, 1, 15, 14, 5), t.dateMillis)
        assertNull(ReceiptQrParser.parse("https://example.com"))
    }

    @Test fun dedup() {
        val a = ParsedTxn(10000, dateMillis = at(2024, 1, 5), merchant = "A")
        val b = ParsedTxn(10000, dateMillis = at(2024, 1, 5, 18), merchant = "B")
        val c = ParsedTxn(25000, dateMillis = at(2024, 1, 6), merchant = "C")
        val existing = listOf(Deduplicator.Existing(10000, false, at(2024, 1, 5, 9)))
        assertEquals(listOf(b, c), Deduplicator.filterNewByDay(listOf(a, b, c), existing, zone))
        assertTrue(Deduplicator.isNearDuplicate(a, listOf(Deduplicator.Existing(10000, false, at(2024, 1, 5, 12, 3)))))
        assertFalse(Deduplicator.isNearDuplicate(a, existing))
    }

    @Test fun subscriptions() {
        val charges = listOf(
            SubscriptionDetector.Charge("YANDEX*PLUS 4411", 39900, at(2024, 1, 5)),
            SubscriptionDetector.Charge("YANDEX PLUS", 39900, at(2024, 2, 5)),
            SubscriptionDetector.Charge("YANDEX*PLUS", 39900, at(2024, 3, 5)),
            SubscriptionDetector.Charge("PYATEROCHKA", 50000, at(2024, 3, 1)),
            SubscriptionDetector.Charge("PYATEROCHKA", 52000, at(2024, 3, 3)),
            SubscriptionDetector.Charge("PYATEROCHKA", 48000, at(2024, 3, 9)),
        )
        val subs = SubscriptionDetector.detect(charges, at(2024, 3, 20), zone)
        assertEquals(1, subs.size); assertEquals(30, subs[0].periodDays); assertEquals(3, subs[0].chargeCount)
        assertTrue(SubscriptionDetector.detect(charges, at(2024, 9, 1), zone).isEmpty())
    }

    @Test fun insights() {
        assertEquals(150000, Insights.safeToSpendToday(3000000, 1000000, 50000, 10))
        assertEquals(0, Insights.safeToSpendToday(100000, 500000, 0, 3))
        assertEquals(5.0, Insights.workHours(300000, 60000))
        assertNull(Insights.workHours(300000, 0))
    }
}
