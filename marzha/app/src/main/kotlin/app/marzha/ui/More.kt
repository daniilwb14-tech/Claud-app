package app.marzha.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.marzha.AppViewModel
import app.marzha.core.Expense
import app.marzha.core.ExpenseKind
import app.marzha.core.TaxBase
import app.marzha.core.expensesFor
import app.marzha.core.reportCsv

@Composable
fun MoreScreen(vm: AppViewModel, openAds: () -> Unit) {
    val context = LocalContext.current
    var editExpense by remember { mutableStateOf<Expense?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    var showPerf by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val s = vm.settings

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Настройки", Modifier.padding(start = 4.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        // ── Налог и ставки ──
        item { SectionTitle("Налог и ставки") }
        item {
            Panel {
                var tax by remember(s.taxPct) { mutableStateOf(fieldNum(s.taxPct)) }
                var mgr by remember(s.managerPct) { mutableStateOf(fieldNum(s.managerPct)) }
                var days by remember(s.stockTargetDays) { mutableStateOf(s.stockTargetDays.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Налог", tax, { tax = it; vm.saveSettings(vm.settings.copy(taxPct = parseNum(it).coerceIn(0.0, 100.0))) }, Modifier.weight(1f), "%")
                    NumberField("Менеджер", mgr, { mgr = it; vm.saveSettings(vm.settings.copy(managerPct = parseNum(it).coerceIn(0.0, 100.0))) }, Modifier.weight(1f), "%")
                }
                Hint("Налог — общая ставка, например УСН 1% + НДС 5% = 6. Менеджер — процент от выкупов.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Text("С какой суммы считать налог", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                ChoiceChips(
                    listOf(TaxBase.ALL to "Со всех выкупов", TaxBase.CUSTOMER_PAID to "Только с оплаты клиента"),
                    s.taxBase, { vm.saveSettings(vm.settings.copy(taxBase = it)) },
                )
                Hint("Второй вариант не считает налог с баллов и скидок за счёт Ozon. Уточните у бухгалтера, какой подходит вам.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                NumberField("Запас товара, на сколько дней", days, {
                    days = it.filter(Char::isDigit).take(3)
                    days.toIntOrNull()?.let { d -> if (d in 1..365) vm.saveSettings(vm.settings.copy(stockTargetDays = d)) }
                }, Modifier.fillMaxWidth(), "дн.")
                Hint("Нужно для расчёта, сколько докупить.", Modifier.padding(top = 6.dp))
            }
        }

        // ── Свои расходы ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Свои расходы", Modifier.weight(1f))
                IconButton(onClick = { editExpense = Expense(0, "", 0.0, ExpenseKind.MONTHLY) }) { Icon(Icons.Filled.Add, contentDescription = "Добавить расход") }
            }
        }
        if (vm.expenses.isEmpty()) item {
            Panel(onClick = { editExpense = Expense(0, "", 0.0, ExpenseKind.MONTHLY) }) {
                Text("Добавить расход", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Hint("Ведение кабинета, зарплаты, аренда склада, фотосессии — всё, что не списывает Ozon. Расходы делятся между товарами по доле выкупов.")
            }
        }
        items(vm.expenses, key = { it.id }) { e ->
            Panel(onClick = { editExpense = e }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.Medium)
                        Hint(
                            when (e.kind) {
                                ExpenseKind.MONTHLY -> "каждый месяц"
                                ExpenseKind.WEEKLY -> "каждую неделю"
                                ExpenseKind.ONCE -> "разово, " + dayMonth(e.date)
                            }
                        )
                    }
                    Text(rub(e.amount), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        vm.report?.let { r ->
            if (vm.expenses.isNotEmpty()) item {
                Hint("За выбранный период (${dayMonth(r.from)}–${dayMonth(r.to)}): ${rub(expensesFor(vm.expenses, r.from, r.to))}", Modifier.padding(start = 4.dp))
            }
        }

        // ── Ozon ──
        item { SectionTitle("Подключение Ozon") }
        item {
            Panel {
                if (vm.demo) {
                    Text("Демо-кабинет", fontWeight = FontWeight.SemiBold)
                    Hint("Данные выдуманы. Подключите свой кабинет Ozon, чтобы увидеть настоящие цифры.")
                    Button(onClick = { showKeys = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Подключить Ozon") }
                } else {
                    Line("Client-Id", vm.clientId)
                    Line("API-ключ", "сохранён на устройстве", sub = "хранится в зашифрованном виде и никуда не передаётся, кроме Ozon")
                    TextButton(onClick = { showKeys = true }) { Text("Заменить ключ") }
                }
            }
        }
        if (!vm.demo) item {
            Panel(onClick = { showPerf = true }) {
                Text("Статистика рекламных кампаний", fontWeight = FontWeight.SemiBold)
                Hint(
                    if (vm.hasPerf) "Performance API подключён. Нажмите, чтобы заменить ключ."
                    else "Необязательно. Расходы на рекламу уже учтены в прибыли — они берутся из начислений. Ключ Performance API добавит показы, клики и ДРР по каждой кампании."
                )
            }
        }
        if (vm.campaignLines.isNotEmpty()) item {
            Panel(onClick = openAds) { Text("Рекламные кампании за период", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary) }
        }

        // ── Данные ──
        item { SectionTitle("Данные") }
        item {
            Panel {
                vm.loadedFrom?.let { Line("История загружена с", dayMonth(it) + "." + it.year) }
                if (!vm.demo) {
                    TextButton(onClick = { vm.sync() }, enabled = !vm.syncing) { Text(if (vm.syncing) "Идёт загрузка…" else "Обновить сейчас") }
                    TextButton(onClick = { vm.sync(vm.today().minusDays(92)) }, enabled = !vm.syncing) { Text("Загрузить историю за 3 месяца") }
                }
                TextButton(onClick = { vm.report?.let { shareCsv(context, reportCsv(it)) } }, enabled = vm.report != null) { Text("Выгрузить отчёт по товарам (CSV)") }
                TextButton(onClick = { confirmLogout = true }, enabled = !vm.syncing) {
                    Text(if (vm.demo) "Выйти из демо" else "Выйти и стереть данные с телефона", color = lossColor)
                }
            }
        }

        item { SectionTitle("Как считается прибыль") }
        item {
            Panel {
                Hint(
                    "Выкупы и удержания берутся по дате начисления Ozon (Финансы → Начисления), поэтому сумма сходится с личным кабинетом.\n\n" +
                        "Чистая прибыль = выкуплено − возвраты − комиссия, логистика, реклама и прочие начисления Ozon − себестоимость проданного (закупка, маркировка, упаковка) − налог − менеджер − свои расходы.\n\n" +
                        "Начисления без привязки к товару (хранение, кросс-докинг, реклама кабинета) и свои расходы делятся между товарами по доле выкупов.\n\n" +
                        "Заказы считаются по дате оформления и нужны для ДРР и доли выкупа. Остатки: склады Ozon, свой склад, поставки в пути и возвраты, которые едут обратно на склад."
                )
            }
        }
    }

    editExpense?.let { e ->
        ExpenseDialog(e, today = vm.today().toString(), onDismiss = { editExpense = null }, onSave = { vm.saveExpense(it); editExpense = null }, onDelete = { vm.deleteExpense(e.id); editExpense = null })
    }
    if (showKeys) KeysDialog(vm, onDismiss = { showKeys = false })
    if (showPerf) PerfDialog(onDismiss = { showPerf = false }, onSave = { id, secret -> vm.savePerformanceKeys(id, secret); showPerf = false })
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(if (vm.demo) "Выйти из демо?" else "Стереть данные?") },
        text = { Text(if (vm.demo) "Демо-данные будут удалены." else "С телефона удалятся ключ Ozon, загруженные данные, себестоимость и расходы. В кабинете Ozon ничего не изменится.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Выйти", color = lossColor) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
    )
}

private fun shareCsv(context: Context, csv: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Юнит-экономика Ozon")
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
}

@Composable
private fun ExpenseDialog(initial: Expense, today: String, onDismiss: () -> Unit, onSave: (Expense) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var amount by remember { mutableStateOf(fieldNum(initial.amount)) }
    var kind by remember { mutableStateOf(initial.kind) }
    var date by remember { mutableStateOf(initial.date.ifEmpty { today }) }
    val dateOk = kind != ExpenseKind.ONCE || runCatching { java.time.LocalDate.parse(date) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый расход" else "Расход") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("Сумма", amount, { amount = it }, Modifier.fillMaxWidth())
                ChoiceChips(listOf(ExpenseKind.MONTHLY to "В месяц", ExpenseKind.WEEKLY to "В неделю", ExpenseKind.ONCE to "Разово"), kind, { kind = it })
                if (kind == ExpenseKind.ONCE) {
                    OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата (ГГГГ-ММ-ДД)") }, singleLine = true, isError = !dateOk, modifier = Modifier.fillMaxWidth())
                }
                if (initial.id != 0L) TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = lossColor)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить", color = lossColor)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(initial.copy(name = name.trim(), amount = parseNum(amount), kind = kind, date = if (kind == ExpenseKind.ONCE) date else "")) },
                enabled = name.isNotBlank() && parseNum(amount) > 0 && dateOk,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Форма ключа Seller API: и на первом экране, и в настройках. */
@Composable
fun KeysForm(vm: AppViewModel, onConnected: () -> Unit, modifier: Modifier = Modifier) {
    var id by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(id, { id = it.filter(Char::isDigit).take(12) }, label = { Text("Client-Id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it.trim().take(80) }, label = { Text("API-ключ") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        err?.let { Text(it, color = lossColor, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                busy = true
                err = null
                vm.connect(id, key) { e ->
                    busy = false
                    err = e
                    if (e == null) onConnected()
                }
            },
            enabled = !busy && id.isNotEmpty() && key.length >= 8,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Подключить Ozon")
        }
        Hint("Где взять: Ozon Seller → Настройки → Seller API → Сгенерировать ключ. Достаточно роли «Admin read only» — приложение только читает данные и ничего не меняет в кабинете.")
        Hint("Ключ хранится только на этом телефоне в зашифрованном виде и отправляется только в Ozon.")
    }
}

@Composable
private fun KeysDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ Ozon Seller API") },
        text = { KeysForm(vm, onConnected = onDismiss, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun PerfDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Performance API") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it.trim().take(120) }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it.trim().take(200) }, label = { Text("Client Secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Hint("Где взять: Ozon Seller → Настройки → API-ключи → Performance API → создать сервисный аккаунт.")
            }
        },
        confirmButton = { Button(onClick = { onSave(id, secret) }, enabled = id.isNotEmpty() && secret.isNotEmpty()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun AdsScreen(vm: AppViewModel, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Рекламные кампании", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        PeriodChips(vm.period, vm::choosePeriod)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val lines = vm.campaignLines
            if (lines.isNotEmpty()) item {
                val spent = lines.sumOf { it.spent }
                val sum = lines.sumOf { it.ordersSum }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Расход", rubShort(spent), "${lines.size} кампаний", Modifier.weight(1f))
                    Kpi("Заказы с рекламы", rubShort(sum), "ДРР ${pct(if (sum > 0) spent / sum else 0.0)}", Modifier.weight(1f))
                }
            }
            items(lines, key = { it.id }) { c ->
                Panel {
                    Text(c.title.ifEmpty { "Кампания ${c.id}" }, fontWeight = FontWeight.SemiBold)
                    Hint("ID ${c.id}")
                    Spacer(Modifier.height(6.dp))
                    Line("Расход", rub(c.spent), bold = true)
                    Line("Показы → клики", "${qty(c.views.toInt())} → ${qty(c.clicks.toInt())}", sub = "CTR ${pct(c.ctr)}")
                    Line("Заказы", "${qty(c.orders.toInt())} шт · ${rub(c.ordersSum)}", sub = "заказ стоит ${rubUnit(c.cpo)}")
                    Line("ДРР", pct(c.drr), valueColor = if (c.drr > 0.15) lossColor else profitColor)
                }
            }
            if (lines.isEmpty()) item { Hint("За период кампаний нет.", Modifier.padding(24.dp)) }
        }
    }
}
