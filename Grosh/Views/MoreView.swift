import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct MoreView: View {
    @Environment(\.modelContext) private var context
    @Environment(PurchaseManager.self) private var store
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]
    @AppStorage(Prefs.budget) private var budget: Double = 60000
    @AppStorage(Prefs.hourlyRate) private var hourlyRate: Double = 0
    @State private var showPaywall = false
    @State private var sheet: QuickSheet?
    @State private var confirmDelete = false
    @State private var exportURL: URL?

    var body: some View {
        NavigationStack {
            List {
                if !store.isPro {
                    Section {
                        Button {
                            showPaywall = true
                        } label: {
                            HStack(spacing: 14) {
                                IconBadge(symbol: "sparkles", color: .brand, size: 44)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Грош Pro").font(.headline).foregroundStyle(Color.primary)
                                    Text("Маркетплейсы, импорт выписок, радар подписок")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                            }
                        }
                    }
                }

                Section {
                    NavigationLink {
                        GuideView(guide: .applePay)
                    } label: {
                        row("Apple Pay и Wallet", "wave.3.right.circle.fill", .blue)
                    }
                    NavigationLink {
                        GuideView(guide: .sms)
                    } label: {
                        row("SMS от банка", "message.fill", .green)
                    }
                    proLink("Импорт выписки (CSV)", "tablecells.fill", .orange) { StatementImportView() }
                    Button {
                        sheet = .paste
                    } label: {
                        row("Вставить текст сообщения", "doc.on.clipboard.fill", .indigo)
                    }
                    Button {
                        sheet = .scan
                    } label: {
                        row("Скан QR-кода с чека", "qrcode.viewfinder", .teal)
                    }
                } header: {
                    Text("Автозаполнение")
                } footer: {
                    Text("Настройте один раз — и покупки будут записываться сами.")
                }

                Section("Инструменты") {
                    proLink("Радар подписок", "repeat", .cyan) { SubscriptionsView() }
                    if store.isPro {
                        if let exportURL {
                            ShareLink(item: exportURL) {
                                row("Поделиться файлом экспорта", "square.and.arrow.up.fill", .gray)
                            }
                        } else {
                            Button {
                                exportURL = ImportService.exportCSV(entries)
                            } label: {
                                row("Экспорт в CSV", "square.and.arrow.up.fill", .gray)
                            }
                            .disabled(entries.isEmpty)
                        }
                    } else {
                        Button {
                            showPaywall = true
                        } label: {
                            row("Экспорт в CSV", "square.and.arrow.up.fill", .gray, locked: true)
                        }
                    }
                }

                Section {
                    LabeledContent("Бюджет на месяц, ₽") {
                        TextField("60000", value: $budget, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Доход за час работы, ₽") {
                        TextField("0", value: $hourlyRate, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                } header: {
                    Text("Бюджет")
                } footer: {
                    Text("Если указать доход за час, Грош покажет, сколько часов работы стоит каждая покупка.")
                }

                Section("Данные") {
                    Button("Добавить демо-данные") {
                        ImportService.insertDemoData(in: context)
                    }
                    Button("Удалить все операции", role: .destructive) {
                        confirmDelete = true
                    }
                    .disabled(entries.isEmpty)
                }

                #if DEBUG
                Section("Разработка") {
                    Toggle("Pro без покупки", isOn: Binding(get: { store.debugPro }, set: { store.setDebugPro($0) }))
                }
                #endif

                Section {
                    LabeledContent("Операций в базе", value: "\(entries.count)")
                    LabeledContent("Версия", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                } footer: {
                    Text("Все данные хранятся только на этом устройстве.")
                }
            }
            .navigationTitle("Ещё")
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .sheet(item: $sheet) { which in
                switch which {
                case .add: AddEntryView()
                case .paste: SmartPasteView()
                case .scan: ScanReceiptView()
                }
            }
            .confirmationDialog("Удалить все операции?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Удалить всё", role: .destructive) {
                    ImportService.deleteAll(in: context)
                }
            } message: {
                Text("Это действие нельзя отменить.")
            }
            .onChange(of: entries.count) { _, _ in exportURL = nil }
        }
    }

    private func row(_ title: String, _ symbol: String, _ color: Color, locked: Bool = false) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(color, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            Text(title).foregroundStyle(Color.primary)
            if locked {
                Spacer()
                Text("Pro")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.brand.opacity(0.15), in: Capsule())
                    .foregroundStyle(Color.brand)
            }
        }
    }

    /// Пункт, который в бесплатной версии открывает экран покупки.
    @ViewBuilder
    private func proLink<Destination: View>(_ title: String, _ symbol: String, _ color: Color,
                                            @ViewBuilder destination: @escaping () -> Destination) -> some View {
        if store.isPro {
            NavigationLink(destination: destination) {
                row(title, symbol, color)
            }
        } else {
            Button {
                showPaywall = true
            } label: {
                row(title, symbol, color, locked: true)
            }
        }
    }
}

