import AppIntents
import Foundation
import SwiftData

/// Действие для автоматизации «Wallet / Транзакция» в приложении «Команды»:
/// после каждой оплаты Apple Pay iOS передаёт сюда сумму и продавца, и трата записывается сама.
struct LogExpenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Записать трату"
    static var description = IntentDescription("Добавляет трату в Грош. Подходит для автоматизации Wallet: передайте сумму и продавца из оплаты Apple Pay.")
    static var openAppWhenRun = false

    @Parameter(title: "Сумма", description: "Число или текст с суммой, например «1 234,56 ₽».")
    var amount: String

    @Parameter(title: "Продавец")
    var merchant: String?

    @Parameter(title: "Карта")
    var card: String?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let value = AmountParser.parse(amount), value != 0 else {
            return .result(dialog: "Не понял сумму «\(amount)».")
        }
        let name = (merchant ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = (card ?? "").filter { $0.isNumber }
        let txn = ParsedTxn(amount: abs(value),
                            currency: currency(in: amount),
                            date: Date(),
                            merchant: name.isEmpty ? "Apple Pay" : name,
                            cardTail: digits.count >= 4 ? String(digits.suffix(4)) : nil)
        // Apple Pay — первоисточник, поэтому дубликаты здесь не отсекаем: две одинаковые покупки за день реальны.
        ImportService.save([txn], source: .applePay, in: Persistence.container.mainContext, deduplicate: false)
        return .result(dialog: "Записал \(Money.string(txn.amount, code: txn.currency)) — \(txn.merchant)")
    }

    private func currency(in text: String) -> String {
        let t = text.uppercased()
        if t.contains("$") || t.contains("USD") { return "USD" }
        if t.contains("€") || t.contains("EUR") { return "EUR" }
        if t.contains("₸") || t.contains("KZT") { return "KZT" }
        if t.contains("£") || t.contains("GBP") { return "GBP" }
        return "RUB"
    }
}

/// Действие для автоматизации «Сообщение»: когда приходит SMS от банка,
/// iOS передаёт сюда его текст, и операция распознаётся автоматически.
struct ParseBankMessageIntent: AppIntent {
    static var title: LocalizedStringResource = "Разобрать сообщение банка"
    static var description = IntentDescription("Распознаёт сумму и продавца в тексте SMS банка и записывает операцию в Грош.")
    static var openAppWhenRun = false

    @Parameter(title: "Текст сообщения")
    var text: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let txn = BankMessageParser.parse(text) else {
            return .result(dialog: "В сообщении нет операции.")
        }
        let outcome = ImportService.save([txn], source: .sms, in: Persistence.container.mainContext)
        if outcome.added == 0 {
            return .result(dialog: "Эта операция уже записана.")
        }
        let sign = txn.isIncome ? "+" : ""
        return .result(dialog: "Записал \(sign)\(Money.string(txn.amount, code: txn.currency)) — \(txn.merchant)")
    }
}

struct GroshShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: LogExpenseIntent(),
                    phrases: ["Записать трату в \(.applicationName)", "Добавить расход в \(.applicationName)"],
                    shortTitle: "Записать трату",
                    systemImageName: "plus.circle.fill")
        AppShortcut(intent: ParseBankMessageIntent(),
                    phrases: ["Разобрать сообщение банка в \(.applicationName)"],
                    shortTitle: "Разобрать SMS банка",
                    systemImageName: "message.fill")
    }
}
