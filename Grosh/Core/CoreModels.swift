import Foundation

/// Маркетплейсы, траты по которым приложение выделяет в отдельную аналитику.
enum Marketplace: String, CaseIterable, Identifiable, Codable {
    case ozon, wildberries, aliexpress, yandexMarket, megamarket, lamoda

    var id: String { rawValue }

    var title: String {
        switch self {
        case .ozon: return "Ozon"
        case .wildberries: return "Wildberries"
        case .aliexpress: return "AliExpress"
        case .yandexMarket: return "Яндекс Маркет"
        case .megamarket: return "Мегамаркет"
        case .lamoda: return "Lamoda"
        }
    }

    var shortTitle: String {
        switch self {
        case .wildberries: return "WB"
        case .aliexpress: return "Ali"
        case .yandexMarket: return "Маркет"
        default: return title
        }
    }

    /// Фирменный цвет площадки (RGB).
    var colorHex: UInt32 {
        switch self {
        case .ozon: return 0x005BFF
        case .wildberries: return 0xCB11AB
        case .aliexpress: return 0xE8402A
        case .yandexMarket: return 0xF5B800
        case .megamarket: return 0x21A038
        case .lamoda: return 0x555555
        }
    }
}

enum SpendCategory: String, CaseIterable, Identifiable, Codable {
    case groceries, cafes, transport, marketplaces, shopping, electronics
    case utilities, health, entertainment, subscriptions, travel, transfers, other
    case salary, incomeOther

    var id: String { rawValue }

    var isIncome: Bool { self == .salary || self == .incomeOther }

    static var expenseCases: [SpendCategory] { allCases.filter { !$0.isIncome } }
    static var incomeCases: [SpendCategory] { allCases.filter { $0.isIncome } }

    var title: String {
        switch self {
        case .groceries: return "Продукты"
        case .cafes: return "Кафе и еда"
        case .transport: return "Транспорт"
        case .marketplaces: return "Маркетплейсы"
        case .shopping: return "Одежда"
        case .electronics: return "Техника"
        case .utilities: return "ЖКХ и связь"
        case .health: return "Здоровье"
        case .entertainment: return "Развлечения"
        case .subscriptions: return "Подписки"
        case .travel: return "Путешествия"
        case .transfers: return "Переводы"
        case .other: return "Другое"
        case .salary: return "Зарплата"
        case .incomeOther: return "Другой доход"
        }
    }

    /// Имя SF Symbol.
    var icon: String {
        switch self {
        case .groceries: return "cart.fill"
        case .cafes: return "fork.knife"
        case .transport: return "car.fill"
        case .marketplaces: return "shippingbox.fill"
        case .shopping: return "tshirt.fill"
        case .electronics: return "desktopcomputer"
        case .utilities: return "house.fill"
        case .health: return "cross.case.fill"
        case .entertainment: return "gamecontroller.fill"
        case .subscriptions: return "repeat"
        case .travel: return "airplane"
        case .transfers: return "arrow.left.arrow.right"
        case .other: return "square.grid.2x2.fill"
        case .salary: return "banknote.fill"
        case .incomeOther: return "plus.circle.fill"
        }
    }

    var colorHex: UInt32 {
        switch self {
        case .groceries: return 0x34C759
        case .cafes: return 0xFF9500
        case .transport: return 0x0A84FF
        case .marketplaces: return 0xBF5AF2
        case .shopping: return 0xFF2D55
        case .electronics: return 0x5E5CE6
        case .utilities: return 0x8E8E93
        case .health: return 0xFF453A
        case .entertainment: return 0xFFD60A
        case .subscriptions: return 0x64D2FF
        case .travel: return 0x30B0C7
        case .transfers: return 0xAC8E68
        case .other: return 0x98989D
        case .salary: return 0x00B37A
        case .incomeOther: return 0x00B37A
        }
    }
}

/// Откуда запись попала в приложение.
enum EntrySource: String, CaseIterable, Codable {
    case manual, applePay, sms, paste, csv, qr, demo

    var title: String {
        switch self {
        case .manual: return "Вручную"
        case .applePay: return "Apple Pay"
        case .sms: return "SMS банка"
        case .paste: return "Из сообщения"
        case .csv: return "Выписка"
        case .qr: return "Чек по QR"
        case .demo: return "Демо"
        }
    }

    var icon: String {
        switch self {
        case .manual: return "hand.tap.fill"
        case .applePay: return "wave.3.right.circle.fill"
        case .sms: return "message.fill"
        case .paste: return "doc.on.clipboard.fill"
        case .csv: return "tablecells.fill"
        case .qr: return "qrcode"
        case .demo: return "sparkles"
        }
    }

    var isAutomatic: Bool { self == .applePay || self == .sms || self == .csv }
}

/// Операция, распознанная из любого внешнего источника (SMS, выписка, QR, Apple Pay).
struct ParsedTxn: Equatable {
    var amount: Decimal
    var currency: String = "RUB"
    var date: Date
    var merchant: String
    var isIncome: Bool = false
    var cardTail: String? = nil
}

extension Decimal {
    var doubleValue: Double { NSDecimalNumber(decimal: self).doubleValue }
}
