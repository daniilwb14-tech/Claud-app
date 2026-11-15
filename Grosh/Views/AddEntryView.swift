import SwiftData
import SwiftUI
import VisionKit

/// Добавление и редактирование операции. Может открываться с заранее распознанными данными.
struct AddEntryView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @AppStorage(Prefs.hourlyRate) private var hourlyRate: Double = 0

    var entry: Entry?
    var prefill: ParsedTxn?
    var source: EntrySource = .manual

    @State private var amountText = ""
    @State private var isIncome = false
    @State private var merchant = ""
    @State private var category: SpendCategory = .other
    @State private var categoryChosenByUser = false
    @State private var date = Date()
    @State private var note = ""
    @State private var loaded = false
    @FocusState private var amountFocused: Bool

    private var amount: Decimal? {
        guard let value = AmountParser.parse(amountText), value > 0 else { return nil }
        return value
    }

    private var categories: [SpendCategory] {
        isIncome ? SpendCategory.incomeCases : SpendCategory.expenseCases
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Тип", selection: $isIncome) {
                        Text("Расход").tag(false)
                        Text("Доход").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())

                    VStack(spacing: 4) {
                        TextField("0", text: $amountText)
                            .keyboardType(.decimalPad)
                            .focused($amountFocused)
                            .font(.system(size: 48, weight: .bold, design: .rounded))
                            .multilineTextAlignment(.center)
                            .foregroundStyle(isIncome ? Color.income : Color.primary)
                        if !isIncome, let amount, let hours = Insights.workHoursText(amount: amount, hourlyRate: Decimal(hourlyRate)) {
                            Label(hours, systemImage: "clock")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("₽").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }

                Section("Где") {
                    TextField(isIncome ? "Откуда поступление" : "Магазин или описание", text: $merchant)
                    DatePicker("Дата", selection: $date, in: ...Date())
                }

                Section("Категория") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 8)], spacing: 10) {
                        ForEach(categories) { item in
                            Button {
                                category = item
                                categoryChosenByUser = true
                            } label: {
                                VStack(spacing: 6) {
                                    IconBadge(symbol: item.icon, color: item.color, size: 44)
                                        .overlay {
                                            if category == item {
                                                RoundedRectangle(cornerRadius: 13, style: .continuous)
                                                    .strokeBorder(item.color, lineWidth: 2.5)
                                            }
                                        }
                                    Text(item.title)
                                        .font(.caption2)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                        .foregroundStyle(category == item ? .primary : .secondary)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Заметка") {
                    TextField("Необязательно", text: $note, axis: .vertical)
                }

                if let entry {
                    Section {
                        Button("Удалить операцию", role: .destructive) {
                            context.delete(entry)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(entry == nil ? "Новая операция" : "Операция")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Сохранить", action: save)
                        .disabled(amount == nil)
                }
            }
            .onAppear(perform: load)
            .onChange(of: merchant) { _, newValue in
                // Категория подбирается по названию, пока человек не выбрал её сам.
                guard loaded, !categoryChosenByUser else { return }
                category = MerchantClassifier.category(for: newValue, isIncome: isIncome)
            }
            .onChange(of: isIncome) { _, nowIncome in
                guard loaded else { return }
                if category.isIncome != nowIncome {
                    category = MerchantClassifier.category(for: merchant, isIncome: nowIncome)
                    categoryChosenByUser = false
                }
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        if let entry {
            amountText = "\(entry.amount)".replacingOccurrences(of: ".", with: ",")
            isIncome = entry.isIncome
            merchant = entry.merchant
            category = entry.category
            categoryChosenByUser = true
            date = entry.date
            note = entry.note
        } else if let prefill {
            amountText = "\(prefill.amount)".replacingOccurrences(of: ".", with: ",")
            isIncome = prefill.isIncome
            merchant = prefill.merchant
            category = MerchantClassifier.category(for: prefill.merchant, isIncome: prefill.isIncome)
            date = prefill.date
        } else {
            amountFocused = true
        }
        // Флаг ставится после первого прохода, чтобы onChange не перезаписал загруженные значения.
        DispatchQueue.main.async { loaded = true }
    }

    private func save() {
        guard let amount else { return }
        let name = merchant.trimmingCharacters(in: .whitespacesAndNewlines)
        let marketplace = isIncome ? nil : MerchantClassifier.marketplace(for: name)
        if let entry {
            entry.amount = amount
            entry.isIncome = isIncome
            entry.merchant = name
            entry.category = category
            entry.marketplace = marketplace
            entry.date = date
            entry.note = note
        } else {
            context.insert(Entry(amount: amount, currency: prefill?.currency ?? "RUB", date: date, merchant: name, note: note,
                                 isIncome: isIncome, category: category, marketplace: marketplace,
                                 source: source, cardTail: prefill?.cardTail))
        }
        try? context.save()
        dismiss()
    }
}

// MARK: Вставка текста SMS / пуша

struct SmartPasteView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var duplicateWarning = false

    private var parsed: ParsedTxn? { BankMessageParser.parse(text) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $text)
                        .frame(minHeight: 110)
                    PasteButton(payloadType: String.self) { strings in
                        text = strings.first ?? ""
                    }
                } header: {
                    Text("Текст сообщения банка")
                } footer: {
                    Text("Скопируйте SMS или пуш от банка и вставьте сюда — сумма, магазин и категория определятся сами.")
                }

                if let parsed {
                    Section("Распознано") {
                        EntryRow(entry: Entry(parsed, source: .paste))
                        if let card = parsed.cardTail {
                            LabeledContent("Карта", value: "•• \(card)")
                        }
                    }
                } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Section {
                        Label("Не нашёл в тексте операцию. Проверьте, что в сообщении есть сумма.", systemImage: "questionmark.circle")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Из сообщения")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Сохранить") {
                        guard let parsed else { return }
                        let outcome = ImportService.save([parsed], source: .paste, in: context)
                        if outcome.added == 0 { duplicateWarning = true } else { dismiss() }
                    }
                    .disabled(parsed == nil)
                }
            }
            .alert("Такая операция уже есть", isPresented: $duplicateWarning) {
                Button("Всё равно добавить") {
                    if let parsed { ImportService.save([parsed], source: .paste, in: context, deduplicate: false) }
                    dismiss()
                }
                Button("Не добавлять", role: .cancel) { dismiss() }
            } message: {
                Text("За этот день уже записана операция на ту же сумму.")
            }
        }
    }
}

