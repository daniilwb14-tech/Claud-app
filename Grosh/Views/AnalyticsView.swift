import Charts
import SwiftData
import SwiftUI

struct AnalyticsView: View {
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]
    @AppStorage(Prefs.hourlyRate) private var hourlyRate: Double = 0
    @State private var month = Date()

    private var calendar: Calendar { .current }
    private var expenses: [Entry] { entries.inMonth(of: month).expenses }
    private var total: Decimal { expenses.total }

    private var previousTotal: Decimal {
        guard let previous = calendar.date(byAdding: .month, value: -1, to: month) else { return 0 }
        return entries.inMonth(of: previous).expenses.total
    }

    private var isCurrentMonth: Bool { calendar.isDate(month, equalTo: Date(), toGranularity: .month) }

    private struct DayPoint: Identifiable {
        let id: Date
        let total: Double
    }

    private var dayPoints: [DayPoint] {
        Dictionary(grouping: expenses) { calendar.startOfDay(for: $0.date) }
            .map { DayPoint(id: $0.key, total: $0.value.total.doubleValue) }
            .sorted { $0.id < $1.id }
    }

    private var topMerchants: [(name: String, total: Decimal, count: Int)] {
        Dictionary(grouping: expenses) { $0.title }
            .map { (name: $0.key, total: $0.value.total, count: $0.value.count) }
            .sorted { $0.total > $1.total }
            .prefix(5)
            .map { $0 }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    monthSwitcher
                    if expenses.isEmpty {
                        ContentUnavailableView("Нет расходов за месяц", systemImage: "chart.pie", description: Text("Добавьте операции, и здесь появится разбивка."))
                            .padding(.top, 40)
                    } else {
                        summary
                        categories
                        daily
                        merchants
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Аналитика")
        }
    }

    private var monthSwitcher: some View {
        HStack {
            Button {
                shift(-1)
            } label: {
                Image(systemName: "chevron.left").frame(width: 44, height: 44)
            }
            .accessibilityLabel("Предыдущий месяц")
            Spacer()
            Text(month.monthTitle).font(.headline)
            Spacer()
            Button {
                shift(1)
            } label: {
                Image(systemName: "chevron.right").frame(width: 44, height: 44)
            }
            .disabled(isCurrentMonth)
            .accessibilityLabel("Следующий месяц")
        }
    }

    private func shift(_ delta: Int) {
        if let next = calendar.date(byAdding: .month, value: delta, to: month) {
            withAnimation { month = next }
        }
    }

    private var summary: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text("Расходы за месяц").font(.subheadline).foregroundStyle(.secondary)
                Text(Money.short(total))
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                if previousTotal > 0 {
                    let change = ((total - previousTotal) / previousTotal).doubleValue * 100
                    Label("\(change >= 0 ? "+" : "−")\(Int(abs(change).rounded()))% к прошлому месяцу",
                          systemImage: change >= 0 ? "arrow.up.right" : "arrow.down.right")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(change > 0 ? Color.orange : Color.income)
                }
                if let hours = Insights.workHoursText(amount: total, hourlyRate: Decimal(hourlyRate)) {
                    Label("Это \(hours)", systemImage: "clock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var categories: some View {
        let slices = expenses.totalsByCategory()
        return Card {
            VStack(alignment: .leading, spacing: 16) {
                Text("По категориям").font(.headline)
                Chart(slices, id: \.category) { slice in
                    SectorMark(angle: .value("Сумма", slice.total.doubleValue),
                               innerRadius: .ratio(0.62),
                               angularInset: 2)
                        .cornerRadius(5)
                        .foregroundStyle(slice.category.color)
                }
                .frame(height: 200)

                VStack(spacing: 12) {
                    ForEach(slices, id: \.category) { slice in
                        HStack(spacing: 10) {
                            IconBadge(symbol: slice.category.icon, color: slice.category.color, size: 32)
                            Text(slice.category.title)
                            Spacer()
                            Text("\(Int(((slice.total / total).doubleValue * 100).rounded()))%")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Text(Money.short(slice.total))
                                .font(.system(.body, design: .rounded).weight(.semibold))
                                .monospacedDigit()
                        }
                    }
                }
            }
        }
    }

    private var daily: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                Text("По дням").font(.headline)
                Chart(dayPoints) { point in
                    BarMark(x: .value("День", point.id, unit: .day),
                            y: .value("Сумма", point.total))
                        .foregroundStyle(Color.brand)
                        .cornerRadius(3)
                }
                .frame(height: 160)
            }
        }
    }

    private var merchants: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                Text("Куда уходит больше всего").font(.headline)
                ForEach(topMerchants, id: \.name) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).lineLimit(1)
                            Text("Покупок: \(item.count)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(Money.short(item.total))
                            .font(.system(.body, design: .rounded).weight(.semibold))
                            .monospacedDigit()
                    }
                }
            }
        }
    }
}
