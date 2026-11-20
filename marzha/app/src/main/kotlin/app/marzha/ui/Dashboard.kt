package app.marzha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marzha.AppViewModel
import app.marzha.core.Breakdown
import app.marzha.core.DayPoint
import app.marzha.core.Grouping
import app.marzha.core.groupPoints
import app.marzha.core.Report
import app.marzha.core.SkuReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Название текущего кабинета; нажатие открывает список кабинетов. */
@Composable
private fun ProfileSwitcher(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(vm.profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Выбрать кабинет")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, fontWeight = if (p.id == vm.profile.id) FontWeight.SemiBold else FontWeight.Normal) },
                    leadingIcon = { RadioButton(selected = p.id == vm.profile.id, onClick = null) },
                    onClick = { open = false; vm.switchProfile(p.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Добавить кабинет") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { open = false; add = true },
            )
        }
    }
    if (add) AddProfileDialog(vm, onDismiss = { add = false })
}

/** Статьи расходов для списка «Куда уходят деньги». */
fun costRows(b: Breakdown): List<Pair<String, Double>> = listOf(
    "Комиссия Ozon" to b.commission,
    "Логистика" to b.logistics,
    "Обратная логистика" to b.returnLogistics,
    "Реклама и продвижение" to b.ads,
    "Хранение" to b.storage,
    "Эквайринг" to b.acquiring,
    "Кросс-докинг" to b.crossdock,
    "Прочие начисления Ozon" to b.otherOzon,
    "Закупка товара" to b.purchase,
    "Маркировка (КИЗ)" to b.marking,
    "Упаковка" to b.packaging,
    "Прочая себестоимость" to b.otherCost,
    "Налог" to b.tax,
    "Менеджер" to b.manager,
    "Постоянные расходы" to b.fixed,
).filter { kotlin.math.abs(it.second) >= 0.5 }

@Composable
fun DashboardScreen(
    vm: AppViewModel,
    openProduct: (Long) -> Unit,
    openProducts: (ProductSort) -> Unit,
    openAds: () -> Unit,
    openPayout: () -> Unit,
    openSettings: () -> Unit,
) {
    val report = vm.report
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ProfileSwitcher(vm)
                    Hint(
                        when {
                            vm.demo -> "Демо-кабинет · данные выдуманы"
                            vm.lastSync > 0 -> "Обновлено " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(vm.lastSync))
                            else -> "Данные ещё не загружены"
                        }
                    )
                }
                IconButton(onClick = { vm.sync() }, enabled = !vm.syncing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить данные")
                }
            }
        }
        item { PeriodChips(vm) }

        item { SyncBanner(vm, Modifier.padding(horizontal = 16.dp)) }

        if (report == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }

        item { ProfitHero(report, Modifier.padding(horizontal = 16.dp)) }

        if (report.missingCost > 0) item {
            Panel(Modifier.padding(horizontal = 16.dp), onClick = { openProducts(ProductSort.NO_COST) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = warnColor)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Не указана себестоимость: ${report.missingCost} тов.", fontWeight = FontWeight.SemiBold)
                        Hint("Прибыль по ним завышена. Нажмите, чтобы заполнить.")
                    }
                }
            }
        }

        item { KpiGrid(report, Modifier.padding(horizontal = 16.dp), openProducts, openAds, openPayout, openSettings) }

        if (report.daily.size > 1) item { SalesChart(report, Modifier.padding(horizontal = 16.dp)) }

        item { CostStructure(report.total, Modifier.padding(horizontal = 16.dp)) }

        val sold = report.lines.filter { it.sku != 0L && (it.netQty != 0 || it.b.profit != 0.0) }
        if (sold.isNotEmpty()) {
            item { SectionTitle("Лучшие товары", Modifier.padding(horizontal = 16.dp)) }
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    sold.take(5).forEach { ProductRow(it, onClick = { openProduct(it.sku) }) }
                    TextButton(onClick = { openProducts(ProductSort.PROFIT) }, modifier = Modifier.align(Alignment.End)) { Text("Все товары") }
                }
            }
            val losers = sold.filter { it.b.profit < -0.5 }.sortedBy { it.b.profit }.take(3)
            if (losers.isNotEmpty()) {
                item { SectionTitle("Продаются в минус", Modifier.padding(horizontal = 16.dp)) }
                item {
                    Panel(Modifier.padding(horizontal = 16.dp)) {
                        losers.forEach { ProductRow(it, onClick = { openProduct(it.sku) }) }
                    }
                }
            }
        } else if (!vm.syncing) item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("За период начислений нет", fontWeight = FontWeight.SemiBold)
                Hint("Выберите другой период или обновите данные.")
            }
        }
    }
}

