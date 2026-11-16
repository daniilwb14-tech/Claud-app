package app.marzha.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marzha.AppViewModel
import app.marzha.core.SkuReport
import app.marzha.core.UnitCost

private enum class Sort(val title: String) {
    PROFIT("По прибыли"), REVENUE("По выручке"), MARGIN("По марже"), LOSS("Сначала убыточные"), NO_COST("Без себестоимости")
}

@Composable
fun ProductsScreen(vm: AppViewModel, openProduct: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.PROFIT) }
    var bulk by remember { mutableStateOf(false) }
    val report = vm.report

    val lines = remember(report, query, sort) {
        val q = query.trim().lowercase()
        val all = report?.lines.orEmpty().filter { it.sku != 0L }
            .filter { q.isEmpty() || it.product.offerId.lowercase().contains(q) || it.product.name.lowercase().contains(q) || it.sku.toString().contains(q) }
        when (sort) {
            Sort.PROFIT -> all.sortedByDescending { it.b.profit }
            Sort.REVENUE -> all.sortedByDescending { it.b.revenue }
            Sort.MARGIN -> all.sortedByDescending { if (it.b.revenue > 0) it.margin else -1.0 }
            Sort.LOSS -> all.sortedBy { it.b.profit }
            Sort.NO_COST -> all.filter { !it.hasCost }.sortedByDescending { it.b.revenue }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Товары", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        PeriodChips(vm.period, vm::choosePeriod)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Артикул, название или SKU") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Очистить") } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ChoiceChips(Sort.entries.map { it to it.title }, sort, { sort = it }, Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (lines.isNotEmpty() && (query.isNotBlank() || sort == Sort.NO_COST)) item {
                TextButton(onClick = { bulk = true }) { Text("Задать себестоимость всем в списке (${lines.size})") }
            }
            items(lines, key = { it.sku }) { l -> ProductRow(l, onClick = { openProduct(l.sku) }) }
            if (lines.isEmpty()) item {
                Hint(
                    if (report == null) "Загрузка…" else if (sort == Sort.NO_COST) "У всех товаров указана себестоимость." else "Ничего не найдено за этот период.",
                    Modifier.padding(24.dp),
                )
            }
        }
    }

    if (bulk) CostDialog(
        title = "Себестоимость для ${lines.size} тов.",
        initial = UnitCost(),
        onDismiss = { bulk = false },
        onSave = { cost ->
            vm.saveCost(lines.map { it.sku }, cost)
            bulk = false
        },
    )
}

