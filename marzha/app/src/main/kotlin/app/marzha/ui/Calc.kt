package app.marzha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marzha.AppViewModel
import app.marzha.core.CalcInput
import app.marzha.core.CategoryStat
import app.marzha.core.SkuReport
import app.marzha.core.TaxBase
import app.marzha.core.Verdict
import app.marzha.core.calcFromSales
import app.marzha.core.calcUnit

/** Поле калькулятора: ключ, подпись, единица и хранится ли оно процентом. */
private class Field(val key: String, val label: String, val unit: String, val pct: Boolean = false)

private val priceFields = listOf(Field("price", "Цена продажи на Ozon", "₽"))
private val costFields = listOf(
    Field("purchase", "Закупка", "₽"), Field("marking", "Маркировка (КИЗ)", "₽"),
    Field("packaging", "Упаковка", "₽"), Field("inbound", "Доставка до склада и прочее", "₽"),
)
private val ozonFields = listOf(
    Field("commission", "Комиссия Ozon", "%", true), Field("logistics", "Логистика до покупателя", "₽"),
    Field("returnLogistics", "Обратная логистика за невыкуп", "₽"), Field("buyout", "Процент выкупа", "%", true),
    Field("drr", "ДРР от заказов", "%", true), Field("acquiring", "Эквайринг", "%", true),
    Field("storage", "Хранение на штуку", "₽"),
)
private val taxFields = listOf(Field("taxPct", "Налог", "%", true), Field("manager", "Менеджер", "%", true))
private val planFields = listOf(Field("ordersPerDay", "Заказов в день (план)", "шт"), Field("stockDays", "Первая партия на", "дн."))

/** Записывает цифры в поля калькулятора. */
private fun fill(vm: AppViewModel, c: CalcInput) {
    fun put(k: String, v: Double, pct: Boolean = false) {
        vm.calcFields[k] = fieldNum(if (pct) Math.round(v * 1000) / 10.0 else Math.round(v * 100) / 100.0)
    }
    put("price", c.price); put("purchase", c.purchase); put("marking", c.marking); put("packaging", c.packaging)
    put("inbound", c.inbound); put("commission", c.commission, true); put("logistics", c.logistics)
    put("returnLogistics", c.returnLogistics); put("buyout", c.buyout, true); put("drr", c.drr, true)
    put("acquiring", c.acquiring, true); put("storage", c.storage); put("ozonPoints", c.ozonPoints, true)
    put("taxPct", c.taxPct, true); put("manager", c.manager, true)
    vm.calcFields["stockDays"] = c.stockDays.toString()
}

/** Собирает ввод калькулятора из полей. */
private fun input(vm: AppViewModel): CalcInput {
    fun v(k: String) = parseNum(vm.calcFields[k].orEmpty())
    fun p(k: String) = v(k) / 100
    return CalcInput(
        price = v("price"), purchase = v("purchase"), marking = v("marking"), packaging = v("packaging"),
        inbound = v("inbound"), commission = p("commission"), logistics = v("logistics"),
        returnLogistics = v("returnLogistics"), buyout = if (vm.calcFields["buyout"].isNullOrBlank()) 0.8 else p("buyout"),
        drr = p("drr"), acquiring = p("acquiring"), storage = v("storage"), ozonPoints = p("ozonPoints"),
        taxPct = p("taxPct"), taxBase = vm.settings.taxBase, manager = p("manager"),
        ordersPerDay = v("ordersPerDay"), stockDays = v("stockDays").toInt().takeIf { it > 0 } ?: vm.settings.stockTargetDays,
    )
}

