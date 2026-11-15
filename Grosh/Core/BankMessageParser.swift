import Foundation

/// Разбирает текст SMS/пуша банка («Покупка 349р PYATEROCHKA Баланс: 12 345р») в операцию.
/// Работает со Сбером, Т-Банком, Альфой, ВТБ и большинством похожих форматов.
enum BankMessageParser {

    private struct Rx {
        let re: NSRegularExpression
        init(_ pattern: String) {
            re = try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        }
        func first(_ s: String) -> NSTextCheckingResult? {
            re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: (s as NSString).length))
        }
        func matches(_ s: String) -> Bool { first(s) != nil }
        func replace(_ s: String, with template: String) -> String {
            re.stringByReplacingMatches(in: s, options: [], range: NSRange(location: 0, length: (s as NSString).length), withTemplate: template)
        }
    }

    private static let number = #"(?<![\d.,:*])(?:\d{1,3}(?:[   ,]\d{3})+|\d+)(?:[.,]\d{1,2})?"#
    private static let currencyWord = #"₽|руб(?:лей|ля|\.)?|р\.?|rub|rur|usd|eur|kzt|byn|\$|€|₸|£"#

    /// «349р», «1 500 RUB», «1 234,56 ₽»
    private static let suffixAmount = Rx("(" + number + ")" + #"\s?("# + currencyWord + #")(?![a-zа-яё])"#)
    /// «$12.34», «₽1 500»
    private static let prefixAmount = Rx(#"(\$|€|₽|£|₸)\s?("# + number + ")")

    private static let balance = Rx(#"баланс|доступн|остаток|balance|available|avail\b"#)
    private static let ignore = Rx(#"никому не сообщайте|код подтверждения|одноразовый|verification code|\botp\b|отказ|отклонен|недостаточно средств|declined|insufficient"#)
    private static let income = Rx(#"зачислен|пополнен|поступлен|возврат|входящий перевод|перевод от|получен|зарплат|refund|received|deposit"#)
    private static let card = Rx(#"(?:карт[аеыу]|card|сч[её]т|ecmc|mir|мир|visa|mastercard|maestro)\s?[-–№#]?\s?[*•·]{0,4}(\d{4})(?!\d)|[*•·]{1,4}\s?(\d{4})(?!\d)"#)
    private static let time = Rx(#"\b\d{1,2}:\d{2}(?::\d{2})?\b"#)
    private static let dateLike = Rx(#"\b\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?\b"#)
    private static let fullDate = Rx(#"(?<!\d)(\d{2})\.(\d{2})\.(\d{4}|\d{2})(?!\d)"#)
    private static let clock = Rx(#"(?<!\d)(\d{1,2}):(\d{2})(?!\d)"#)
    private static let leadingWord = Rx(#"^(?:в|at|in|от|from|магазин|to|на)\s+"#)
    private static let trailingWord = Rx(#"\s+(?:on|в|at)$"#)
    private static let sentenceEnd = Rx(#"[.;]\s"#)
    private static let operationWords = Rx(#"покупка|оплата|списание|списано|зачисление|зачислено|перевод|пополнение|платеж|платёж|purchase|payment|you paid|paid"#)

    private static func currencyCode(_ token: String) -> String {
        let t = token.lowercased()
        switch t {
        case "$", "usd": return "USD"
        case "€", "eur": return "EUR"
        case "₸", "kzt": return "KZT"
        case "£": return "GBP"
        case "byn": return "BYN"
        default: return "RUB"
        }
    }

    static func parse(_ text: String, now: Date = Date(), calendar: Calendar = .current) -> ParsedTxn? {
        let text = text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, !ignore.matches(text) else { return nil }
        let ns = text as NSString

        // Сумма операции ищется только до слов «Баланс/Доступно», чтобы не принять остаток за трату.
        let headEnd = balance.first(text)?.range.location ?? ns.length
        let head = ns.substring(to: headEnd)

        var amountRange: NSRange?
        var amountText = "", currencyToken = ""
        if let m = suffixAmount.first(head) {
            amountRange = m.range
            amountText = (head as NSString).substring(with: m.range(at: 1))
            currencyToken = (head as NSString).substring(with: m.range(at: 2))
        }
        if let m = prefixAmount.first(head), amountRange == nil || m.range.location < amountRange!.location {
            amountRange = m.range
            amountText = (head as NSString).substring(with: m.range(at: 2))
            currencyToken = (head as NSString).substring(with: m.range(at: 1))
        }
        guard let range = amountRange, let amount = AmountParser.parse(amountText), amount > 0 else { return nil }

        let isIncome = income.matches(text)

        var cardTail: String?
        if let m = card.first(text) {
            let r = m.range(at: 1).location != NSNotFound ? m.range(at: 1) : m.range(at: 2)
            if r.location != NSNotFound { cardTail = ns.substring(with: r) }
        }

        let after = (head as NSString).substring(from: range.location + range.length)
        var merchant = cleanMerchant(after)
        if merchant.isEmpty {
            let before = (head as NSString).substring(to: range.location)
            merchant = cleanMerchant(operationWords.replace(before, with: " "))
        }
        if merchant.isEmpty { merchant = isIncome ? "Поступление" : "Покупка" }

        return ParsedTxn(amount: amount,
                         currency: currencyCode(currencyToken),
                         date: date(in: text, now: now, calendar: calendar),
                         merchant: merchant,
                         isIncome: isIncome,
                         cardTail: cardTail)
    }

    private static let trimSet = CharacterSet(charactersIn: " .,;:-–—*\u{00A0}")

    private static func cleanMerchant(_ raw: String) -> String {
        var s = card.replace(raw, with: " ")
        s = time.replace(s, with: " ")
        s = dateLike.replace(s, with: " ")
        s = s.trimmingCharacters(in: trimSet)
        s = leadingWord.replace(s, with: "")
        if let m = sentenceEnd.first(s) {
            s = (s as NSString).substring(to: m.range.location)
        }
        s = trailingWord.replace(s, with: "")
        s = s.replacingOccurrences(of: "  ", with: " ")
        return s.trimmingCharacters(in: trimSet)
    }

    /// Дата берётся из сообщения, только если она указана полностью; иначе — текущий момент.
    private static func date(in text: String, now: Date, calendar: Calendar) -> Date {
        guard let m = fullDate.first(text) else { return now }
        let ns = text as NSString
        var comps = DateComponents()
        comps.day = Int(ns.substring(with: m.range(at: 1)))
        comps.month = Int(ns.substring(with: m.range(at: 2)))
        let year = Int(ns.substring(with: m.range(at: 3))) ?? 0
        comps.year = year < 100 ? 2000 + year : year
        if let c = clock.first(text) {
            comps.hour = Int(ns.substring(with: c.range(at: 1)))
            comps.minute = Int(ns.substring(with: c.range(at: 2)))
        } else {
            comps.hour = 12
        }
        guard let d = calendar.date(from: comps), d <= now.addingTimeInterval(86_400) else { return now }
        return d
    }
}