/** Ввод себестоимости одной штуки. */
@Composable
fun CostDialog(title: String, initial: UnitCost, onDismiss: () -> Unit, onSave: (UnitCost) -> Unit) {
    var purchase by remember { mutableStateOf(fieldNum(initial.purchase)) }
    var marking by remember { mutableStateOf(fieldNum(initial.marking)) }
    var packaging by remember { mutableStateOf(fieldNum(initial.packaging)) }
    var other by remember { mutableStateOf(fieldNum(initial.other)) }
    val cost = UnitCost(parseNum(purchase), parseNum(marking), parseNum(packaging), parseNum(other))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("На одну штуку товара.")
                NumberField("Закупка", purchase, { purchase = it }, Modifier.fillMaxWidth())
                NumberField("Маркировка (КИЗ)", marking, { marking = it }, Modifier.fillMaxWidth())
                NumberField("Упаковка", packaging, { packaging = it }, Modifier.fillMaxWidth())
                NumberField("Прочее (доставка до склада и т. п.)", other, { other = it }, Modifier.fillMaxWidth())
                Text("Итого: ${rubUnit(cost.total)}", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { Button(onClick = { onSave(cost) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ProductDetailScreen(vm: AppViewModel, sku: Long, back: () -> Unit) {
    val line: SkuReport? = vm.report?.lines?.firstOrNull { it.sku == sku }
    val stock = vm.stockReport?.lines?.firstOrNull { it.sku == sku }
    val product = line?.product ?: stock?.product
    val cost = vm.costs[sku] ?: UnitCost()
    var edit by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Товар", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (product == null) {
            Hint("Нет данных по товару за выбранный период.", Modifier.padding(24.dp))
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(product, 64)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(productTitle(product), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (product.name.isNotEmpty() && product.name != product.offerId) Hint(product.name)
                    Hint("SKU $sku")
                }
            }
            Hint("Период: ${vm.period.title.lowercase()}. Меняется на вкладках «Сводка» и «Товары».")

            Panel(onClick = { edit = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Себестоимость 1 шт", fontWeight = FontWeight.SemiBold)
                        if (cost.total > 0) {
                            Hint("закупка ${rubUnit(cost.purchase)} · КИЗ ${rubUnit(cost.marking)} · упаковка ${rubUnit(cost.packaging)}" + if (cost.other > 0) " · прочее ${rubUnit(cost.other)}" else "")
                        } else {
                            Text("Не указана — прибыль завышена", color = warnColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(if (cost.total > 0) rubUnit(cost.total) else "Указать", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (line != null && line.netQty > 0) {
                val n = line.netQty.toDouble()
                Panel {
                    Text("Экономика одной штуки", fontWeight = FontWeight.SemiBold)
                    Hint("В среднем за период, с учётом возвратов и общих расходов.")
                    Spacer(Modifier.height(6.dp))
                    Line("Цена продажи", rubUnit(line.avgPrice), bold = true)
                    for ((label, v) in costRows(line.b)) Line(label, rubUnit(v / n), sub = if (line.b.revenue > 0) pct(-v / line.b.revenue) + " от цены" else "")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Line("Прибыль с 1 шт", rubUnit(line.profitPerUnit), valueColor = signColor(line.profitPerUnit), bold = true)
                }
            }

            if (line != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Чистая прибыль", rubShort(line.b.profit), "маржа ${pct(line.margin)}", Modifier.weight(1f), signColor(line.b.profit))
                    Kpi("Выкуплено", rubShort(line.b.revenue), "${qty(line.netQty)} шт · возвратов ${qty(line.returnQty)}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Заказано", "${qty(line.orderedQty)} шт", "выкуп ≈ ${pct(line.buyout)}", Modifier.weight(1f))
                    Kpi("Реклама", rubShort(-line.b.ads), "ДРР ${pct(line.drr)}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("На вложенный ₽", if (line.b.cogs < 0) pct(line.roi) else "—", "прибыль к себестоимости", Modifier.weight(1f))
                    Kpi("Оплатили клиенты", rubShort(line.customerPaid), "баллы Ozon ${rubShort(line.points)}", Modifier.weight(1f))
                }
                CostStructure(line)
            } else {
                Panel { Hint("За выбранный период по товару нет начислений.") }
            }

            if (stock != null) Panel {
                Text("Остатки", fontWeight = FontWeight.SemiBold)
                Line("На складах Ozon", "${qty(stock.stock.fbo)} шт")
                if (stock.stock.fbs > 0) Line("На своём складе (FBS)", "${qty(stock.stock.fbs)} шт")
                Line("Едет на склад", "${qty(stock.stock.inTransit)} шт")
                Line("Возвращается от покупателей", "${qty(stock.stock.returning)} шт")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Line("Вложено в остаток", rub(stock.invested), bold = true)
                Line("Хватит на", stock.daysLeft?.let { "${num(it)} дн." } ?: "продаж нет", sub = "при ${num(stock.perDay)} шт в день")
                if (stock.toBuy > 0) Line("Докупить на ${vm.settings.stockTargetDays} дн.", "${qty(stock.toBuy)} шт")
            }
        }
    }

    if (edit) CostDialog(
        title = "Себестоимость",
        initial = cost,
        onDismiss = { edit = false },
        onSave = {
            vm.saveCost(listOf(sku), it)
            edit = false
        },
    )
}
