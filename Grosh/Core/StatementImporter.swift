import Foundation

/// Импорт банковской выписки в CSV (Т-Банк, Альфа, любые выгрузки с колонками дата/сумма/описание).
enum StatementImporter {

    struct Result: Equatable {
        var txns: [ParsedTxn] = []
        var skippedRows = 0
    }

    /// Выписки российских банков часто приходят в Windows-1251.
    static func decode(_ data: Data) -> String? {
        if let s = String(data: data, encoding: .utf8) {
            return s.hasPrefix("\u{FEFF}") ? String(s.dropFirst()) : s
        }
        return String(data: data, encoding: .windowsCP1251)
    }

    static func parse(csv text: String, calendar: Calendar = .current) -> Result {
        let lines = text.split(whereSeparator: { $0 == "\n" || $0 == "\r\n" || $0 == "\r" }).map(String.init)
        guard let headerLine = lines.first, lines.count > 1 else { return Result() }

        let delimiter = detectDelimiter(headerLine)
        let header = splitRow(headerLine, delimiter: delimiter).map { $0.lowercased() }

        func column(_ keys: [String]) -> Int? {
            for key in keys {
                if let i = header.firstIndex(where: { $0.contains(key) }) { return i }
            }
            return nil
        }

        let dateCol = column(["дата операции", "дата", "date", "time"])
        let amountCol = column(["сумма операции", "сумма платежа", "сумма", "amount", "sum"])
        let expenseCol = column(["расход", "списан", "debit", "withdraw"])
        let incomeCol = column(["приход", "зачисл", "credit", "deposit"])
        let descCol = column(["описание", "description", "merchant", "назначение", "контрагент", "payee", "details", "name"])
        let currencyCol = column(["валюта операции", "валюта", "currency"])
        let statusCol = column(["статус", "status"])
        let cardCol = column(["номер карты", "card"])
        let typeCol = column(["тип", "type"])

        guard let dateCol, amountCol != nil || expenseCol != nil || incomeCol != nil else { return Result() }

        var result = Result()
        var sawNegative = false
        var rows: [(txn: ParsedTxn, signed: Decimal, type: String)] = []

        for line in lines.dropFirst() {
            let cells = splitRow(line, delimiter: delimiter)
            func cell(_ i: Int?) -> String {
                guard let i, i < cells.count else { return "" }
                return cells[i].trimmingCharacters(in: .whitespaces)
            }

            let status = cell(statusCol).lowercased()
            if status.contains("fail") || status.contains("отмен") || status.contains("declin") || status.contains("отклон") {
                result.skippedRows += 1
                continue
            }
            guard let date = parseDate(cell(dateCol), calendar: calendar) else {
                result.skippedRows += 1
                continue
            }

            var signed: Decimal?
            if let amountCol { signed = AmountParser.parse(cell(amountCol)) }
            if signed == nil, let v = AmountParser.parse(cell(expenseCol)), v != 0 { signed = -abs(v) }
            if signed == nil, let v = AmountParser.parse(cell(incomeCol)), v != 0 { signed = abs(v) }
            guard let signed, signed != 0 else {
                result.skippedRows += 1
                continue
            }
            if signed < 0 { sawNegative = true }

            let desc = cell(descCol)
            let currency = cell(currencyCol).uppercased()
            let cardDigits = cell(cardCol).filter { $0.isNumber }
            let txn = ParsedTxn(amount: abs(signed),
                                currency: currency.count == 3 ? (currency == "RUR" ? "RUB" : currency) : "RUB",
                                date: date,
                                merchant: desc.isEmpty ? "Операция" : desc,
                                isIncome: signed > 0,
                                cardTail: cardDigits.count >= 4 ? String(cardDigits.suffix(4)) : nil)
            rows.append((txn, signed, cell(typeCol).lowercased()))
        }

        // Если банк выгружает все суммы без знака — считаем расходами всё, кроме явно помеченных поступлений.
        let incomeMarks = ["доход", "приход", "пополн", "зачисл", "income", "credit", "deposit"]
        result.txns = rows.map { row in
            var txn = row.txn
            if !sawNegative {
                txn.isIncome = incomeMarks.contains { row.type.contains($0) }
            }
            return txn
        }
        return result
    }

    static func detectDelimiter(_ line: String) -> Character {
        let candidates: [Character] = [";", ",", "\t"]
        return candidates.max { a, b in
            line.filter { $0 == a }.count < line.filter { $0 == b }.count
        } ?? ";"
    }

    /// Разбивает строку CSV с учётом кавычек и экранирования «""».
    static func splitRow(_ line: String, delimiter: Character) -> [String] {
        var cells: [String] = []
        var current = ""
        var inQuotes = false
        var i = line.startIndex
        while i < line.endIndex {
            let ch = line[i]
            if ch == "\"" {
                let next = line.index(after: i)
                if inQuotes, next < line.endIndex, line[next] == "\"" {
                    current.append("\"")
                    i = next
                } else {
                    inQuotes.toggle()
                }
            } else if ch == delimiter && !inQuotes {
                cells.append(current)
                current = ""
            } else {
                current.append(ch)
            }
            i = line.index(after: i)
        }
        cells.append(current)
        return cells
    }

    private static let dateFormats = [
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
        "dd/MM/yyyy", "dd.MM.yy",
    ]

    static func parseDate(_ raw: String, calendar: Calendar = .current) -> Date? {
        let s = raw.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        for format in dateFormats {
            formatter.dateFormat = format
            if let d = formatter.date(from: s) { return d }
        }
        return nil
    }
}