// MARK: Инструкции по автоматизации

enum Guide {
    case applePay, sms

    var title: String { self == .applePay ? "Apple Pay и Wallet" : "SMS от банка" }

    var intro: String {
        switch self {
        case .applePay:
            return "iOS умеет запускать действие после каждой оплаты картой из Wallet. Грош получает сумму и название магазина и сам записывает трату — открывать приложение не нужно."
        case .sms:
            return "iOS не даёт приложениям читать SMS напрямую, но может передать текст сообщения банка в Грош через автоматизацию. После настройки каждая покупка и зачисление записываются сами."
        }
    }

    var steps: [String] {
        switch self {
        case .applePay:
            return [
                "Откройте приложение «Команды» → вкладка «Автоматизация» → «+».",
                "Выберите «Wallet» (в iOS 17 — «Транзакция») и отметьте карты, по которым вести учёт.",
                "Включите «Запускать немедленно» и выключите «Уведомлять при запуске».",
                "Нажмите «Новая пустая автоматизация» → «Добавить действие» → найдите «Записать трату» (Грош).",
                "В поле «Сумма» выберите «Ввод команды» → «Сумма». В поле «Продавец» — «Ввод команды» → «Продавец».",
                "Готово. Оплатите что-нибудь через Apple Pay — трата появится в Грош.",
            ]
        case .sms:
            return [
                "Откройте приложение «Команды» → вкладка «Автоматизация» → «+».",
                "Выберите «Сообщение». В «Отправитель» укажите номер банка (Сбер — 900, Т-Банк — T-Bank, Альфа — Alfa-Bank).",
                "Включите «Запускать немедленно» и выключите «Уведомлять при запуске».",
                "Нажмите «Новая пустая автоматизация» → «Добавить действие» → найдите «Разобрать сообщение банка» (Грош).",
                "В поле «Текст сообщения» выберите «Ввод команды» → «Содержимое».",
                "Готово. Повторите для каждого банка, от которого приходят SMS.",
            ]
        }
    }

    var note: String {
        switch self {
        case .applePay:
            return "Работает с картами, добавленными в Apple Wallet. Если Apple Pay с вашей картой недоступен, используйте SMS от банка или импорт выписки."
        case .sms:
            return "Работает с SMS. Пуш-уведомления банковских приложений iOS передавать не разрешает — для них скопируйте текст и вставьте через «Вставить текст сообщения»."
        }
    }
}

struct GuideView: View {
    let guide: Guide

