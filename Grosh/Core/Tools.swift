import Foundation

/// QR-код с кассового чека (ФНС): «t=20240115T1405&s=1234.56&fn=…&i=…&fp=…&n=1».
/// Сумма и дата зашиты прямо в код, интернет не нужен.
enum ReceiptQRParser {
    static func parse(_ payload: String, calendar: Calendar = .current) -> ParsedTxn? {
        var fields: [String: String] = [:]
        for pair in payload.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { fields[String(kv[0]).lowercased()] = String(kv[1]) }
        }
        guard let sum = fields["s"], let amount = AmountParser.parse(sum), amount > 0, fields["fn"] != nil else {
            return nil
        }
        var date = Date()
        if let t = fields["t"] {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = calendar.timeZone
            formatter.dateFormat = t.count >= 15 ? "yyyyMMdd'T'HHmmss" : "yyyyMMdd'T'HHmm"
            if let d = formatter.date(from: t) { date = d }
        }
        // n=2 — чек возврата прихода.
        return ParsedTxn(amount: amount, date: date, merchant: "", isIncome: fields["n"] == "2")
    }
}

/// Убирает операции, которые уже есть в базе (например, покупка пришла и из Apple Pay, и из выписки).
enum Deduplicator {
    struct Key: Hashable {
        let day: Int
        let amount: Decimal
        let isIncome: Bool
    }

    static func key(date: Date, amount: Decimal, isIncome: Bool, calendar: Calendar = .current) -> Key {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return Key(day: (c.year ?? 0) * 10_000 + (c.month ?? 0) * 100 + (c.day ?? 0), amount: amount, isIncome: isIncome)
    }

    /// Каждая существующая запись «гасит» не более одной входящей, поэтому две одинаковые
    /// покупки за день внутри одной выписки не схлопываются.
    static func filterNew(_ incoming: [ParsedTxn], existing: [Key], calendar: Calendar = .current) -> [ParsedTxn] {
        var counts: [Key: Int] = [:]
        for k in existing { counts[k, default: 0] += 1 }
        var fresh: [ParsedTxn] = []
        for txn in incoming {
            let k = key(date: txn.date, amount: txn.amount, isIncome: txn.isIncome, calendar: calendar)
            if let left = counts[k], left > 0 {
                counts[k] = left - 1
            } else {
                fresh.append(txn)
            }
        }
        return fresh
    }
}

/// Находит регулярные списания (подписки) по истории трат.
enum SubscriptionDetector {
    struct Charge {
        var merchant: String
        var amount: Decimal
        var date: Date
    }

    struct Subscription: Identifiable, Equatable {
        var id: String
        var merchant: String
        var amount: Decimal
        var periodDays: Int
        var lastDate: Date
        var nextDate: Date
        var chargeCount: Int

        var monthlyCost: Decimal { amount * 30 / Decimal(periodDays) }

        var periodTitle: String {
            switch periodDays {
            case 7: return "раз в неделю"
            case 365: return "раз в год"
            default: return "раз в месяц"
            }
        }
    }

    private static let periods: [(days: Int, range: ClosedRange<Int>)] = [
        (7, 6...8), (30, 27...33), (365, 355...375),
    ]

    static func detect(_ charges: [Charge], now: Date = Date(), calendar: Calendar = .current) -> [Subscription] {
        let groups = Dictionary(grouping: charges) { key(for: $0.merchant) }
        var found: [Subscription] = []

        for (key, group) in groups where !key.isEmpty {
            let sorted = group.sorted { $0.date < $1.date }
            let knownService = MerchantClassifier.category(for: sorted[0].merchant) == .subscriptions
            guard sorted.count >= (knownService ? 2 : 3), let last = sorted.last else { continue }

            // Суммы должны быть близки (цена подписки меняется редко).
            let amounts = sorted.map { $0.amount.doubleValue }.sorted()
            let median = amounts[amounts.count / 2]
            guard median > 0, amounts.allSatisfy({ abs($0 - median) / median <= 0.15 }) else { continue }

            let gaps = zip(sorted.dropFirst(), sorted).map {
                calendar.dateComponents([.day], from: calendar.startOfDay(for: $1.date), to: calendar.startOfDay(for: $0.date)).day ?? 0
            }
            guard let period = periods.first(where: { p in gaps.allSatisfy { p.range.contains($0) } }) else { continue }

            // Давно не списывалось — подписку, видимо, отменили.
            let sinceLast = calendar.dateComponents([.day], from: last.date, to: now).day ?? 0
            guard Double(sinceLast) <= Double(period.days) * 1.6 else { continue }

            let next = calendar.date(byAdding: .day, value: period.days, to: last.date) ?? last.date
            found.append(Subscription(id: key, merchant: last.merchant, amount: last.amount, periodDays: period.days,
                                      lastDate: last.date, nextDate: next, chargeCount: sorted.count))
        }
        return found.sorted { $0.monthlyCost > $1.monthlyCost }
    }

    /// Название без цифр и мусора: «YANDEX*PLUS 4411» и «YANDEX PLUS» — один продавец.
    static func key(for merchant: String) -> String {
        MerchantClassifier.normalize(merchant)
            .split(separator: " ")
            .filter { token in !token.allSatisfy { $0.isNumber } }
            .joined(separator: " ")
    }
}

enum Insights {
    /// Сколько ещё можно потратить сегодня, чтобы уложиться в бюджет месяца.
    static func safeToSpendToday(budget: Decimal, spentBeforeToday: Decimal, spentToday: Decimal, daysLeftIncludingToday: Int) -> Decimal {
        guard daysLeftIncludingToday > 0 else { return 0 }
        let perDay = (budget - spentBeforeToday) / Decimal(daysLeftIncludingToday)
        return max(0, perDay - spentToday)
    }

    /// «Цена в часах работы»: сколько часов нужно отработать ради этой покупки.
    static func workHours(amount: Decimal, hourlyRate: Decimal) -> Double? {
        guard hourlyRate > 0, amount > 0 else { return nil }
        return (amount / hourlyRate).doubleValue
    }

    static func workHoursText(amount: Decimal, hourlyRate: Decimal) -> String? {
        guard let hours = workHours(amount: amount, hourlyRate: hourlyRate) else { return nil }
        if hours < 1 { return "\(max(1, Int((hours * 60).rounded()))) мин работы" }
        if hours < 10 { return String(format: "%.1f ч работы", hours).replacingOccurrences(of: ".", with: ",") }
        return "\(Int(hours.rounded())) ч работы"
    }
}