/** Вкладка «Проверка товара»: юнит-экономика нового товара до запуска. */
@Composable
fun CalcScreen(vm: AppViewModel) {
    var pickProduct by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    // Первое открытие: подставляем средние по магазину, чтобы не начинать с нуля.
    LaunchedEffect(vm.recentReport, vm.profile.id) {
        if (vm.calcFields.isEmpty()) {
            val r = vm.recentReport
            val base = CalcInput(
                taxPct = vm.settings.taxPct / 100, manager = vm.settings.managerPct / 100,
                stockDays = vm.settings.stockTargetDays, acquiring = 0.01,
            )
            fill(vm, if (r != null && r.total.netQty > 0) calcFromSales(r.total, null, vm.settings, base).copy(
                price = 0.0, purchase = 0.0, marking = 0.0, packaging = 0.0, inbound = 0.0,
            ) else base)
        }
    }
    val inp = input(vm)
    val res = calcUnit(inp)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Проверка товара", Modifier.padding(start = 4.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("Посчитайте юнит-экономику до запуска: сколько товар будет приносить и бьётся ли он вообще.", Modifier.padding(start = 4.dp))
        }
        item {
            Panel {
                Text("Подставить цифры", fontWeight = FontWeight.SemiBold)
                Hint("Комиссию, логистику, выкуп и ДРР можно взять из ваших продаж за 30 дней, а цену и выкуп ниши — из аналитики Ozon.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {
                        vm.recentReport?.total?.let { t -> fill(vm, calcFromSales(t, null, vm.settings, input(vm)).copy(price = inp.price, purchase = inp.purchase, marking = inp.marking, packaging = inp.packaging, inbound = inp.inbound)) }
                    }, label = { Text("Средние по магазину") }, enabled = (vm.recentReport?.total?.netQty ?: 0) > 0)
                    AssistChip(onClick = { pickProduct = true }, label = { Text("Как у моего товара…") }, enabled = vm.recentReport != null)
                    AssistChip(onClick = { pickCategory = true; if (vm.categories.isEmpty()) vm.loadCategories() }, label = { Text("Ниша на Ozon…") })
                    AssistChip(onClick = { vm.calcFields.clear(); vm.calcCategory = null }, label = { Text("Очистить") })
                }
            }
        }
        vm.calcCategory?.let { c -> item { CategoryCard(c) } }

        item { VerdictCard(res.verdict, res.profit, res.margin) }

        item { FieldsPanel(vm, "Цена", priceFields) }
        item { FieldsPanel(vm, "Себестоимость 1 шт", costFields, footer = "Итого себестоимость: ${rubUnit(inp.cogs)}") }
        item {
            FieldsPanel(
                vm, "Расходы Ozon", ozonFields,
                footer = "Логистика зависит от объёма товара в литрах и склада — проще всего взять её «как у моего товара» похожего размера.",
            )
        }
        item {
            FieldsPanel(
                vm, "Налог и менеджер", taxFields +
                    if (vm.settings.taxBase == TaxBase.CUSTOMER_PAID) listOf(Field("ozonPoints", "Баллы и скидки Ozon в цене", "%", true)) else emptyList(),
                footer = if (vm.settings.taxBase == TaxBase.CUSTOMER_PAID) "Налог считается с оплаты клиента: цена минус баллы Ozon (как в настройках)." else "Налог считается со всей цены (как в настройках).",
            )
        }

        item {
            Panel {
                Text("Экономика одной продажи", fontWeight = FontWeight.SemiBold)
                Hint("На одну выкупленную штуку, с учётом невыкупов и рекламы со всех заказов.")
                Spacer(Modifier.height(4.dp))
                Line("Цена продажи", rubUnit(res.price), bold = true)
                for ((label, v) in listOf(
                    "Комиссия Ozon" to res.commission, "Логистика" to res.logistics, "Невыкупы (туда и обратно)" to res.nonBuyout,
                    "Реклама" to res.ads, "Эквайринг" to res.acquiring, "Хранение" to res.storage, "Налог" to res.tax,
                    "Менеджер" to res.manager, "Себестоимость" to res.cogs,
                )) if (kotlin.math.abs(v) >= 0.005) Line(label, rubUnit(v), sub = if (res.price > 0) pct(-v / res.price) + " от цены" else "")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Line("Прибыль с 1 шт", rubUnit(res.profit), valueColor = signColor(res.profit), bold = true, sub = if (res.price > 0) "маржа ${pct(res.margin)}" else "")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Безубыточная цена", res.breakEvenPrice?.let(::rubUnit) ?: "—", "ниже — продажа в минус", Modifier.weight(1f))
                    Kpi("Предельный ДРР", pct(res.maxDrr), "выше — реклама съест прибыль", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("На вложенный ₽", if (inp.cogs > 0) pct(res.roi) else "—", "прибыль к себестоимости", Modifier.weight(1f))
                    Kpi("Прибыль в месяц", if (inp.ordersPerDay > 0) rubShort(res.monthProfit) else "—", if (inp.ordersPerDay > 0) "${qty(res.soldPerMonth.toInt())} выкупов · ${rubShort(res.monthRevenue)}" else "укажите заказы в день", Modifier.weight(1f), signColor(res.monthProfit))
                }
                if (inp.ordersPerDay > 0) Kpi(
                    "Первая партия на ${inp.stockDays} дн.", rubShort(res.firstBatch), "${qty(res.firstBatchUnits)} шт по себестоимости", Modifier.fillMaxWidth(),
                )
            }
        }
        item { FieldsPanel(vm, "План продаж", planFields, footer = "Для прогноза прибыли за месяц и суммы на первую закупку.") }
        item {
            Hint(
                "Как считается: на каждую выкупленную штуку приходится (1 − выкуп) ÷ выкуп невыкупленных — за каждую платится логистика туда и обратно. " +
                    "Реклама платится со всех заказов, поэтому на выкупленную штуку — ДРР × цена ÷ выкуп. " +
                    "Прибыль = цена − комиссия − логистика − невыкупы − реклама − эквайринг − хранение − налог − менеджер − себестоимость.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (pickProduct) ProductPicker(vm.recentReport?.lines.orEmpty().filter { it.sku != 0L && it.netQty > 0 }, onDismiss = { pickProduct = false }) { l ->
        fill(vm, calcFromSales(l, vm.costs[l.sku], vm.settings, input(vm)))
        pickProduct = false
    }
    if (pickCategory) CategoryPicker(vm, onDismiss = { pickCategory = false }) { c ->
        vm.calcCategory = c
        if (c.avgPrice > 0) vm.calcFields["price"] = fieldNum(Math.round(c.avgPrice).toDouble())
        if (c.buyout > 0) vm.calcFields["buyout"] = fieldNum(Math.round(c.buyout * 1000) / 10.0)
        pickCategory = false
    }
}

