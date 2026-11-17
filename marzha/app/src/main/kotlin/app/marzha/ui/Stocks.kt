package app.marzha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.marzha.AppViewModel
import app.marzha.core.StockLine
import kotlinx.coroutines.launch

private enum class StockSort(val title: String) {
    ALL("Все остатки"), INVESTED("По вложениям"), ENDING("Скоро закончатся"), TO_BUY("Что докупить"), SLOW("Залежались")
}

/** Насколько срочно пополнять товар — определяет цвет строки. */
private enum class Urgency(val label: String) {
    OUT("закончился"), CRITICAL("меньше недели"), SOON("меньше двух недель"), OK("запас есть"), SLOW("залежался"), NO_SALES("нет продаж")
}

private fun urgency(l: StockLine): Urgency {
    val d = l.daysLeft
    return when {
        d == null -> Urgency.NO_SALES
        l.stock.total == 0 -> Urgency.OUT
        d < 7 -> Urgency.CRITICAL
        d < 14 -> Urgency.SOON
        d > 90 -> Urgency.SLOW
        else -> Urgency.OK
    }
}

@Composable
private fun urgencyColor(u: Urgency): Color = when (u) {
    Urgency.OUT, Urgency.CRITICAL -> lossColor
    Urgency.SOON -> warnColor
    Urgency.OK -> profitColor
    Urgency.SLOW -> MaterialTheme.colorScheme.primary
    Urgency.NO_SALES -> MaterialTheme.colorScheme.outline
}

@Composable
fun StocksScreen(vm: AppViewModel, openProduct: (Long) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(StockSort.ALL) }
    val rep = vm.stockReport
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lines = remember(rep, sort) {
        val all = rep?.lines.orEmpty()
        when (sort) {
            StockSort.ALL -> all.filter { it.stock.total > 0 }.sortedByDescending { it.stock.total }
            StockSort.INVESTED -> all.filter { it.invested > 0 }.sortedByDescending { it.invested }
            StockSort.ENDING -> all.filter { it.daysLeft != null }.sortedBy { it.daysLeft }
            StockSort.TO_BUY -> all.filter { it.toBuy > 0 }.sortedByDescending { it.toBuy }
            StockSort.SLOW -> all.filter { it.stock.total > 0 }.sortedByDescending { it.daysLeft ?: Double.MAX_VALUE }
        }
    }
    // Плитка сверху выбирает список и прокручивает к нему.
    fun show(s: StockSort) {
        sort = s
        scope.launch { listState.animateScrollToItem(2) }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Остатки и деньги в товаре", Modifier.padding(start = 4.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("Склады Ozon, свой склад, поставки в пути и возвраты, которые едут обратно на склад.", Modifier.padding(start = 4.dp))
        }
        item { SyncBanner(vm) }
        if (rep == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Вложено в товар", rubShort(rep.invested), "${qty(rep.units)} шт по себестоимости", Modifier.weight(1f), onClick = { show(StockSort.ALL) })
                    Kpi("Вернётся денег", rubShort(rep.moneyBack), "вложенное + прибыль", Modifier.weight(1f), onClick = { show(StockSort.INVESTED) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Принесёт чистой прибыли", rubShort(rep.expectedProfit), "по прибыли с 1 шт за 30 дней", Modifier.weight(1f), signColor(rep.expectedProfit), onClick = { show(StockSort.INVESTED) })
                    Kpi(
                        "Докупить на ${vm.settings.stockTargetDays} дн.", if (rep.toBuyUnits > 0) rubShort(rep.toBuySum) else "Хватает",
                        if (rep.toBuyUnits > 0) "${qty(rep.toBuyUnits)} шт по себестоимости" else "запаса достаточно",
                        Modifier.weight(1f), onClick = { show(StockSort.TO_BUY) },
                    )
                }
            }
        }
        item {
            Hint(
                "Выручка, если продать всё по средним ценам за 30 дней (с баллами Ozon): ${rubShort(rep.expectedRevenue)}. " +
                    "Из неё Ozon удержит комиссию, логистику и рекламу, поэтому вернётся меньше.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
        item { ChoiceChips(StockSort.entries.map { it to it.title }, sort, { sort = it }) }
        item { Legend() }
        items(lines, key = { it.sku }) { l -> StockRow(l, sort, onClick = { openProduct(l.sku) }) }
        if (lines.isEmpty()) item {
            Hint(if (sort == StockSort.TO_BUY) "Докупать ничего не нужно: запаса хватает." else "Здесь пусто.", Modifier.padding(24.dp))
        }
    }
}

/** Расшифровка цветов строк. */
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (u in listOf(Urgency.CRITICAL, Urgency.SOON, Urgency.OK, Urgency.SLOW)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(urgencyColor(u)))
                Spacer(Modifier.width(5.dp))
                Hint(if (u == Urgency.SLOW) "больше 90 дней" else u.label)
            }
        }
    }
}

@Composable
private fun StockRow(l: StockLine, sort: StockSort, onClick: () -> Unit) {
    val u = urgency(l)
    val color = urgencyColor(u)
    val shape = RoundedCornerShape(20.dp)
    val tinted = u == Urgency.OUT || u == Urgency.CRITICAL || u == Urgency.SOON
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            // Срочные строки слегка подсвечены целиком, остальные — только полоской слева.
            .background(if (tinted) color.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
        Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(l.product)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(productTitle(l.product), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val s = l.stock
                Hint(
                    buildList {
                        add("Ozon ${qty(s.fbo)}")
                        if (s.fbs > 0) add("свой склад ${qty(s.fbs)}")
                        if (s.inTransit > 0) add("в пути ${qty(s.inTransit)}")
                        if (s.returning > 0) add("возвраты ${qty(s.returning)}")
                    }.joinToString(" · ")
                )
                Hint("продаётся ${num(l.perDay)} шт в день")
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (sort) {
                        StockSort.TO_BUY -> "+${qty(l.toBuy)} шт"
                        StockSort.INVESTED -> rubShort(l.invested)
                        else -> "${qty(l.stock.total)} шт"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                if (sort != StockSort.INVESTED && l.hasCost) Hint(rubShort(l.invested))
                Box(Modifier.padding(top = 3.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        when (u) {
                            Urgency.NO_SALES -> "нет продаж"
                            Urgency.OUT -> "закончился"
                            else -> l.daysLeft?.let { if (it > 365) "больше года" else "на ${num(it)} дн." } ?: ""
                        },
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color,
                    )
                }
            }
        }
    }
}
