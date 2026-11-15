import XCTest
@testable import GroshCore

final class CoreTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s)! }

    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Moscow")!
        return c
    }

    private func day(_ y: Int, _ m: Int, _ d: Int, hour: Int = 12) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: hour))!
    }

    // MARK: Суммы

    func testAmountParser() {
        XCTAssertEqual(AmountParser.parse("1 234,56"), dec("1234.56"))
        XCTAssertEqual(AmountParser.parse("1,234.56"), dec("1234.56"))
        XCTAssertEqual(AmountParser.parse("-349,50"), dec("-349.5"))
        XCTAssertEqual(AmountParser.parse("₽1 500"), dec("1500"))
        XCTAssertEqual(AmountParser.parse("12,345"), dec("12345"))
        XCTAssertEqual(AmountParser.parse("500 р."), dec("500"))
        XCTAssertEqual(AmountParser.parse("$12.34"), dec("12.34"))
        XCTAssertNil(AmountParser.parse("нет суммы"))
    }

    // MARK: SMS банков

    func testSberPurchase() {
        let t = BankMessageParser.parse("ECMC1234 14:05 Покупка 349р PYATEROCHKA Баланс: 12 345.67р")
        XCTAssertEqual(t?.amount, dec("349"))
        XCTAssertEqual(t?.merchant, "PYATEROCHKA")
        XCTAssertEqual(t?.isIncome, false)
        XCTAssertEqual(t?.cardTail, "1234")
        XCTAssertEqual(t?.currency, "RUB")
    }

    func testTBankPurchase() {
        let t = BankMessageParser.parse("Покупка, карта *1234. 1 500 RUB. OZON. Доступно 10 000 RUB")
        XCTAssertEqual(t?.amount, dec("1500"))
        XCTAssertEqual(t?.merchant, "OZON")
        XCTAssertEqual(t?.cardTail, "1234")
    }

    func testAlfaPurchase() {
        let t = BankMessageParser.parse("*1234 Покупка 349,50 RUR PYATEROCHKA Остаток: 1 000,00 RUR")
        XCTAssertEqual(t?.amount, dec("349.5"))
        XCTAssertEqual(t?.merchant, "PYATEROCHKA")
    }

    func testVTBPurchase() {
        let t = BankMessageParser.parse("Оплата 500.00р Карта*1234 WILDBERRIES Баланс 1000.00р 14:05")
        XCTAssertEqual(t?.amount, dec("500"))
        XCTAssertEqual(t?.merchant, "WILDBERRIES")
    }

    func testSentenceStylePurchase() {
        let t = BankMessageParser.parse("Покупка 1 234,56 ₽ в OZON. Карта *1234. Баланс: 5 000 ₽")
        XCTAssertEqual(t?.amount, dec("1234.56"))
        XCTAssertEqual(t?.merchant, "OZON")
    }

    func testSalaryIncomeWithDate() {
        let now = day(2024, 2, 10)
        let t = BankMessageParser.parse("MIR-1234 01.02.24 14:05 зачисление зарплаты 50 000р Баланс: 61 000р", now: now, calendar: calendar)
        XCTAssertEqual(t?.amount, dec("50000"))
        XCTAssertEqual(t?.isIncome, true)
        XCTAssertEqual(t?.date, calendar.date(from: DateComponents(year: 2024, month: 2, day: 1, hour: 14, minute: 5)))
        XCTAssertEqual(MerchantClassifier.category(for: t?.merchant ?? "", isIncome: true), .salary)
    }

    func testEnglishPurchase() {
        let t = BankMessageParser.parse("You paid $1,234.56 at STARBUCKS on 01/02")
        XCTAssertEqual(t?.amount, dec("1234.56"))
        XCTAssertEqual(t?.currency, "USD")
        XCTAssertEqual(t?.merchant, "STARBUCKS")
    }

    func testIgnoredMessages() {
        XCTAssertNil(BankMessageParser.parse("Баланс 5 000р"))
        XCTAssertNil(BankMessageParser.parse("Код подтверждения 1234. Никому не сообщайте его. Сумма 500р"))
        XCTAssertNil(BankMessageParser.parse("Отказ. Покупка 500р OZON. Недостаточно средств"))
        XCTAssertNil(BankMessageParser.parse("Привет, как дела?"))
    }

    // MARK: Категории и маркетплейсы

    func testMarketplaces() {
        XCTAssertEqual(MerchantClassifier.marketplace(for: "OZON.RU MOSKVA"), .ozon)
        XCTAssertNil(MerchantClassifier.marketplace(for: "OZON BANK"))
        XCTAssertEqual(MerchantClassifier.marketplace(for: "WB RETAIL"), .wildberries)
        XCTAssertEqual(MerchantClassifier.marketplace(for: "Wildberries"), .wildberries)
        XCTAssertEqual(MerchantClassifier.marketplace(for: "ALIEXPRESS.COM"), .aliexpress)
        XCTAssertEqual(MerchantClassifier.marketplace(for: "Яндекс Маркет"), .yandexMarket)
        XCTAssertNil(MerchantClassifier.marketplace(for: "PYATEROCHKA 1234"))
    }

    func testCategories() {
        XCTAssertEqual(MerchantClassifier.category(for: "PYATEROCHKA 1234"), .groceries)
        XCTAssertEqual(MerchantClassifier.category(for: "Пятёрочка"), .groceries)
        XCTAssertEqual(MerchantClassifier.category(for: "OZON"), .marketplaces)
        XCTAssertEqual(MerchantClassifier.category(for: "YANDEX*PLUS"), .subscriptions)
        XCTAssertEqual(MerchantClassifier.category(for: "YANDEX.TAXI"), .transport)
        XCTAssertEqual(MerchantClassifier.category(for: "METRO C&C"), .groceries)
        XCTAssertEqual(MerchantClassifier.category(for: "Неизвестный магазин"), .other)
    }

    // MARK: Выписка CSV

    func testTBankCSV() {
        let csv = """
        "Дата операции";"Дата платежа";"Номер карты";"Статус";"Сумма операции";"Валюта операции";"Сумма платежа";"Валюта платежа";"Описание"
        "31.01.2024 14:05:33";"01.02.2024";"*1234";"OK";"-349,50";"RUB";"-349,50";"RUB";"Пятёрочка"
        "30.01.2024 10:00:00";"30.01.2024";"*1234";"OK";"50000,00";"RUB";"50000,00";"RUB";"Зарплата; аванс"
        "29.01.2024 09:00:00";"";"*1234";"FAILED";"-100,00";"RUB";"-100,00";"RUB";"OZON"
        """
        let r = StatementImporter.parse(csv: csv, calendar: calendar)
        XCTAssertEqual(r.txns.count, 2)
        XCTAssertEqual(r.skippedRows, 1)
        XCTAssertEqual(r.txns[0].amount, dec("349.5"))
        XCTAssertEqual(r.txns[0].isIncome, false)
        XCTAssertEqual(r.txns[0].merchant, "Пятёрочка")
        XCTAssertEqual(r.txns[0].cardTail, "1234")
        XCTAssertEqual(r.txns[1].isIncome, true)
        XCTAssertEqual(r.txns[1].merchant, "Зарплата; аванс")
    }

    func testGenericCommaCSVWithoutSigns() {
        let csv = "Date,Description,Amount,Type\n2024-03-01,Coffee,4.50,expense\n2024-03-02,Paycheck,1000,income\n"
        let r = StatementImporter.parse(csv: csv, calendar: calendar)
        XCTAssertEqual(r.txns.count, 2)
        XCTAssertEqual(r.txns[0].isIncome, false)
        XCTAssertEqual(r.txns[1].isIncome, true)
        XCTAssertEqual(r.txns[0].amount, dec("4.5"))
    }

    // MARK: QR чека

    func testReceiptQR() {
        let t = ReceiptQRParser.parse("t=20240115T1405&s=1234.56&fn=9289000100123456&i=12345&fp=1234567890&n=1", calendar: calendar)
        XCTAssertEqual(t?.amount, dec("1234.56"))
        XCTAssertEqual(t?.isIncome, false)
        XCTAssertEqual(t?.date, calendar.date(from: DateComponents(year: 2024, month: 1, day: 15, hour: 14, minute: 5)))
        XCTAssertNil(ReceiptQRParser.parse("https://example.com"))
    }

    // MARK: Дубликаты

    func testDeduplicator() {
        let a = ParsedTxn(amount: 100, date: day(2024, 1, 5), merchant: "A")
        let b = ParsedTxn(amount: 100, date: day(2024, 1, 5, hour: 18), merchant: "B")
        let c = ParsedTxn(amount: 250, date: day(2024, 1, 6), merchant: "C")
        let existing = [Deduplicator.key(date: day(2024, 1, 5, hour: 9), amount: 100, isIncome: false, calendar: calendar)]
        let fresh = Deduplicator.filterNew([a, b, c], existing: existing, calendar: calendar)
        XCTAssertEqual(fresh, [b, c])
    }

    // MARK: Подписки

    func testSubscriptionDetector() {
        typealias Charge = SubscriptionDetector.Charge
        let charges = [
            Charge(merchant: "YANDEX*PLUS 4411", amount: 399, date: day(2024, 1, 5)),
            Charge(merchant: "YANDEX PLUS", amount: 399, date: day(2024, 2, 5)),
            Charge(merchant: "YANDEX*PLUS", amount: 399, date: day(2024, 3, 5)),
            Charge(merchant: "PYATEROCHKA", amount: 500, date: day(2024, 3, 1)),
            Charge(merchant: "PYATEROCHKA", amount: 520, date: day(2024, 3, 3)),
            Charge(merchant: "PYATEROCHKA", amount: 480, date: day(2024, 3, 9)),
        ]
        let subs = SubscriptionDetector.detect(charges, now: day(2024, 3, 20), calendar: calendar)
        XCTAssertEqual(subs.count, 1)
        XCTAssertEqual(subs.first?.periodDays, 30)
        XCTAssertEqual(subs.first?.amount, 399)
        XCTAssertEqual(subs.first?.chargeCount, 3)

        // Через полгода без списаний подписка считается отменённой.
        XCTAssertTrue(SubscriptionDetector.detect(charges, now: day(2024, 9, 1), calendar: calendar).isEmpty)
    }

    // MARK: Инсайты

    func testInsights() {
        XCTAssertEqual(Insights.safeToSpendToday(budget: 30000, spentBeforeToday: 10000, spentToday: 500, daysLeftIncludingToday: 10), 1500)
        XCTAssertEqual(Insights.safeToSpendToday(budget: 1000, spentBeforeToday: 5000, spentToday: 0, daysLeftIncludingToday: 3), 0)
        XCTAssertEqual(Insights.workHours(amount: 3000, hourlyRate: 600), 5)
        XCTAssertNil(Insights.workHours(amount: 3000, hourlyRate: 0))
    }
}