@Composable
private fun ProfitHero(r: Report, modifier: Modifier = Modifier) {
    val t = r.total
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(heroBrush())
            .padding(20.dp)
    ) {
        Column {
            Text("Чистая прибыль · ${dayMonth(r.from)}–${dayMonth(r.to)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(rub(t.b.profit), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeroStat("Маржа", pct(t.margin))
                HeroStat("На 1 шт", rubUnit(t.profitPerUnit))
                HeroStat("На вложенный ₽", if (t.b.cogs < 0) pct(t.roi) else "—")
            }
        }
    }
}

@Composable
private fun HeroStat(title: String, value: String) {
    Column {
        Text(title, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun KpiGrid(
    r: Report, modifier: Modifier, openProducts: (ProductSort) -> Unit,
    openAds: () -> Unit, openPayout: () -> Unit, openSettings: () -> Unit,
) {
    val t = r.total
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Выкуплено", rubShort(t.b.revenue), "${qty(t.netQty)} шт · возвратов ${qty(t.returnQty)}", Modifier.weight(1f), onClick = { openProducts(ProductSort.REVENUE) })
            Kpi("Заказано", rubShort(t.orderedSum), "${qty(t.orderedQty)} шт · отмен ${qty(t.cancelledQty)}", Modifier.weight(1f), onClick = { openProducts(ProductSort.ORDERED) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Реклама", rubShort(-t.b.ads), "ДРР ${pct(t.drr)}", Modifier.weight(1f), onClick = openAds)
            Kpi("К выплате от Ozon", rubShort(t.b.payout), "удержано ${pct(if (t.b.revenue > 0) -t.b.ozon / t.b.revenue else 0.0)}", Modifier.weight(1f), onClick = openPayout)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Оплатили клиенты", rubShort(t.customerPaid), "баллы Ozon ${rubShort(t.points)}", Modifier.weight(1f), onClick = openPayout)
            Kpi("Себестоимость", rubShort(-t.b.cogs), "средний чек ${rubUnit(t.avgPrice)}", Modifier.weight(1f), onClick = { openProducts(ProductSort.COGS) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi(
                "Процент выкупа", t.buyout?.let(::pct) ?: "—",
                if (t.finishedQty > 0) "доставлено ${qty(t.deliveredQty)} из ${qty(t.finishedQty)}" else "заказы ещё в пути",
                Modifier.weight(1f), onClick = { openProducts(ProductSort.BUYOUT) },
            )
            Kpi("Налог", rubShort(-t.b.tax), if (t.b.revenue > 0) "${pct(-t.b.tax / t.b.revenue)} от выкупов" else "", Modifier.weight(1f), onClick = openSettings)
        }
    }
}

private enum class ChartMetric(val title: String, val money: Boolean) {
    REVENUE("Выкуплено, ₽", true), ORDERED("Заказано, ₽", true), PROFIT("Прибыль, ₽", true),
    SOLD_QTY("Выкуплено, шт", false), ORDERED_QTY("Заказано, шт", false)
}

private val monthNames = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")

/** График продаж: что показывать и как сворачивать — по дням, неделям или месяцам. */
@Composable
private fun SalesChart(r: Report, modifier: Modifier = Modifier) {
    var metric by rememberSaveable { mutableStateOf(ChartMetric.REVENUE) }
    var grouping by rememberSaveable { mutableStateOf(Grouping.DAY) }
    var selected by remember(r.from, r.to, grouping) { mutableIntStateOf(-1) }
    val points = remember(r, grouping) { groupPoints(r.daily, grouping) }
    fun value(p: DayPoint): Double = when (metric) {
        ChartMetric.REVENUE -> p.revenue
        ChartMetric.ORDERED -> p.orderedSum
        ChartMetric.PROFIT -> p.profit
        ChartMetric.SOLD_QTY -> p.soldQty.toDouble()
        ChartMetric.ORDERED_QTY -> p.orderedQty.toDouble()
    }
    fun show(v: Double): String = if (metric.money) rub(v) else "${qty(v.toInt())} шт"
    fun label(p: DayPoint): String = when (grouping) {
        Grouping.DAY -> dayMonth(p.date)
        Grouping.WEEK -> "неделя с ${dayMonth(p.date)}"
        Grouping.MONTH -> monthNames[p.date.substring(5, 7).toInt() - 1]
    }
    val values = points.map(::value)
    val sel = selected.takeIf { it in points.indices }

    Panel(modifier) {
        Text("График продаж", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ChoiceChips(ChartMetric.entries.map { it to it.title }, metric, { metric = it })
        ChoiceChips(listOf(Grouping.DAY to "По дням", Grouping.WEEK to "По неделям", Grouping.MONTH to "По месяцам"), grouping, { grouping = it })
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Hint(if (sel != null) label(points[sel]) else "всего за период")
                Text(show(if (sel != null) values[sel] else values.sum()), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            if (values.isNotEmpty()) Hint("в среднем ${show(values.sum() / values.size)}")
        }
        Spacer(Modifier.height(10.dp))
        BarChart(
            values, sel ?: -1, { selected = if (selected == it) -1 else it },
            Modifier.fillMaxWidth().height(140.dp), signed = metric == ChartMetric.PROFIT,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Hint(points.firstOrNull()?.let(::label) ?: "", Modifier.weight(1f))
            Hint(points.lastOrNull()?.let(::label) ?: "")
        }
        Hint("Нажмите на столбик, чтобы увидеть значение.", Modifier.padding(top = 4.dp))
    }
}

@Composable
fun CostStructure(t: SkuReport, modifier: Modifier = Modifier) {
    val rows = costRows(t.b)
    val revenue = t.b.revenue
    Panel(modifier) {
        Text("Куда уходят деньги", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Line("Выкуплено", rub(revenue), bold = true)
        for ((label, v) in rows) {
            val share = if (revenue > 0) -v / revenue else 0.0
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (revenue > 0) pct(share) else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(rub(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = if (v > 0) profitColor else Color.Unspecified)
            }
            ShareBar(share, if (v > 0) profitColor else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), Modifier.padding(top = 4.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Line("Чистая прибыль", rub(t.b.profit), valueColor = signColor(t.b.profit), bold = true, sub = if (revenue > 0) "маржа ${pct(t.margin)}" else "")
    }
}

/** Строка товара в списках: слева артикул и выкупы, справа — показатель выбранной сортировки. */
@Composable
fun ProductRow(l: SkuReport, mode: ProductSort = ProductSort.PROFIT, onClick: () -> Unit) {
    val noCost = if (!l.hasCost && l.netQty > 0) " · нет себестоимости" else ""
    val sub: String
    val main: String
    val mainColor: Color
    val side: String
    when (mode) {
        ProductSort.ORDERED -> {
            sub = "отмен ${qty(l.cancelledQty)} · выкуплено ${qty(l.netQty)} шт"
            main = "${qty(l.orderedQty)} шт"; mainColor = Color.Unspecified; side = rubShort(l.orderedSum)
        }
        ProductSort.BUYOUT -> {
            sub = "доставлено ${qty(l.deliveredQty)} · отменено ${qty(l.cancelledQty)} · в пути ${qty(l.orderedQty - l.finishedQty)}"
            main = l.buyout?.let(::pct) ?: "—"; mainColor = Color.Unspecified; side = "выкуп"
        }
        ProductSort.ADS -> {
            sub = "заказано ${rubShort(l.orderedSum)}"
            main = rub(-l.b.ads); mainColor = Color.Unspecified; side = "ДРР ${pct(l.drr)}"
        }
        ProductSort.REVENUE -> {
            sub = "${qty(l.netQty)} шт · возвратов ${qty(l.returnQty)}$noCost"
            main = rub(l.b.revenue); mainColor = Color.Unspecified; side = "прибыль ${rubShort(l.b.profit)}"
        }
        ProductSort.COGS -> {
            sub = "${qty(l.netQty)} шт$noCost"
            main = rub(-l.b.cogs); mainColor = Color.Unspecified
            side = if (l.netQty > 0) "${rubUnit(-l.b.cogs / l.netQty)} за шт" else ""
        }
        else -> {
            sub = "${qty(l.netQty)} шт · ${rubShort(l.b.revenue)}$noCost"
            main = rub(l.b.profit); mainColor = signColor(l.b.profit); side = if (l.b.revenue > 0) pct(l.margin) else ""
        }
    }
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(l.product)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(productTitle(l.product), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(sub)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(main, fontWeight = FontWeight.SemiBold, color = mainColor)
                Hint(side)
            }
        }
    }
}
