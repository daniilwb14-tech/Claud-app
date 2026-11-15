import Foundation
import Observation
import StoreKit

/// Покупки «Грош Pro» через StoreKit 2: подписка (месяц/год) и разовая покупка навсегда.
@MainActor
@Observable
final class PurchaseManager {
    static let shared = PurchaseManager()

    static let monthlyID = "grosh.pro.monthly"
    static let yearlyID = "grosh.pro.yearly"
    static let lifetimeID = "grosh.pro.lifetime"
    static let allIDs = [yearlyID, monthlyID, lifetimeID]

    private(set) var products: [Product] = []
    private(set) var entitled = false
    private(set) var isLoading = false
    var lastError: String?

    /// Переключатель для разработки: открывает Pro без покупки (только в Debug-сборке).
    var debugPro = UserDefaults.standard.bool(forKey: "debugPro") {
        didSet { UserDefaults.standard.set(debugPro, forKey: "debugPro") }
    }

    var isPro: Bool {
        #if DEBUG
        return entitled || debugPro
        #else
        return entitled
        #endif
    }

    private var updates: Task<Void, Never>?

    private init() {
        updates = Task { [weak self] in
            for await _ in StoreKit.Transaction.updates {
                await self?.refreshEntitlements()
            }
        }
        Task { await load() }
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let loaded = try await Product.products(for: Self.allIDs)
            products = Self.allIDs.compactMap { id in loaded.first { $0.id == id } }
            lastError = products.isEmpty ? "Цены пока недоступны. Проверьте подключение к интернету." : nil
        } catch {
            lastError = "Не удалось загрузить цены: \(error.localizedDescription)"
        }
        await refreshEntitlements()
    }

    func refreshEntitlements() async {
        var active = false
        for await result in StoreKit.Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               Self.allIDs.contains(transaction.productID),
               transaction.revocationDate == nil {
                active = true
            }
        }
        entitled = active
    }

    /// Возвращает `true`, если покупка прошла и Pro открыт.
    func purchase(_ product: Product) async -> Bool {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                    await refreshEntitlements()
                    return isPro
                }
                lastError = "Покупку не удалось подтвердить."
            case .pending:
                lastError = "Покупка ожидает подтверждения."
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            lastError = "Покупка не удалась: \(error.localizedDescription)"
        }
        return false
    }

    func restore() async {
        do {
            try await AppStore.sync()
        } catch {
            lastError = "Не удалось восстановить покупки."
        }
        await refreshEntitlements()
    }
}