@Composable
private fun FieldsPanel(vm: AppViewModel, title: String, fields: List<Field>, footer: String = "") {
    Panel {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        fields.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { f ->
                    OutlinedTextField(
                        value = vm.calcFields[f.key].orEmpty(),
                        onValueChange = { s -> vm.calcFields[f.key] = s.filter { it.isDigit() || it == ',' || it == '.' }.take(10) },
                        label = { Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text(f.unit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1 && fields.size > 1) Spacer(Modifier.weight(1f))
            }
        }
        if (footer.isNotEmpty()) Hint(footer)
    }
}

@Composable
private fun VerdictCard(v: Verdict, profit: Double, margin: Double) {
    val color = when (v) {
        Verdict.GOOD -> profitColor
        Verdict.OK -> LocalPalette.current.bars
        Verdict.THIN -> warnColor
        Verdict.LOSS -> lossColor
        Verdict.EMPTY -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(v.title, fontWeight = FontWeight.Bold, color = color, fontSize = 20.sp)
            Text(v.text, style = MaterialTheme.typography.bodySmall)
        }
        if (v != Verdict.EMPTY) Column(horizontalAlignment = Alignment.End) {
            Text(rubUnit(profit), fontWeight = FontWeight.Bold, color = signColor(profit), fontSize = 18.sp)
            Hint("с 1 шт · ${pct(margin)}")
        }
    }
}

@Composable
private fun CategoryCard(c: CategoryStat) {
    Panel {
        Text("Ниша: ${c.name}", fontWeight = FontWeight.SemiBold)
        Hint("Аналитика Ozon за последний месяц")
        Spacer(Modifier.height(4.dp))
        Line("Сумма заказов в нише", rubShort(c.gmv), sub = "рост к прошлому месяцу ${pct(c.gmvGrowth)}")
        Line("Средняя цена", rubUnit(c.avgPrice))
        Line("Выкуп в нише", pct(c.buyout))
        Line("Продавцов", qty(c.sellers.toInt()), sub = "у топ-5 продавцов ${pct(c.leaderShare)} продаж")
        Hint(
            when {
                c.leaderShare > 0.5 -> "Нишу держат несколько крупных продавцов — зайти будет дорого по рекламе."
                c.gmvGrowth > 0.1 -> "Ниша растёт — хороший момент для входа."
                c.gmvGrowth < -0.05 -> "Ниша сокращается — спрос падает."
                else -> "Ниша стабильна."
            },
            Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ProductPicker(lines: List<SkuReport>, onDismiss: () -> Unit, onPick: (SkuReport) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = lines.filter { q.isBlank() || it.product.offerId.contains(q, true) || it.product.name.contains(q, true) }.sortedByDescending { it.b.revenue }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как у какого товара?") },
        text = {
            Column {
                Hint("Возьмём его цену, комиссию, логистику, выкуп, ДРР и себестоимость за 30 дней. Удобно для похожего по размеру и категории товара.")
                OutlinedTextField(q, { q = it }, placeholder = { Text("Поиск") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.sku }) { l ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(l) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProductImage(l.product, 36)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(productTitle(l.product), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint("${rubUnit(l.avgPrice)} · маржа ${pct(l.margin)}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun CategoryPicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (CategoryStat) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = vm.categories.filter { q.isBlank() || it.name.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ниша на Ozon") },
        text = {
            Column {
                Hint("Категории из «Аналитика → Что продавать» за последний месяц: средняя цена, выкуп, продавцы. Подставим цену и выкуп ниши.")
                when {
                    vm.categoriesLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    vm.categoriesError != null -> {
                        Text(vm.categoriesError!!, color = lossColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { vm.loadCategories() }) { Text("Попробовать снова") }
                    }
                    else -> {
                        OutlinedTextField(q, { q = it }, placeholder = { Text("Например, перчатки") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(list, key = { it.id + it.name }) { c ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c) }.padding(vertical = 8.dp)) {
                                    Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Hint("${rubShort(c.gmv)} в месяц · средняя ${rubUnit(c.avgPrice)} · выкуп ${pct(c.buyout)} · рост ${pct(c.gmvGrowth)}")
                                }
                            }
                            if (list.isEmpty()) item { Hint("Ничего не найдено.", Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