// MARK: Скан QR-кода с чека

struct ScanReceiptView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var parsed: ParsedTxn?
    @State private var manualCode = ""
    @State private var failed = false

    @MainActor private var scannerAvailable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        if let parsed {
            AddEntryView(prefill: parsed, source: .qr)
        } else {
            NavigationStack {
                VStack(spacing: 16) {
                    if scannerAvailable {
                        QRScanner { handle($0) }
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .overlay(alignment: .bottom) {
                                Text(failed ? "Это не QR-код кассового чека" : "Наведите камеру на QR-код внизу чека")
                                    .font(.subheadline.weight(.medium))
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(.ultraThinMaterial, in: Capsule())
                                    .padding(16)
                            }
                    } else {
                        ContentUnavailableView {
                            Label("Камера недоступна", systemImage: "camera.fill")
                        } description: {
                            Text("Разрешите доступ к камере в Настройках или вставьте содержимое QR-кода ниже.")
                        }
                        TextField("t=20240115T1405&s=1234.56&fn=…", text: $manualCode)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("Распознать") { handle(manualCode) }
                            .buttonStyle(.borderedProminent)
                            .disabled(manualCode.isEmpty)
                        if failed {
                            Text("Это не похоже на код кассового чека.")
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                    }
                }
                .padding(16)
                .navigationTitle("Скан чека")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Отмена") { dismiss() }
                    }
                }
            }
        }
    }

    private func handle(_ payload: String) {
        if let txn = ReceiptQRParser.parse(payload) {
            parsed = txn
        } else {
            failed = true
        }
    }
}

/// Обёртка над системным сканером VisionKit.
struct QRScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                                   qualityLevel: .balanced,
                                                   recognizesMultipleItems: false,
                                                   isHighFrameRateTrackingEnabled: false,
                                                   isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var lastPayload = ""

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let code) = item, let payload = code.payloadStringValue, payload != lastPayload {
                    lastPayload = payload
                    onCode(payload)
                }
            }
        }
    }
}
