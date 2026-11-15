import SwiftData
import SwiftUI

@main
struct GroshApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(PurchaseManager.shared)
                .tint(.brand)
        }
        .modelContainer(Persistence.container)
    }
}

struct RootView: View {
    @AppStorage(Prefs.onboarded) private var onboarded = false

    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Главная", systemImage: "house.fill") }
            HistoryView()
                .tabItem { Label("История", systemImage: "list.bullet") }
            AnalyticsView()
                .tabItem { Label("Аналитика", systemImage: "chart.pie.fill") }
            MarketplacesView()
                .tabItem { Label("Маркеты", systemImage: "shippingbox.fill") }
            MoreView()
                .tabItem { Label("Ещё", systemImage: "ellipsis.circle.fill") }
        }
        .sheet(isPresented: .constant(!onboarded)) {
            OnboardingView()
                .interactiveDismissDisabled()
        }
    }
}

struct OnboardingView: View {
    @Environment(\.modelContext) private var context
    @AppStorage(Prefs.onboarded) private var onboarded = false
    @AppStorage(Prefs.budget) private var budget: Double = 60000

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer()
            Image(systemName: "rublesign.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.brand)
            Text("Грош считает траты за вас")
                .font(.largeTitle.weight(.bold))

            VStack(alignment: .leading, spacing: 18) {
                feature("wave.3.right.circle.fill", "Сам записывает покупки", "Оплаты Apple Pay и SMS банка попадают в приложение без вашего участия.")
                feature("shippingbox.fill", "Видит маркетплейсы", "Отдельно показывает, сколько уходит на Ozon, Wildberries и AliExpress.")
                feature("gauge.with.needle.fill", "Говорит, сколько можно сегодня", "Один понятный лимит на день вместо сложных таблиц.")
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Бюджет на месяц")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                TextField("60000", value: $budget, format: .number)
                    .keyboardType(.numberPad)
                    .font(.system(.title, design: .rounded).weight(.bold))
                    .padding(14)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            Spacer()

            VStack(spacing: 10) {
                Button {
                    onboarded = true
                } label: {
                    Text("Начать")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)

                Button("Посмотреть на демо-данных") {
                    ImportService.insertDemoData(in: context)
                    onboarded = true
                }
                .font(.subheadline)
            }
        }
        .padding(24)
    }

    private func feature(_ symbol: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            IconBadge(symbol: symbol, color: .brand, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}
