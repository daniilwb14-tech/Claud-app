import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }

    static let brand = Color(hex: 0x00B37A)
    static let brandDeep = Color(hex: 0x063D33)
    static let income = Color(hex: 0x00B37A)
}

extension SpendCategory {
    var color: Color { Color(hex: colorHex) }
}

extension Marketplace {
    var color: Color { Color(hex: colorHex) }
}

enum Money {
    private static let locale = Locale(identifier: "ru_RU")

    /// «1 234,5 ₽»
    static func string(_ value: Decimal, code: String = "RUB") -> String {
        value.formatted(.currency(code: code).precision(.fractionLength(0...2)).locale(locale))
    }

    /// Без копеек — для крупных цифр на карточках: «12 345 ₽»
    static func short(_ value: Decimal, code: String = "RUB") -> String {
        value.formatted(.currency(code: code).precision(.fractionLength(0)).locale(locale))
    }
}

extension Date {
    var monthTitle: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "LLLL yyyy"
        return f.string(from: self).capitalized(with: Locale(identifier: "ru_RU"))
    }

    var dayTitle: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(self) { return "Сегодня" }
        if calendar.isDateInYesterday(self) { return "Вчера" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM, EEEE"
        return f.string(from: self)
    }
}

// MARK: Подсчёты по списку записей

extension Array where Element == Entry {
    var expenses: [Entry] { filter { !$0.isIncome } }
    var incomes: [Entry] { filter { $0.isIncome } }
    var total: Decimal { reduce(0) { $0 + $1.amount } }

    func inMonth(of date: Date, calendar: Calendar = .current) -> [Entry] {
        filter { calendar.isDate($0.date, equalTo: date, toGranularity: .month) }
    }

    func totalsByCategory() -> [(category: SpendCategory, total: Decimal)] {
        Dictionary(grouping: self, by: \.category)
            .map { (category: $0.key, total: $0.value.total) }
            .sorted { $0.total > $1.total }
    }

    func totalsByMarketplace() -> [(marketplace: Marketplace, total: Decimal, count: Int)] {
        Dictionary(grouping: filter { $0.marketplace != nil }, by: { $0.marketplace! })
            .map { (marketplace: $0.key, total: $0.value.total, count: $0.value.count) }
            .sorted { $0.total > $1.total }
    }
}

// MARK: Общие элементы интерфейса

struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct IconBadge: View {
    var symbol: String
    var color: Color
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
    }
}

struct EntryRow: View {
    let entry: Entry

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(symbol: entry.category.icon, color: entry.marketplace?.color ?? entry.category.color)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                HStack(spacing: 5) {
                    Text(entry.marketplace?.title ?? entry.category.title)
                    if entry.source != .manual {
                        Image(systemName: entry.source.icon)
                            .accessibilityLabel(entry.source.title)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Text((entry.isIncome ? "+" : "−") + Money.string(entry.amount, code: entry.currency))
                .font(.system(.body, design: .rounded).weight(.semibold))
                .foregroundStyle(entry.isIncome ? Color.income : Color.primary)
                .monospacedDigit()
        }
        .contentShape(Rectangle())
    }
}

struct SectionTitle: View {
    var text: String

    var body: some View {
        Text(text)
            .font(.title3.weight(.bold))
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
