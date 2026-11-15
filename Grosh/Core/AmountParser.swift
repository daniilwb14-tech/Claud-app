import Foundation

/// Разбор денежных сумм в любом написании: «1 234,56», «1,234.56», «-349,50», «₽1 500», «$12.34».
enum AmountParser {

    /// Возвращает сумму со знаком. `nil`, если в тексте нет числа.
    static func parse(_ text: String) -> Decimal? {
        var negative = false
        var body = ""
        for ch in text {
            if ch.isNumber, ch.isASCII {
                body.append(ch)
            } else if ch == "." || ch == "," {
                body.append(ch)
            } else if (ch == "-" || ch == "−" || ch == "–") && body.isEmpty {
                negative = true
            }
        }
        // Хвостовые разделители («500 р.») не являются частью числа.
        while let last = body.last, last == "." || last == "," { body.removeLast() }
        while let first = body.first, first == "." || first == "," { body.removeFirst() }
        guard !body.isEmpty, let value = normalized(body) else { return nil }
        return negative ? -value : value
    }

    private static func normalized(_ raw: String) -> Decimal? {
        var s = raw
        let hasDot = s.contains("."), hasComma = s.contains(",")
        if hasDot && hasComma {
            // Десятичным считается тот разделитель, который стоит последним.
            if s.lastIndex(of: ".")! > s.lastIndex(of: ",")! {
                s.removeAll { $0 == "," }
            } else {
                s.removeAll { $0 == "." }
                s = s.replacingOccurrences(of: ",", with: ".")
            }
        } else if hasComma {
            let parts = s.split(separator: ",", omittingEmptySubsequences: false)
            if parts.count == 2 && parts[1].count != 3 {
                s = String(parts[0]) + "." + String(parts[1])
            } else {
                s.removeAll { $0 == "," }
            }
        } else if hasDot {
            let parts = s.split(separator: ".", omittingEmptySubsequences: false)
            if parts.count > 2 { s.removeAll { $0 == "." } }
        }
        return Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))
    }
}
