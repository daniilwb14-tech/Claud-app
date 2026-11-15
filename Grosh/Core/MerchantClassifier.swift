import Foundation

/// Определяет категорию и маркетплейс по названию продавца из банка/чека.
enum MerchantClassifier {

    /// Верхний регистр, Ё→Е, всё кроме букв/цифр/& заменяется пробелом.
    static func normalize(_ raw: String) -> String {
        let upper = raw.uppercased().replacingOccurrences(of: "Ё", with: "Е")
        var out = ""
        var lastWasSpace = true
        for ch in upper {
            if ch.isLetter || ch.isNumber || ch == "&" {
                out.append(ch)
                lastWasSpace = false
            } else if !lastWasSpace {
                out.append(" ")
                lastWasSpace = true
            }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    static func marketplace(for merchant: String) -> Marketplace? {
        let n = normalize(merchant)
        guard !n.isEmpty else { return nil }
        let tokens = Set(n.split(separator: " ").map(String.init))
        func has(_ keys: String...) -> Bool { keys.contains { n.contains($0) } }

        if has("YANDEX MARKET", "MARKET YANDEX", "YANDEXMARKET", "ЯНДЕКС МАРКЕТ", "YM MARKET") { return .yandexMarket }
        if has("MEGAMARKET", "МЕГАМАРКЕТ") { return .megamarket }
        if has("OZON", "ОЗОН") {
            // Ozon Банк — это переводы, а не покупки на маркетплейсе.
            return has("BANK", "БАНК") ? nil : .ozon
        }
        if has("WILDBERR", "ВАЙЛДБЕРРИ") || tokens.contains("WB") || tokens.contains("ВБ") { return .wildberries }
        if has("ALIEXPRESS", "ALI EXPRESS", "АЛИЭКСПРЕСС", "ALIBABA") { return .aliexpress }
        if has("LAMODA", "ЛАМОДА") { return .lamoda }
        return nil
    }

    private static let rules: [(SpendCategory, [String])] = [
        (.subscriptions, ["YANDEX PLUS", "ЯНДЕКС ПЛЮС", "NETFLIX", "SPOTIFY", "APPLE COM", "ITUNES", "KINOPOISK",
                          "КИНОПОИСК", "IVI", "OKKO", "YOUTUBE", "TELEGRAM", "VK MUSIC", "ICLOUD", "CHATGPT", "OPENAI"]),
        (.groceries, ["PYATEROCHKA", "ПЯТЕРОЧКА", "MAGNIT", "МАГНИТ", "PEREKRESTOK", "ПЕРЕКРЕСТОК", "LENTA", "ЛЕНТА",
                      "VKUSVILL", "ВКУСВИЛЛ", "AUCHAN", "АШАН", "SAMOKAT", "САМОКАТ", "DIXY", "ДИКСИ", "METRO C&C",
                      "METRO CASH", "LAVKA", "ЛАВКА", "AZBUKA VKUSA", "АЗБУКА ВКУСА", "SPAR", "OKEY", "ПРОДУКТЫ", "PRODUKTY"]),
        (.cafes, ["KFC", "BURGER", "ВКУСНО И ТОЧКА", "VKUSNO I TOCHKA", "DODO", "ДОДО", "COFFEE", "КОФЕ", "CAFE", "КАФЕ",
                  "RESTAURANT", "РЕСТОРАН", "SHOKOLADNICA", "ШОКОЛАДНИЦА", "YANDEX EDA", "ЯНДЕКС ЕДА", "DELIVERY CLUB",
                  "STARBUCKS", "PIZZA", "ПИЦЦА", "SUSHI", "СУШИ", "ROSTIC", "STOLOVAYA", "СТОЛОВАЯ", "BAKERY", "ПЕКАРНЯ"]),
        (.transport, ["YANDEX GO", "YANDEX TAXI", "ЯНДЕКС ТАКСИ", "ЯНДЕКС GO", "TAXI", "ТАКСИ", "UBER", "CITYMOBIL",
                      "MOSMETRO", "METRO", "МЕТРО", "LUKOIL", "ЛУКОЙЛ", "GAZPROMNEFT", "ГАЗПРОМНЕФТЬ", "ROSNEFT",
                      "РОСНЕФТЬ", "АЗС", "AZS", "PARKING", "ПАРКОВК", "TROIKA", "ТРОЙКА", "WHOOSH", "КАРШЕРИНГ", "DELIMOBIL"]),
        (.travel, ["RZD", "РЖД", "AEROFLOT", "АЭРОФЛОТ", "S7", "POBEDA", "ПОБЕДА", "AVIASALES", "BOOKING", "OSTROVOK",
                   "ОСТРОВОК", "HOTEL", "ОТЕЛЬ", "AIRBNB", "TUTU", "ТУТУ"]),
        (.health, ["APTEKA", "АПТЕКА", "CLINIC", "КЛИНИКА", "GORZDRAV", "ГОРЗДРАВ", "STOMAT", "СТОМАТ", "INVITRO",
                   "ИНВИТРО", "GEMOTEST", "ГЕМОТЕСТ", "PHARM", "ФАРМ", "MEDSI", "МЕДСИ"]),
        (.utilities, ["ЖКХ", "ZHKH", "MOSENERGO", "МОСЭНЕРГО", " MTS", "МТС", "BEELINE", "БИЛАЙН", "MEGAFON", "МЕГАФОН",
                      "TELE2", "ТЕЛЕ2", "ROSTELECOM", "РОСТЕЛЕКОМ", "GOSUSLUGI", "ГОСУСЛУГИ", "КВАРТПЛАТА", "ЭЛЕКТРО"]),
        (.electronics, ["DNS", "MVIDEO", "М ВИДЕО", "ELDORADO", "ЭЛЬДОРАДО", "CITILINK", "СИТИЛИНК", "RE STORE", "RESTORE"]),
        (.shopping, ["ZARA", "H&M", "GLORIA JEANS", "SPORTMASTER", "СПОРТМАСТЕР", "UNIQLO", "DECATHLON", "ДЕТСКИЙ МИР",
                     "DETSKY MIR", "LETUAL", "ЛЭТУАЛЬ", "ЗОЛОТОЕ ЯБЛОКО", "GOLD APPLE", "IKEA", "ЛЕМАНА", "LEROY"]),
        (.entertainment, ["KINO", "КИНО", "CINEMA", "STEAM", "PLAYSTATION", "XBOX", "THEATRE", "ТЕАТР", "CONCERT",
                          "КОНЦЕРТ", "KASSIR", "АФИША", "AFISHA", "BOWLING", "FITNESS", "ФИТНЕС"]),
        (.transfers, ["ПЕРЕВОД", "PEREVOD", "СБП", "SBP", "TRANSFER", "P2P"]),
    ]

    static func category(for merchant: String, isIncome: Bool = false) -> SpendCategory {
        let n = normalize(merchant)
        if isIncome {
            let salaryKeys = ["ЗАРПЛАТ", "ЗАРАБОТН", "SALARY", "PAYROLL", "АВАНС"]
            return salaryKeys.contains { n.contains($0) } ? .salary : .incomeOther
        }
        if marketplace(for: merchant) != nil { return .marketplaces }
        let padded = " " + n
        for (category, keys) in rules where keys.contains(where: { padded.contains($0) }) {
            return category
        }
        return .other
    }
}
