import Foundation
import SwiftData

/// Одна операция: трата или поступление.
@Model
final class Entry {
    var id: UUID = UUID()
    /// Всегда положительная; знак задаёт `isIncome`.
    var amount: Decimal = 0
    var currency: String = "RUB"
    var date: Date = Date()
    var merchant: String = ""
    var note: String = ""
    var isIncome: Bool = false
    var categoryRaw: String = "other"
    var marketplaceRaw: String?
    var sourceRaw: String = "manual"
    var cardTail: String?

    init(amount: Decimal,
         currency: String = "RUB",
         date: Date = Date(),
         merchant: String,
         note: String = "",
         isIncome: Bool = false,
         category: SpendCategory,
         marketplace: Marketplace? = nil,
         source: EntrySource = .manual,
         cardTail: String? = nil) {
        self.id = UUID()
        self.amount = amount
        self.currency = currency
        self.date = date
        self.merchant = merchant
        self.note = note
        self.isIncome = isIncome
        self.categoryRaw = category.rawValue
        self.marketplaceRaw = marketplace?.rawValue
        self.sourceRaw = source.rawValue
        self.cardTail = cardTail
    }

    /// Запись из распознанной операции: категория и маркетплейс определяются автоматически.
    convenience init(_ txn: ParsedTxn, source: EntrySource) {
        self.init(amount: txn.amount,
                  currency: txn.currency,
                  date: txn.date,
                  merchant: txn.merchant,
                  isIncome: txn.isIncome,
                  category: MerchantClassifier.category(for: txn.merchant, isIncome: txn.isIncome),
                  marketplace: txn.isIncome ? nil : MerchantClassifier.marketplace(for: txn.merchant),
                  source: source,
                  cardTail: txn.cardTail)
    }

    var category: SpendCategory {
        get { SpendCategory(rawValue: categoryRaw) ?? .other }
        set { categoryRaw = newValue.rawValue }
    }

    var marketplace: Marketplace? {
        get { marketplaceRaw.flatMap(Marketplace.init(rawValue:)) }
        set { marketplaceRaw = newValue?.rawValue }
    }

    var source: EntrySource {
        get { EntrySource(rawValue: sourceRaw) ?? .manual }
        set { sourceRaw = newValue.rawValue }
    }

    var title: String { merchant.isEmpty ? category.title : merchant }
}

enum Persistence {
    /// Общий контейнер: им пользуются и экраны, и команды Shortcuts (App Intents).
    static let container: ModelContainer = {
        do {
            return try ModelContainer(for: Entry.self)
        } catch {
            fatalError("Не удалось открыть базу данных: \(error)")
        }
    }()
}

enum Prefs {
    static let budget = "monthlyBudget"
    static let hourlyRate = "hourlyRate"
    static let onboarded = "onboarded"
}
