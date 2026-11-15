import SwiftData
import SwiftUI

struct HistoryView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \Entry.date, order: .reverse) private var entries: [Entry]
    @State private var search = ""
    @State private var filter: Filter = .all
    @State private var editing: Entry?
    @State private var adding = false

    enum Filter: String, CaseIterable, Identifiable {
        case all = "Все", expenses = "Расходы", incomes = "Доходы", auto = "Авто"
        var id: String { rawValue }
    }

    private var filtered: [Entry] {
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        return entries.filter { entry in
            switch filter {
            case .all: break
            case .expenses: if entry.isIncome { return false }
            case .incomes: if !entry.isIncome { return false }
            case .auto: if !entry.source.isAutomatic { return false }
            }
            guard !query.isEmpty else { return true }
            return entry.merchant.lowercased().contains(query)
                || entry.category.title.lowercased().contains(query)
                || entry.note.lowercased().contains(query)
                || (entry.marketplace?.title.lowercased().contains(query) ?? false)
        }
    }

    private var days: [(day: Date, items: [Entry])] {
        let calendar = Calendar.current
        return Dictionary(grouping: filtered) { calendar.startOfDay(for: $0.date) }
            .map { (day: $0.key, items: $0.value) }
            .sorted { $0.day > $1.day }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Фильтр", selection: $filter) {
                        ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }

                ForEach(days, id: \.day) { group in
                    Section {
                        ForEach(group.items) { entry in
                            Button {
                                editing = entry
                            } label: {
                                EntryRow(entry: entry)
                            }
                            .buttonStyle(.plain)
                            .swipeActions {
                                Button("Удалить", role: .destructive) {
                                    context.delete(entry)
                                }
                            }
                        }
                    } header: {
                        HStack {
                            Text(group.day.dayTitle)
                            Spacer()
                            Text("−" + Money.short(group.items.expenses.total))
                        }
                    }
                }
            }
            .overlay {
                if filtered.isEmpty {
                    if search.isEmpty {
                        ContentUnavailableView("Операций нет", systemImage: "tray", description: Text("Здесь появится вся история трат и поступлений."))
                    } else {
                        ContentUnavailableView.search(text: search)
                    }
                }
            }
            .searchable(text: $search, prompt: "Магазин, категория, заметка")
            .navigationTitle("История")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        adding = true
                    } label: {
                        Image(systemName: "plus.circle.fill").font(.title3)
                    }
                    .accessibilityLabel("Добавить операцию")
                }
            }
            .sheet(isPresented: $adding) { AddEntryView() }
            .sheet(item: $editing) { AddEntryView(entry: $0) }
        }
    }
}
