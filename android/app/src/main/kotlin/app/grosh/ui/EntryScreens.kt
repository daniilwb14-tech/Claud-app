package app.grosh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grosh.AppViewModel
import app.grosh.Overlay
import app.grosh.core.AmountParser
import app.grosh.core.BankMessageParser
import app.grosh.core.EntrySource
import app.grosh.core.Insights
import app.grosh.core.MerchantClassifier
import app.grosh.core.SpendCategory
import app.grosh.data.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Каркас экрана поверх вкладок: заголовок со стрелкой «назад». */
@Composable
fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        content = content,
    )
}

// MARK: История

private enum class HistoryFilter(val title: String) { ALL("Все"), EXPENSES("Расходы"), INCOMES("Доходы"), AUTO("Авто") }

@Composable
fun HistoryScreen(entries: List<Entry>, open: (Overlay) -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }

    val query = search.trim().lowercase()
    val filtered = entries.filter { e ->
        val passes = when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.EXPENSES -> !e.isIncome
            HistoryFilter.INCOMES -> e.isIncome
            HistoryFilter.AUTO -> e.src.isAutomatic
        }
        passes && (query.isEmpty() || e.merchant.lowercase().contains(query) || e.cat.title.lowercase().contains(query) ||
            e.note.lowercase().contains(query) || (e.market?.title?.lowercase()?.contains(query) ?: false))
    }
    val days = filtered.groupBy { it.day }.entries.sortedByDescending { it.key.toEpochDay() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { open(Overlay.Edit()) }) { Icon(Icons.Filled.Add, "Добавить операцию") }
        },
    ) { _ ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)) {
            item {
                Text("История", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Магазин, категория, заметка") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFilter.entries.forEach { item ->
                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.title) })
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { EmptyState("🗂️", "Операций нет", if (query.isEmpty()) "Здесь появится вся история трат и поступлений." else "По запросу ничего не найдено.") }
            }
            days.forEach { (day, dayEntries) ->
                item(key = "h$day") {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dayTitle(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("−" + moneyShort(dayEntries.expenses().total()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(dayEntries, key = { it.id }) { entry ->
                    EntryRow(entry) { open(Overlay.Edit(entry)) }
                }
            }
        }
    }
}

// MARK: Добавление и редактирование

@Composable
fun EditEntryScreen(vm: AppViewModel, args: Overlay.Edit, onClose: () -> Unit) {
    val existing = args.entry
    val prefill = args.prefill
    val zone = ZoneId.systemDefault()

    fun amountText(minor: Long) = if (minor % 100 == 0L) (minor / 100).toString() else "%d,%02d".format(minor / 100, minor % 100)

    var amount by remember { mutableStateOf(existing?.let { amountText(it.amountMinor) } ?: prefill?.let { amountText(it.amountMinor) } ?: "") }
    var isIncome by remember { mutableStateOf(existing?.isIncome ?: prefill?.isIncome ?: false) }
    var merchant by remember { mutableStateOf(existing?.merchant ?: prefill?.merchant ?: "") }
    var category by remember {
        mutableStateOf(existing?.cat ?: MerchantClassifier.category(prefill?.merchant.orEmpty(), prefill?.isIncome ?: false))
    }
    var categoryChosen by remember { mutableStateOf(existing != null) }
    var dateMillis by remember { mutableStateOf(existing?.dateMillis ?: prefill?.dateMillis ?: System.currentTimeMillis()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amountMinor = AmountParser.parseMinor(amount)?.takeIf { it > 0 }
    val date = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()

    fun save() {
        val minor = amountMinor ?: return
        val name = merchant.trim()
        val market = if (isIncome) null else MerchantClassifier.marketplace(name)?.name
        val entry = existing?.copy(
            amountMinor = minor, isIncome = isIncome, merchant = name, category = category.name,
            marketplace = market, dateMillis = dateMillis, note = note,
        ) ?: Entry(
            amountMinor = minor, currency = prefill?.currency ?: "RUB", dateMillis = dateMillis, merchant = name, note = note,
            isIncome = isIncome, category = category.name, marketplace = market, source = args.source.name, cardTail = prefill?.cardTail,
        )
        vm.save(entry)
        onClose()
    }

    OverlayScaffold(
        title = if (existing == null) "Новая операция" else "Операция",
        onBack = onClose,
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Удалить операцию") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isIncome, onClick = { isIncome = false; if (category.isIncome) { category = MerchantClassifier.category(merchant); categoryChosen = false } }, label = { Text("Расход") })
                FilterChip(selected = isIncome, onClick = { isIncome = true; if (!category.isIncome) { category = MerchantClassifier.category(merchant, true); categoryChosen = false } }, label = { Text("Доход") })
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(12) },
                placeholder = { Text("0", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 40.sp) },
                textStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (isIncome) IncomeColor else MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                supportingText = {
                    val hours = if (!isIncome && amountMinor != null) Insights.workHoursText(amountMinor, vm.hourlyRate * 100) else null
                    Text(if (hours != null) "🕒 $hours" else "Сумма, ₽", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = {
                    merchant = it
                    // Категория подбирается по названию, пока человек не выбрал её сам.
                    if (!categoryChosen) category = MerchantClassifier.category(it, isIncome)
                },
                label = { Text(if (isIncome) "Откуда поступление" else "Магазин или описание") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                fun setDay(day: LocalDate) {
                    dateMillis = day.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli()
                }
                FilterChip(selected = date == today, onClick = { setDay(today) }, label = { Text("Сегодня") })
                FilterChip(selected = date == today.minusDays(1), onClick = { setDay(today.minusDays(1)) }, label = { Text("Вчера") })
                FilterChip(
                    selected = date != today && date != today.minusDays(1),
                    onClick = { pickingDate = true },
                    label = { Text(if (date != today && date != today.minusDays(1)) dayTitle(date) else "Другая дата") },
                )
            }

            Text("Категория", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (isIncome) SpendCategory.incomeCases else SpendCategory.expenseCases).forEach { item ->
                    val selected = category == item
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) item.color else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
                            .clickable { category = item; categoryChosen = true }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.emoji)
                        Spacer(Modifier.width(6.dp))
                        Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Заметка (необязательно)") }, modifier = Modifier.fillMaxWidth())

            Button(onClick = { save() }, enabled = amountMinor != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Сохранить") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickingDate) {
        // DatePicker работает в UTC: переводим локальную дату туда и обратно.
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val picked = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!picked.isAfter(LocalDate.now())) {
                            dateMillis = picked.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                        }
                    }
                    pickingDate = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить операцию?") },
            confirmButton = { TextButton(onClick = { vm.delete(existing); onClose() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

// MARK: Вставка текста SMS / пуша

@Composable
fun PasteScreen(vm: AppViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val parsed = remember(text) { BankMessageParser.parse(text) }

    OverlayScaffold("Из сообщения", onClose) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Скопируйте SMS или пуш от банка и вставьте сюда — сумма, магазин и категория определятся сами.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("Текст сообщения банка") },
                minLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Text("📋 Вставить из буфера") }

            if (parsed != null) {
                GCard {
                    Text("Распознано", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EntryRow(Entry.from(parsed, EntrySource.PASTE))
                    parsed.cardTail?.let { Text("Карта •• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else if (text.isNotBlank()) {
                Text("Не нашёл в тексте операцию. Проверьте, что в сообщении есть сумма.", color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { parsed?.let { txn -> vm.savePasted(txn, force = false) { saved -> if (saved) onClose() else duplicate = true } } },
                enabled = parsed != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Сохранить") }
        }
    }

    if (duplicate && parsed != null) {
        AlertDialog(
            onDismissRequest = { duplicate = false },
            title = { Text("Такая операция уже есть") },
            text = { Text("За этот день уже записана операция на ту же сумму.") },
            confirmButton = { TextButton(onClick = { vm.savePasted(parsed, force = true) { onClose() } }) { Text("Всё равно добавить") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Не добавлять") } },
        )
    }
}