    var body: some View {
        List {
            Section {
                Text(guide.intro)
            }
            Section("Настройка за 2 минуты") {
                ForEach(Array(guide.steps.enumerated()), id: \.offset) { index, step in
                    HStack(alignment: .top, spacing: 12) {
                        Text("\(index + 1)")
                            .font(.system(.footnote, design: .rounded).weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 24, height: 24)
                            .background(Color.brand, in: Circle())
                        Text(step)
                    }
                    .padding(.vertical, 2)
                }
            }
            Section {
                if let url = URL(string: "shortcuts://") {
                    Link(destination: url) {
                        Label("Открыть «Команды»", systemImage: "arrow.up.forward.app.fill")
                    }
                }
            } footer: {
                Text(guide.note)
            }
        }
        .navigationTitle(guide.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: Импорт выписки

struct StatementImportView: View {
    @Environment(\.modelContext) private var context
    @State private var picking = false
    @State private var result: StatementImporter.Result?
    @State private var outcome: ImportService.Outcome?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Text("Выгрузите выписку в формате CSV в приложении или личном кабинете банка и выберите файл. Грош сам найдёт колонки с датой, суммой и описанием, определит категории и пропустит операции, которые уже записаны.")
            }
            Section {
                Button {
                    picking = true
                } label: {
                    Label("Выбрать файл выписки", systemImage: "doc.badge.plus")
                }
            } footer: {
                Text("Т-Банк: Счёт → Выписка → Экспорт → CSV. Подходит любой CSV с колонками «Дата», «Сумма», «Описание».")
            }

            if let result, outcome == nil {
                Section("Найдено в файле") {
                    LabeledContent("Операций", value: "\(result.txns.count)")
                    if result.skippedRows > 0 {
                        LabeledContent("Пропущено строк", value: "\(result.skippedRows)")
                    }
                    ForEach(Array(result.txns.prefix(5).enumerated()), id: \.offset) { _, txn in
                        EntryRow(entry: Entry(txn, source: .csv))
                    }
                    Button("Импортировать \(result.txns.count)") {
                        outcome = ImportService.save(result.txns, source: .csv, in: context)
                    }
                    .font(.headline)
                }
            }

            if let outcome {
                Section {
                    Label("Добавлено операций: \(outcome.added)", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(Color.brand)
                    if outcome.duplicates > 0 {
                        Label("Уже были в базе: \(outcome.duplicates)", systemImage: "doc.on.doc")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Импорт выписки")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.commaSeparatedText, .plainText, .tabSeparatedText]) { picked in
            load(picked)
        }
    }

    private func load(_ picked: Swift.Result<URL, Error>) {
        outcome = nil
        result = nil
        error = nil
        guard case .success(let url) = picked else {
            error = "Не удалось открыть файл."
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let text = StatementImporter.decode(data) else {
            error = "Не удалось прочитать файл. Убедитесь, что это CSV."
            return
        }
        let parsed = StatementImporter.parse(csv: text)
        if parsed.txns.isEmpty {
            error = "В файле не нашлось операций. Нужны колонки с датой и суммой."
        } else {
            result = parsed
        }
    }
}

// MARK: Радар подписок

struct SubscriptionsView: View {
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]

    private var subscriptions: [SubscriptionDetector.Subscription] {
        SubscriptionDetector.detect(entries.expenses.map {
            SubscriptionDetector.Charge(merchant: $0.title, amount: $0.amount, date: $0.date)
        })
    }

    var body: some View {
        let subs = subscriptions
        let monthly = subs.reduce(Decimal(0)) { $0 + $1.monthlyCost }
        List {
            if subs.isEmpty {
                ContentUnavailableView {
                    Label("Подписок не найдено", systemImage: "repeat")
                } description: {
                    Text("Радар замечает списания, которые повторяются каждую неделю, месяц или год. Нужна история хотя бы за два-три месяца.")
                }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Подписки съедают").font(.subheadline).foregroundStyle(.secondary)
                        Text("\(Money.short(monthly)) в месяц")
                            .font(.system(.title, design: .rounded).weight(.bold))
                        Text("Это \(Money.short(monthly * 12)) в год")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                }
                Section("Найдено: \(subs.count)") {
                    ForEach(subs) { sub in
                        HStack(spacing: 12) {
                            IconBadge(symbol: "repeat", color: SpendCategory.subscriptions.color)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(sub.merchant).font(.body.weight(.medium)).lineLimit(1)
                                Text("\(sub.periodTitle) · следующее \(sub.nextDate.formatted(.dateTime.day().month(.abbreviated).locale(Locale(identifier: "ru_RU"))))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(Money.string(sub.amount))
                                .font(.system(.body, design: .rounded).weight(.semibold))
                                .monospacedDigit()
                        }
                    }
                }
            }
        }
        .navigationTitle("Радар подписок")
        .navigationBarTitleDisplayMode(.inline)
    }
}
