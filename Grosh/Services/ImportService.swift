import Foundation
import SwiftData

/// Единая точка, через которую в базу попадают автоматически распознанные операции.
@MainActor
enum ImportService {

    struct Outcome {
        var added = 0
        var duplicates = 0
    }

    /// Сохраняет операции, пропуская те, что уже есть в базе (тот же день, сумма и тип).
    @discardableResult
    static func save(_ txns: [ParsedTxn], source: EntrySource, in context: ModelContext, deduplicate: Bool = true) -> Outcome {
        guard !txns.isEmpty else { return Outcome() }
        var fresh = txns
        if deduplicate {
            let existing = ((try? context.fetch(FetchDescriptor<Entry>())) ?? []).map {
                Deduplicator.key(date: $0.date, amount: $0.amount, isIncome: $0.isIncome)
            }
            fresh = Deduplicator.filterNew(txns, existing: existing)
        }
        for txn in fresh {
            context.insert(Entry(txn, source: source))
        }
        try? context.save()
        return Outcome(added: fresh.count, duplicates: txns.count - fresh.count)
    }

    /// CSV со всеми операциями для выгрузки в Excel/Numbers.
    static func exportCSV(_ entries: [Entry]) -> URL? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "dd.MM.yyyy HH:mm"

        func quote(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }

        var rows = ["Дата;Тип;Сумма;Валюта;Категория;Маркетплейс;Продавец;Заметка;Источник"]
        for e in entries {
            let amount = "\(e.amount)".replacingOccurrences(of: ".", with: ",")
            rows.append([
                formatter.string(from: e.date),
                e.isIncome ? "Доход" : "Расход",
                (e.isIncome ? "" : "-") + amount,
                e.currency,
                quote(e.category.title),
                quote(e.marketplace?.title ?? ""),
                quote(e.merchant),
                quote(e.note),
                quote(e.source.title),
            ].joined(separator: ";"))
        }
        // BOM нужен, чтобы Excel правильно открыл кириллицу.
        let text = "\u{FEFF}" + rows.joined(separator: "\n")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("grosh-export.csv")
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }

    /// Три месяца правдоподобных трат — чтобы посмотреть приложение в деле до подключения банка.
    static func insertDemoData(in context: ModelContext) {
        let calendar = Calendar.current
        let now = Date()
        var generator = SeededGenerator(seed: 42)

        func add(_ merchant: String, _ amount: Int, daysAgo: Int, income: Bool = false) {
            guard let day = calendar.date(byAdding: .day, value: -daysAgo, to: now) else { return }
            let date = calendar.date(bySettingHour: Int.random(in: 9...21, using: &generator), minute: Int.random(in: 0...59, using: &generator), second: 0, of: day) ?? day
            guard date <= now else { return }
            let txn = ParsedTxn(amount: Decimal(amount), date: date, merchant: merchant, isIncome: income)
            context.insert(Entry(txn, source: .demo))
        }

        let daily: [(String, ClosedRange<Int>, Double)] = [
            ("Пятёрочка", 350...1900, 0.55), ("ВкусВилл", 400...2200, 0.25), ("Яндекс Такси", 180...950, 0.3),
            ("Кофе Хауз", 190...420, 0.35), ("Додо Пицца", 600...1700, 0.1), ("Метро", 60...120, 0.5),
            ("OZON", 300...6500, 0.22), ("WILDBERRIES", 250...5200, 0.25), ("ALIEXPRESS", 150...3200, 0.07),
            ("Яндекс Маркет", 500...7000, 0.06), ("Аптека Горздрав", 200...1800, 0.07), ("Лукойл АЗС", 1500...3200, 0.08),
            ("Кинотеатр", 450...900, 0.04), ("DNS", 1200...15000, 0.02),
        ]
        for daysAgo in 0..<90 {
            for (merchant, range, chance) in daily {
                let roll = Double.random(in: 0...1, using: &generator)
                guard roll < chance else { continue }
                let amount = Int.random(in: range, using: &generator)
                add(merchant, amount, daysAgo: daysAgo)
            }
        }
        let today = calendar.component(.day, from: now)
        for month in 0..<3 {
            let base = month * 30 + today
            add("Яндекс Плюс", 399, daysAgo: base - 5)
            add("Кинопоиск", 299, daysAgo: base - 12)
            add("iCloud", 149, daysAgo: base - 18)
            add("МТС связь", 650, daysAgo: base - 3)
            add("Квартплата ЖКХ", 6800, daysAgo: base - 10)
            add("Зарплата", 95000, daysAgo: base - 5, income: true)
            add("Аванс", 45000, daysAgo: base - 20, income: true)
        }
        try? context.save()
    }

    static func deleteAll(in context: ModelContext) {
        try? context.delete(model: Entry.self)
        try? context.save()
    }
}

/// Детерминированный генератор, чтобы демо-данные были одинаковыми при каждом запуске.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
