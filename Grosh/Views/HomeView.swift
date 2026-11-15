import SwiftData
import SwiftUI

enum QuickSheet: String, Identifiable {
    case add, paste, scan
    var id: String { rawValue }
}

struct HomeView: View {
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]
    @AppStorage(Prefs.budget) private var budget: Double = 60000
    @State private var sheet: QuickSheet?
    @State private var editing: Entry?

    private var calendar: Calendar { .current }
    private var monthExpenses: [Entry] { entries.inMonth(of: Date()).expenses }
    private var spent: Decimal { monthExpenses.total }
    private var income: Decimal { entries.inMonth(of: Date()).incomes.total }
    private var spentToday: Decimal { monthExpenses.filter { calendar.isDateInToday($0.date) }.total }

    private var daysLeft: Int {
        let now = Date()
        let range = calendar.range(of: .day, in: .month, for: now) ?? 1..<31
        return range.count - calendar.component(.day, from: now) + 1
    }

    private var safeToday: Decimal {
        Insights.safeToSpendToday(budget: Decimal(budget), spentBeforeToday: spent - spentToday,
                                  spentToday: spentToday, daysLeftIncludingToday: daysLeft)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    hero
                    quickActions
                    statsRow
                    marketplaceCard
                    recent
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(Date().monthTitle)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        sheet = .add
                    } label: {
                        Image(systemName: "plus.circle.fill").font(.title3)
                    }
                    .accessibilityLabel("Добавить операцию")
                }
            }
            .sheet(item: $sheet) { which in
                switch which {
                case .add: AddEntryView()
                case .paste: SmartPasteView()
                case .scan: ScanReceiptView()
                }
            }
            .sheet(item: $editing) { AddEntryView(entry: $0) }
        }
    }

    // MARK: Главная карточка

    private var hero: some View {
        let budgetValue = Decimal(budget)
        let progress = budgetValue > 0 ? min(1, (spent / budgetValue).doubleValue) : 0
        let over = budgetValue > 0 && spent > budgetValue

        return VStack(alignment: .leading, spacing: 14) {
            Text("Можно потратить сегодня")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.75))
            Text(Money.short(safeToday))
                .font(.system(size: 46, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .contentTransition(.numericText())
                .minimumScaleFactor(0.6)
                .lineLimit(1)

            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: progress)
                    .tint(over ? Color(hex: 0xFF6B5E) : .white)
                    .background(.white.opacity(0.15), in: Capsule())
                HStack {
                    Text("Потрачено \(Money.short(spent)) из \(Money.short(budgetValue))")
                    Spacer()
                    Text(daysLeftText)
                }
                .font(.caption)
                .foregroundStyle(.white.opacity(0.8))
            }

            if over {
                Label("Бюджет превышен на \(Money.short(spent - budgetValue))", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color(hex: 0xFFD0CB))
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(colors: [Color(hex: 0x0B6B56), .brandDeep], startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
    }

    private var daysLeftText: String {
        let n = daysLeft
        let mod10 = n % 10, mod100 = n % 100
        let word: String
        if mod10 == 1 && mod100 != 11 { word = "день" }
        else if (2...4).contains(mod10) && !(12...14).contains(mod100) { word = "дня" }
        else { word = "дней" }
        return "ещё \(n) \(word)"
    }

    // MARK: Быстрые действия

    private var quickActions: some View {
        HStack(spacing: 10) {
            action("Вручную", "plus", .add)
            action("Из SMS", "doc.on.clipboard", .paste)
            action("Скан чека", "qrcode.viewfinder", .scan)
        }
    }

    private func action(_ title: String, _ symbol: String, _ target: QuickSheet) -> some View {
        Button {
            sheet = target
        } label: {
            VStack(spacing: 8) {
                Image(systemName: symbol).font(.title3.weight(.semibold))
                Text(title).font(.caption.weight(.medium))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.brand)
    }

    // MARK: Цифры месяца

    private var statsRow: some View {
        HStack(spacing: 10) {
            stat("Расходы", Money.short(spent), "arrow.up.right", .primary)
            stat("Доходы", Money.short(income), "arrow.down.left", .income)
        }
    }

    private func stat(_ title: String, _ value: String, _ symbol: String, _ color: Color) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: symbol)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .foregroundStyle(color)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
            }
        }
    }

    // MARK: Маркетплейсы

    @ViewBuilder
    private var marketplaceCard: some View {
        let byMarket = monthExpenses.totalsByMarketplace()
        if !byMarket.isEmpty {
            let total = byMarket.reduce(Decimal(0)) { $0 + $1.total }
            let share = spent > 0 ? Int(((total / spent).doubleValue * 100).rounded()) : 0
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Label("Маркетплейсы", systemImage: "shippingbox.fill")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(share)% расходов")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(Money.short(total))
                        .font(.system(.title2, design: .rounded).weight(.bold))
                    // Полоса-разбивка по площадкам.
                    GeometryReader { geo in
                        HStack(spacing: 2) {
                            ForEach(byMarket, id: \.marketplace) { item in
                                item.marketplace.color
                                    .frame(width: max(4, geo.size.width * (item.total / total).doubleValue - 2))
                            }
                        }
                    }
                    .frame(height: 10)
                    .clipShape(Capsule())
                    HStack(spacing: 12) {
                        ForEach(byMarket.prefix(3), id: \.marketplace) { item in
                            HStack(spacing: 5) {
                                Circle().fill(item.marketplace.color).frame(width: 8, height: 8)
                                Text("\(item.marketplace.shortTitle) \(Money.short(item.total))")
                            }
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }
            }
        }
    }

    // MARK: Последние операции

    @ViewBuilder
    private var recent: some View {
        if entries.isEmpty {
            ContentUnavailableView {
                Label("Пока нет операций", systemImage: "tray")
            } description: {
                Text("Добавьте первую трату вручную или настройте автозаполнение во вкладке «Ещё».")
            }
            .padding(.top, 24)
        } else {
            SectionTitle(text: "Последние операции").padding(.top, 4)
            Card {
                VStack(spacing: 14) {
                    ForEach(entries.prefix(8)) { entry in
                        Button {
                            editing = entry
                        } label: {
                            EntryRow(entry: entry)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}
