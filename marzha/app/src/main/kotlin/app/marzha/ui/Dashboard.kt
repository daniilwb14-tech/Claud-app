package app.marzha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marzha.AppViewModel
import app.marzha.core.Breakdown
import app.marzha.core.Report
import app.marzha.core.SkuReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
fun DashboardScreen(vm: AppViewModel, openProduct: (Long) -> Unit, openProducts: () -> Unit, openAds: () -> Unit) {
    val report = vm.report
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Маржа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        item { PeriodChips(vm.period, vm::choosePeriod) }

        if (vm.syncing) item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("Загружаем данные из Ozon", fontWeight = FontWeight.SemiBold)
                Hint(vm.syncStatus)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("Первая загрузка идёт дольше всего. Цифры появляются по мере загрузки.", Modifier.padding(top = 8.dp))
            }
        }
        vm.error?.let { msg ->
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = lossColor)
                        Spacer(Modifier.width(10.dp))
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.error = null }) { Text("Скрыть") }
                        TextButton(onClick = { vm.sync() }, enabled = !vm.syncing) { Text("Повторить") }
                    }
                }
            }
        }

        if (report == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }

        // Период шире загруженной истории.
        val loaded = vm.loadedFrom
        if (!vm.demo && loaded != null && report.from.isBefore(loaded) && !vm.syncing) item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("За этот период загружено не всё", fontWeight = FontWeight.SemiBold)
                Hint("История есть с ${dayMonth(loaded)}, а период начинается ${dayMonth(report.from)}.")
                TextButton(onClick = { vm.sync(report.from) }) { Text("Загрузить с ${dayMonth(report.from)}") }
            }
        }

        item { ProfitHero(report, Modifier.padding(horizontal = 16.dp)) }

        if (report.missingCost > 0) item {
            Panel(Modifier.padding(horizontal = 16.dp), onClick = openProducts) {
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

        item { KpiGrid(report, Modifier.padding(horizontal = 16.dp)) }

        if (report.daily.size > 1) item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("Прибыль по дням", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                ProfitBars(report.daily, Modifier.fillMaxWidth().height(120.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Hint(dayMonth(report.from), Modifier.weight(1f))
                    Hint(dayMonth(report.to))
                }
            }
        }

        item { CostStructure(report.total, Modifier.padding(horizontal = 16.dp)) }

        if (vm.campaignLines.isNotEmpty()) item {
            Panel(Modifier.padding(horizontal = 16.dp), onClick = openAds) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Рекламные кампании", fontWeight = FontWeight.SemiBold)
                        Hint("${vm.campaignLines.size} кампаний · расход ${rub(vm.campaignLines.sumOf { it.spent })}")
                    }
                    Text("Открыть", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }

        val sold = report.lines.filter { it.sku != 0L && (it.netQty != 0 || it.b.profit != 0.0) }
        if (sold.isNotEmpty()) {
            item { SectionTitle("Лучшие товары", Modifier.padding(horizontal = 16.dp)) }
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    sold.take(5).forEach { ProductRow(it, onClick = { openProduct(it.sku) }) }
                    TextButton(onClick = openProducts, modifier = Modifier.align(Alignment.End)) { Text("Все товары") }
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
            .background(Brush.linearGradient(listOf(Color(0xFF0B6E5B), Color(0xFF0A4F5E))))
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
private fun KpiGrid(r: Report, modifier: Modifier = Modifier) {
    val t = r.total
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Выкуплено", rubShort(t.b.revenue), "${qty(t.netQty)} шт · возвратов ${qty(t.returnQty)}", Modifier.weight(1f))
            Kpi("Заказано", rubShort(t.orderedSum), "${qty(t.orderedQty)} шт · отмен ${qty(t.cancelledQty)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Реклама", rubShort(-t.b.ads), "ДРР ${pct(t.drr)}", Modifier.weight(1f))
            Kpi("К выплате от Ozon", rubShort(t.b.payout), "удержано ${pct(if (t.b.revenue > 0) -t.b.ozon / t.b.revenue else 0.0)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Оплатили клиенты", rubShort(t.customerPaid), "баллы Ozon ${rubShort(t.points)}", Modifier.weight(1f))
            Kpi("Себестоимость", rubShort(-t.b.cogs), "средний чек ${rubUnit(t.avgPrice)}", Modifier.weight(1f))
        }
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

/** Строка товара в списках: картинка, артикул, выкупы и прибыль. */
@Composable
fun ProductRow(l: SkuReport, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(l.product)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(productTitle(l.product), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint("${qty(l.netQty)} шт · ${rubShort(l.b.revenue)}" + if (!l.hasCost && l.netQty > 0) " · нет себестоимости" else "")
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rub(l.b.profit), fontWeight = FontWeight.SemiBold, color = signColor(l.b.profit))
                Hint(if (l.b.revenue > 0) pct(l.margin) else "")
            }
        }
    }
}
