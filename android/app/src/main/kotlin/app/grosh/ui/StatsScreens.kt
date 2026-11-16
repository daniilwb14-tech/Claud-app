package app.grosh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grosh.AppViewModel
import app.grosh.core.Insights
import app.grosh.core.Marketplace
import app.grosh.core.SubscriptionDetector
import app.grosh.data.Entry
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: Аналитика

@Composable
fun AnalyticsScreen(vm: AppViewModel, entries: List<Entry>) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val expenses = entries.inMonth(month).expenses()
    val total = expenses.total()
    val previous = entries.inMonth(month.minusMonths(1)).expenses().total()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Аналитика", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹ Раньше") }
            Text(monthTitle(month), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("Позже ›") }
        }

        if (expenses.isEmpty()) {
            EmptyState("📊", "Нет расходов за месяц", "Добавьте операции, и здесь появится разбивка.")
            return@Column
        }

        GCard {
            Text("Расходы за месяц", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(moneyShort(total), fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (previous > 0) {
                val change = ((total - previous) * 100.0 / previous).roundToInt()
                Text(
                    "${if (change >= 0) "↗ +" else "↘ −"}${abs(change)}% к прошлому месяцу",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    color = if (change > 0) Color(0xFFE08600) else IncomeColor,
                )
            }
            Insights.workHoursText(total, vm.hourlyRate * 100)?.let {
                Text("🕒 Это $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val slices = expenses.byCategory()
        GCard {
            Text("По категориям", fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                Donut(slices.map { it.first.color to it.second.toFloat() }, Modifier.size(180.dp))
            }
            slices.forEach { (category, sum) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(category.emoji, category.color, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(category.title, Modifier.weight(1f))
                    Text("${sum * 100 / total}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(moneyShort(sum), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        GCard {
            Text("По дням", fontWeight = FontWeight.SemiBold)
            val byDay = expenses.groupBy { it.day.dayOfMonth }.mapValues { it.value.total() }
            val bars = (1..month.lengthOfMonth()).map { listOf(Brand to (byDay[it] ?: 0L).toFloat()) }
            Bars(bars, Modifier.fillMaxWidth().height(150.dp).padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${month.lengthOfMonth()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        GCard {
            Text("Куда уходит больше всего", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            expenses.groupBy { it.title }.map { Triple(it.key, it.value.total(), it.value.size) }
                .sortedByDescending { it.second }.take(5)
                .forEach { (name, sum, count) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Покупок: $count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(moneyShort(sum), fontWeight = FontWeight.SemiBold)
                    }
                }
        }
    }
}

/** Кольцевая диаграмма: сегменты с небольшими зазорами. */
@Composable
private fun Donut(slices: List<Pair<Color, Float>>, modifier: Modifier) {
    val total = slices.sumOf { it.second.toDouble() }.toFloat()
    Canvas(modifier) {
        if (total <= 0f) return@Canvas
        val stroke = size.minDimension * 0.17f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val gap = if (slices.size > 1) 2.5f else 0f
        var start = -90f
        for ((color, value) in slices) {
            val sweep = value / total * 360f
            if (sweep > gap) {
                drawArc(color, start + gap / 2, sweep - gap, useCenter = false, topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            start += sweep
        }
    }
}

/** Столбчатая диаграмма; каждый столбец может состоять из нескольких цветных частей. */
@Composable
private fun Bars(columns: List<List<Pair<Color, Float>>>, modifier: Modifier) {
    val max = columns.maxOfOrNull { column -> column.sumOf { it.second.toDouble() } }?.toFloat() ?: 0f
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (columns.size > 12) 2.dp else 10.dp), verticalAlignment = Alignment.Bottom) {
        columns.forEach { parts ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                val sum = parts.sumOf { it.second.toDouble() }.toFloat()
                if (sum <= 0f || max <= 0f) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(track))
                } else {
                    Column(Modifier.fillMaxWidth().fillMaxHeight((sum / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp))) {
                        parts.filter { it.second > 0f }.forEach { (color, value) ->
                            Box(Modifier.fillMaxWidth().weight(value).background(color))
                        }
                    }
                }
            }
        }
    }
}

// MARK: Маркетплейсы

@Composable
fun MarketsScreen(entries: List<Entry>) {
    val marketEntries = entries.filter { !it.isIncome && it.market != null }
    val month = YearMonth.now()
    val monthEntries = marketEntries.inMonth(month)
    val monthAll = entries.inMonth(month).expenses().total()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Маркетплейсы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (marketEntries.isEmpty()) {
            EmptyState("📦", "Покупок на маркетплейсах пока нет", "Грош сам узнаёт Ozon, Wildberries, AliExpress, Яндекс Маркет, Мегамаркет и Lamoda по названию продавца в операции.")
            return@Column
        }

        val total = monthEntries.total()
        GCard {
            Text("В этом месяце", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(moneyShort(total), fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "${if (monthAll > 0) total * 100 / monthAll else 0}% всех расходов · покупок: ${monthEntries.size}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        monthEntries.byMarketplace().forEach { item ->
            GCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(item.marketplace.color), contentAlignment = Alignment.Center) {
                        Text(item.marketplace.title.take(1), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.marketplace.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Покупок: ${item.count} · средний чек ${moneyShort(item.total / item.count)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(moneyShort(item.total), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Последние 6 месяцев по площадкам.
        val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        val present = Marketplace.entries.filter { market -> marketEntries.any { it.market == market && YearMonth.from(it.day) >= months.first() } }
        GCard {
            Text("За полгода", fontWeight = FontWeight.SemiBold)
            val columns = months.map { m ->
                val inMonth = marketEntries.inMonth(m)
                present.map { market -> market.color to inMonth.filter { it.market == market }.total().toFloat() }
            }
            Bars(columns, Modifier.fillMaxWidth().height(170.dp).padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                months.forEach { m ->
                    Text(
                        m.format(DateTimeFormatter.ofPattern("LLL", Locale("ru", "RU"))).take(3),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            // Легенда словами: цвет не единственный носитель смысла.
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                present.forEach { market ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(market.color))
                        Spacer(Modifier.width(5.dp))
                        Text(market.shortTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        GCard {
            Text("Самые крупные покупки", fontWeight = FontWeight.SemiBold)
            marketEntries.sortedByDescending { it.amountMinor }.take(5).forEach { EntryRow(it) }
        }
    }
}

// MARK: Радар подписок

@Composable
fun SubscriptionsScreen(entries: List<Entry>, onClose: () -> Unit) {
    val subs = remember(entries) {
        SubscriptionDetector.detect(entries.expenses().map { SubscriptionDetector.Charge(it.title, it.amountMinor, it.dateMillis) })
    }
    val monthly = subs.sumOf { it.monthlyCostMinor }
    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale("ru", "RU")).withZone(ZoneId.systemDefault())

    OverlayScaffold("Радар подписок", onClose) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (subs.isEmpty()) {
                EmptyState("🔁", "Подписок не найдено", "Радар замечает списания, которые повторяются каждую неделю, месяц или год. Нужна история хотя бы за два-три месяца.")
                return@Column
            }
            GCard {
                Text("Подписки съедают", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${moneyShort(monthly)} в месяц", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Это ${moneyShort(monthly * 12)} в год", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GCard {
                Text("Найдено: ${subs.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                subs.forEach { sub ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        EmojiBadge("🔁", Color(0xFF32ADE6))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sub.merchant, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${sub.periodTitle} · следующее ${dateFormat.format(Instant.ofEpochMilli(sub.nextDateMillis))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(money(sub.amountMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
