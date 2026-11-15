import Charts
import SwiftData
import SwiftUI

/// Траты по маркетплейсам: сколько уходит на Ozon, Wildberries, AliExpress и другие площадки.
struct MarketplacesView: View {
    @Environment(PurchaseManager.self) private var store
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]
    @State private var showPaywall = false

    private var calendar: Calendar { .current }
    private var marketEntries: [Entry] { entries.filter { !$0.isIncome && $0.marketplace != nil } }
    private var monthEntries: [Entry] { marketEntries.inMonth(of: Date()) }
    private var monthAllExpenses: Decimal { entries.inMonth(of: Date()).expenses.total }

    private struct MonthPoint: Identifiable {
        let id: String
        let month: Date
        let marketplace: Marketplace
        let total: Double
    }

    /// Последние 6 месяцев по площадкам — для столбчатой диаграммы.
    private var history: [MonthPoint] {
        guard let start = calendar.date(byAdding: .month, value: -5, to: Date()),
              let from = calendar.dateInterval(of: .month, for: start)?.start else { return [] }
        var buckets: [Date: [Marketplace: Decimal]] = [:]
        for entry in marketEntries where entry.date >= from {
            guard let market = entry.marketplace,
                  let month = calendar.dateInterval(of: .month, for: entry.date)?.start else { continue }
            buckets[month, default: [:]][market, default: 0] += entry.amount
        }
        return buckets.flatMap { month, markets in
            markets.map { MonthPoint(id: "\(month.timeIntervalSince1970)-\($0.key.rawValue)", month: month, marketplace: $0.key, total: $0.value.doubleValue) }
        }
        .sorted { $0.month < $1.month }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if marketEntries.isEmpty {
                    ContentUnavailableView {
                        Label("Покупок на маркетплейсах пока нет", systemImage: "shippingbox")
                    } description: {
                        Text("Грош сам узнаёт Ozon, Wildberries, AliExpress, Яндекс Маркет, Мегамаркет и Lamoda по названию продавца в операции.")
                    }
                    .padding(.top, 60)
                } else {
                    VStack(spacing: 16) {
                        summary
                        if store.isPro {
                            details
                        } else {
                            details
                                .blur(radius: 9)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                                .overlay(alignment: .top) { locked }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Маркетплейсы")
            .sheet(isPresented: $showPaywall) { PaywallView() }
        }
    }

    private var summary: some View {
        let total = monthEntries.total
        let share = monthAllExpenses > 0 ? Int(((total / monthAllExpenses).doubleValue * 100).rounded()) : 0
        return Card {
            VStack(alignment: .leading, spacing: 8) {
                Text("В этом месяце").font(.subheadline).foregroundStyle(.secondary)
                Text(Money.short(total))
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text("\(share)% всех расходов · покупок: \(monthEntries.count)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var locked: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.fill").font(.title2).foregroundStyle(Color.brand)
            Text("Разбивка по площадкам — в Грош Pro")
                .font(.headline)
                .multilineTextAlignment(.center)
            Text("Средний чек, динамика за полгода и самые дорогие покупки на каждой площадке.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Открыть Pro") { showPaywall = true }
                .buttonStyle(.borderedProminent)
        }
        .padding(20)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.top, 40)
        .padding(.horizontal, 12)
    }

    private var details: some View {
        VStack(spacing: 16) {
            ForEach(monthEntries.totalsByMarketplace(), id: \.marketplace) { item in
                Card {
                    HStack(spacing: 12) {
                        Text(String(item.marketplace.title.prefix(1)))
                            .font(.system(.title3, design: .rounded).weight(.heavy))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(item.marketplace.color, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.marketplace.title).font(.headline)
                            Text("Покупок: \(item.count) · средний чек \(Money.short(item.total / Decimal(item.count)))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(Money.short(item.total))
                            .font(.system(.body, design: .rounded).weight(.bold))
                            .monospacedDigit()
                    }
                }
            }

            Card {
                VStack(alignment: .leading, spacing: 16) {
                    Text("За полгода").font(.headline)
                    Chart(history) { point in
                        BarMark(x: .value("Месяц", point.month, unit: .month),
                                y: .value("Сумма", point.total))
                            .foregroundStyle(point.marketplace.color)
                            .cornerRadius(3)
                    }
                    .chartXAxis {
                        AxisMarks(values: .stride(by: .month)) { _ in
                            AxisValueLabel(format: .dateTime.month(.abbreviated))
                        }
                    }
                    .frame(height: 180)

                    // Легенда словами: цвет не единственный носитель смысла.
                    HStack(spacing: 12) {
                        ForEach(Marketplace.allCases.filter { market in history.contains { $0.marketplace == market } }) { market in
                            HStack(spacing: 5) {
                                Circle().fill(market.color).frame(width: 8, height: 8)
                                Text(market.shortTitle)
                            }
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }

            Card {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Самые крупные покупки").font(.headline)
                    ForEach(marketEntries.sorted { $0.amount > $1.amount }.prefix(5)) { entry in
                        EntryRow(entry: entry)
                    }
                }
            }
        }
    }
}
