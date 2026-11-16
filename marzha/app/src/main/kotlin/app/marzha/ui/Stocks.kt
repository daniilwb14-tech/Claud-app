package app.marzha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marzha.AppViewModel
import app.marzha.core.StockLine

private enum class StockSort(val title: String) { INVESTED("По вложениям"), ENDING("Скоро закончатся"), TO_BUY("Что докупить"), SLOW("Залежались") }

@Composable
fun StocksScreen(vm: AppViewModel, openProduct: (Long) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(StockSort.INVESTED) }
    val rep = vm.stockReport
    val lines = remember(rep, sort) {
        val all = rep?.lines.orEmpty()
        when (sort) {
            StockSort.INVESTED -> all
            StockSort.ENDING -> all.filter { it.daysLeft != null }.sortedBy { it.daysLeft }
            StockSort.TO_BUY -> all.filter { it.toBuy > 0 }.sortedByDescending { it.toBuy }
            StockSort.SLOW -> all.filter { it.stock.total > 0 }.sortedByDescending { it.daysLeft ?: Double.MAX_VALUE }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Остатки и деньги в товаре", Modifier.padding(start = 4.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("Склады Ozon, свой склад, поставки в пути и возвраты.", Modifier.padding(start = 4.dp))
        }
        if (rep == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Вложено в товар", rubShort(rep.invested), "${qty(rep.units)} шт по себестоимости", Modifier.weight(1f))
                    Kpi("Принесёт прибыли", rubShort(rep.expectedProfit), "если продавать как за 30 дней", Modifier.weight(1f), signColor(rep.expectedProfit))
                }
                Kpi(
                    "Докупить на ${vm.settings.stockTargetDays} дней продаж", if (rep.toBuyUnits > 0) rubShort(rep.toBuySum) else "Запаса хватает",
                    if (rep.toBuyUnits > 0) "${qty(rep.toBuyUnits)} шт по себестоимости" else "", Modifier.fillMaxWidth(),
                )
            }
        }
        item { ChoiceChips(StockSort.entries.map { it to it.title }, sort, { sort = it }) }
        items(lines, key = { it.sku }) { l -> StockRow(l, sort == StockSort.TO_BUY, onClick = { openProduct(l.sku) }) }
        if (lines.isEmpty()) item { Hint("Здесь пусто.", Modifier.padding(24.dp)) }
    }
}

@Composable
private fun StockRow(l: StockLine, showBuy: Boolean, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductImage(l.product)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(productTitle(l.product), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val s = l.stock
                Hint(
                    buildList {
                        add("Ozon ${qty(s.fbo)}")
                        if (s.fbs > 0) add("свой ${qty(s.fbs)}")
                        if (s.inTransit > 0) add("в пути ${qty(s.inTransit)}")
                        if (s.returning > 0) add("возвраты ${qty(s.returning)}")
                    }.joinToString(" · ")
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (showBuy) {
                    Text("+${qty(l.toBuy)} шт", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(if (l.hasCost) rubShort(l.invested) else "${qty(l.stock.total)} шт", fontWeight = FontWeight.SemiBold)
                }
                DaysBadge(l.daysLeft)
            }
        }
    }
}

@Composable
private fun DaysBadge(days: Double?) {
    val (text, color) = when {
        days == null -> "нет продаж" to MaterialTheme.colorScheme.onSurfaceVariant
        days < 7 -> "на ${num(days)} дн." to lossColor
        days < 14 -> "на ${num(days)} дн." to warnColor
        days > 365 -> "больше года" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "на ${num(days)} дн." to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
