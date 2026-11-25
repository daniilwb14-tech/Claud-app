package app.marzha.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import app.marzha.core.ClusterLine
import app.marzha.core.ClusterSummary
import app.marzha.core.ClusterUrgency

@Composable
fun clusterColor(u: ClusterUrgency): Color = when (u) {
    ClusterUrgency.OUT, ClusterUrgency.CRITICAL -> lossColor
    ClusterUrgency.SOON -> warnColor
    ClusterUrgency.OK -> profitColor
    ClusterUrgency.EXCESS -> MaterialTheme.colorScheme.outline
}

/** Объяснение рекомендации человеческим языком. */
fun clusterAdvice(l: ClusterLine, targetDays: Int): String {
    val s = l.stock
    val sold = "здесь продаётся ${num(s.perDay)} шт в день"
    val has = buildList {
        add("лежит ${qty(s.available)}")
        if (s.transit > 0) add("едет ${qty(s.transit)}")
    }.joinToString(", ")
    return when (l.urgency) {
        ClusterUrgency.OUT ->
            "Товар закончился, а $sold. Каждый день без товара — около ${rubShort(l.revenuePerDay)} выручки и " +
                "${rubShort(l.profitPerDay)} прибыли мимо. " + if (l.toShip > 0) "Поставьте ${qty(l.toShip)} шт — хватит на $targetDays дн." else "Поставка уже едет."
        ClusterUrgency.CRITICAL, ClusterUrgency.SOON ->
            "Срочно: $sold, $has — того, что на складе, хватит на ${num(l.daysLeft ?: 0.0)} дн. " +
                if (l.toShip > 0) "Поставьте ${qty(l.toShip)} шт, чтобы хватило на $targetDays дн." else "Того, что едет, хватит — следите, чтобы поставка дошла вовремя."
        ClusterUrgency.OK ->
            if (l.toShip > 0) "Запас есть ($has, хватит на ${num(l.daysWithTransit ?: 0.0)} дн.). Чтобы держать $targetDays дн., добавьте ${qty(l.toShip)} шт со следующей поставкой."
            else "Запаса хватает: $has, $sold."
        ClusterUrgency.EXCESS ->
            if (s.perDay <= 0) "Лежит ${qty(s.available)} шт, а продаж в кластере почти нет. Сюда больше не везите."
            else "Запас на ${num(l.daysLeft ?: 0.0)} дн. — это много. Сюда пока не везите, лучше в кластеры, где товар заканчивается."
    }
}

/** Карточка «Что срочно везти» для вкладки «Остатки». */
@Composable
fun UrgentClustersCard(vm: AppViewModel, openClusters: () -> Unit, openProduct: (Long) -> Unit) {
    val plan = vm.clusterPlan
    Panel {
        Text("Куда срочно везти", fontWeight = FontWeight.SemiBold)
        if (plan == null) {
            Hint("Разбивки по кластерам пока нет. Она загружается вместе с остатками из аналитики Ozon — нажмите «Обновить».")
            return@Panel
        }
        val urgent = plan.urgent
        if (urgent.isEmpty()) {
            Hint("Во всех кластерах товара хватает больше чем на 2 недели.")
        } else {
            Hint("По продажам в каждом кластере за 28 дней. Сверху — то, что закончилось или вот-вот закончится там, где хорошо продаётся.")
            Spacer(Modifier.height(6.dp))
            urgent.take(6).forEach { l -> UrgentRow(l, onClick = { openProduct(l.sku) }) }
        }
        TextButton(onClick = openClusters) { Text("Все кластеры и поставки") }
    }
}

@Composable
private fun UrgentRow(l: ClusterLine, onClick: () -> Unit) {
    val color = clusterColor(l.urgency)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(productTitle(l.product), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Кластер — отдельной строкой, чтобы длинное название товара его не обрезало.
            Text("в кластер: ${l.stock.cluster}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2)
            Hint(
                if (l.urgency == ClusterUrgency.OUT) "закончился · продавалось ${num(l.stock.perDay)} шт/день"
                else "хватит на ${num(l.daysLeft ?: 0.0)} дн. · ${num(l.stock.perDay)} шт/день"
            )
        }
        Text("+${qty(l.toShip)} шт", fontWeight = FontWeight.SemiBold, color = color)
    }
}

private enum class ClusterView(val title: String) { CLUSTERS("По кластерам"), URGENT("Срочно"), EXCESS("Лишнее") }

