import StoreKit
import SwiftUI

struct PaywallView: View {
    @Environment(PurchaseManager.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var selectedID = PurchaseManager.yearlyID
    @State private var purchasing = false

    private let features: [(String, String, String)] = [
        ("shippingbox.fill", "Маркетплейсы под контролем", "Разбивка по Ozon, WB и AliExpress, средний чек, динамика за полгода."),
        ("tablecells.fill", "Импорт выписки банка", "Загрузите CSV из Т-Банка или Альфы — сотни операций за секунду, без дублей."),
        ("repeat", "Радар подписок", "Находит регулярные списания и показывает, сколько они съедают в год."),
        ("square.and.arrow.up.fill", "Экспорт в Excel", "Все операции одним файлом для своих таблиц."),
    ]

    private var selected: Product? { store.products.first { $0.id == selectedID } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    VStack(spacing: 10) {
                        Image(systemName: "rublesign.circle.fill")
                            .font(.system(size: 60))
                            .foregroundStyle(Color.brand)
                        Text("Грош Pro")
                            .font(.largeTitle.weight(.bold))
                        Text("Видеть, куда уходят деньги, — и тратить меньше.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 8)

                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(features, id: \.1) { feature in
                            HStack(alignment: .top, spacing: 14) {
                                IconBadge(symbol: feature.0, color: .brand, size: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(feature.1).font(.headline)
                                    Text(feature.2).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }

                    if store.isPro {
                        Label("Pro уже открыт. Спасибо!", systemImage: "checkmark.seal.fill")
                            .font(.headline)
                            .foregroundStyle(Color.brand)
                            .padding(.vertical, 12)
                    } else if store.products.isEmpty {
                        VStack(spacing: 10) {
                            if store.isLoading {
                                ProgressView()
                            } else {
                                Text(store.lastError ?? "Цены пока недоступны.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                Button("Повторить") { Task { await store.load() } }
                            }
                        }
                        .padding(.vertical, 12)
                    } else {
                        plans
                    }
                }
                .padding(20)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { dismiss() }
                }
            }
        }
    }

    private var plans: some View {
        VStack(spacing: 10) {
            ForEach(store.products, id: \.id) { product in
                Button {
                    selectedID = product.id
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 8) {
                                Text(title(for: product)).font(.headline)
                                if product.id == PurchaseManager.yearlyID {
                                    Text("Выгодно")
                                        .font(.caption2.weight(.bold))
                                        .padding(.horizontal, 7)
                                        .padding(.vertical, 3)
                                        .background(Color.brand, in: Capsule())
                                        .foregroundStyle(.white)
                                }
                            }
                            Text(subtitle(for: product))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(product.displayPrice)
                            .font(.system(.body, design: .rounded).weight(.bold))
                    }
                    .padding(16)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(product.id == selectedID ? Color.brand : Color.clear, lineWidth: 2)
                    }
                }
                .buttonStyle(.plain)
            }

            Button {
                guard let selected else { return }
                purchasing = true
                Task {
                    let done = await store.purchase(selected)
                    purchasing = false
                    if done { dismiss() }
                }
            } label: {
                Group {
                    if purchasing {
                        ProgressView().tint(.white)
                    } else {
                        Text(selectedID == PurchaseManager.lifetimeID ? "Купить навсегда" : "Оформить подписку")
                            .font(.headline)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .disabled(purchasing || selected == nil)
            .padding(.top, 6)

            if let error = store.lastError {
                Text(error).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
            }

            Button("Восстановить покупки") { Task { await store.restore() } }
                .font(.subheadline)

            Text("Подписка продлевается автоматически, пока её не отменить в настройках Apple ID не позднее чем за сутки до конца периода.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func title(for product: Product) -> String {
        switch product.id {
        case PurchaseManager.yearlyID: return "Год"
        case PurchaseManager.monthlyID: return "Месяц"
        default: return "Навсегда"
        }
    }

    private func subtitle(for product: Product) -> String {
        switch product.id {
        case PurchaseManager.yearlyID:
            let perMonth = product.price / 12
            return "≈ \(perMonth.formatted(product.priceFormatStyle)) в месяц"
        case PurchaseManager.monthlyID:
            return "Можно отменить в любой момент"
        default:
            return "Один платёж, без подписки"
        }
    }
}