/** Все кластеры: что лежит, как продаётся и сколько везти. */
@Composable
fun ClustersScreen(vm: AppViewModel, back: () -> Unit, openProduct: (Long) -> Unit) {
    var view by rememberSaveable { mutableStateOf(ClusterView.CLUSTERS) }
    val plan = vm.clusterPlan
    val target = vm.settings.stockTargetDays
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Поставки по кластерам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (plan == null) {
                item { Panel { Hint("Разбивки по кластерам пока нет — она загружается вместе с остатками. Нажмите «Обновить» на главной.") } }
                return@LazyColumn
            }
            item {
                val urgent = plan.urgent
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Срочно везти", "${qty(urgent.sumOf { it.toShip })} шт", "${urgent.size} позиций в ${urgent.map { it.stock.clusterId }.distinct().size} кластерах", Modifier.weight(1f), lossColor)
                    Kpi("Всего до запаса на $target дн.", "${qty(plan.lines.sumOf { it.toShip })} шт", "по всем кластерам", Modifier.weight(1f))
                }
            }
            item {
                Hint(
                    "Спрос — среднесуточные продажи товара в кластере за 28 дней по данным Ozon. Поставить = продажи в день × $target дн. − " +
                        "(лежит + едет). Красный — меньше недели или уже закончился, жёлтый — меньше двух недель. Целевой запас меняется в настройках.",
                    Modifier.padding(horizontal = 4.dp),
                )
            }
            item { ChoiceChips(ClusterView.entries.map { it to it.title }, view, { view = it }) }
            when (view) {
                ClusterView.CLUSTERS -> items(plan.clusters, key = { it.clusterId }) { c -> ClusterCard(c, target, openProduct) }
                ClusterView.URGENT -> {
                    val list = plan.urgent
                    if (list.isEmpty()) item { Hint("Срочных поставок нет.", Modifier.padding(16.dp)) }
                    items(list, key = { "${it.sku}-${it.stock.clusterId}" }) { l -> AdviceCard(l, target, showCluster = true, onClick = { openProduct(l.sku) }) }
                }
                ClusterView.EXCESS -> {
                    val list = plan.lines.filter { it.urgency == ClusterUrgency.EXCESS }.sortedByDescending { it.stock.available }
                    if (list.isEmpty()) item { Hint("Залежавшегося товара в кластерах нет.", Modifier.padding(16.dp)) }
                    items(list, key = { "${it.sku}-${it.stock.clusterId}" }) { l -> AdviceCard(l, target, showCluster = true, onClick = { openProduct(l.sku) }) }
                }
            }
        }
    }
}

@Composable
private fun ClusterCard(c: ClusterSummary, target: Int, openProduct: (Long) -> Unit) {
    var open by rememberSaveable(c.clusterId) { mutableStateOf(c.urgent > 0) }
    val color = clusterColor(c.worst)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.cluster, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint("${num(c.perDay)} шт в день · ${rubShort(c.revenuePerDay)} в день" + if (c.urgent > 0) " · срочно: ${c.urgent}" else "")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (c.toShip > 0) "+${qty(c.toShip)} шт" else "хватает", fontWeight = FontWeight.SemiBold, color = if (c.toShip > 0) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    Hint(if (open) "свернуть" else "подробнее")
                }
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.lines.forEach { l -> AdviceCard(l, target, showCluster = false, onClick = { openProduct(l.sku) }) }
            }
        }
    }
}

@Composable
private fun AdviceCard(l: ClusterLine, target: Int, showCluster: Boolean, onClick: () -> Unit) {
    val color = clusterColor(l.urgency)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showCluster) "${productTitle(l.product)} → ${l.stock.cluster}" else productTitle(l.product),
                    Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (l.toShip > 0) Text("+${qty(l.toShip)} шт", fontWeight = FontWeight.SemiBold, color = color)
            }
            Text(l.urgency.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.height(2.dp))
            Hint(clusterAdvice(l, target))
        }
    }
}

/** Разбивка одного товара по кластерам — для карточки товара. */
@Composable
fun ProductClusters(vm: AppViewModel, sku: Long) {
    val lines = vm.clusterPlan?.lines.orEmpty().filter { it.sku == sku }
        .sortedWith(compareBy<ClusterLine> { it.urgency.ordinal }.thenByDescending { it.stock.perDay })
    if (lines.isEmpty()) return
    Panel {
        Text("По кластерам", fontWeight = FontWeight.SemiBold)
        Hint("Продажи в день за 28 дней, что лежит и сколько поставить на ${vm.settings.stockTargetDays} дн.")
        Spacer(Modifier.height(6.dp))
        lines.forEach { l ->
            val color = clusterColor(l.urgency)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.stock.cluster, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(
                        "${num(l.stock.perDay)} шт/день · лежит ${qty(l.stock.available)}" +
                            (if (l.stock.transit > 0) " · едет ${qty(l.stock.transit)}" else "") +
                            (l.daysLeft?.let { " · на ${num(it)} дн." } ?: "")
                    )
                }
                Text(if (l.toShip > 0) "+${qty(l.toShip)}" else "—", fontWeight = FontWeight.SemiBold, color = if (l.toShip > 0) color else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
