package app.marzha.core

import kotlin.math.ceil
import kotlin.math.max

/** Остаток и спрос товара в одном кластере Ozon — из аналитики остатков. */
data class ClusterStock(
    val sku: Long,
    val clusterId: Long,
    val cluster: String,
    /** Доступно к продаже на складах кластера. */
    val available: Int = 0,
    /** Едет в кластер: поставки в пути и заявки. */
    val transit: Int = 0,
    /** Возвращается от покупателей в кластере. */
    val returning: Int = 0,
    /** Среднесуточные продажи в кластере за 28 дней, шт. */
    val perDay: Double = 0.0,
) {
    operator fun plus(o: ClusterStock) = copy(
        available = available + o.available,
        transit = transit + o.transit,
        returning = returning + o.returning,
        // Спрос кластера Ozon повторяет в каждой строке склада — берём его, а не складываем.
        perDay = max(perDay, o.perDay),
    )
}

/** Насколько срочно везти товар в кластер. */
enum class ClusterUrgency(val title: String) {
    OUT("закончился — продажи стоят"),
    CRITICAL("хватит меньше чем на неделю"),
    SOON("хватит меньше чем на 2 недели"),
    OK("запаса хватает"),
    EXCESS("лишний: продаж почти нет"),
}

/** Рекомендация по товару в кластере. */
data class ClusterLine(
    val sku: Long,
    val product: Product,
    val stock: ClusterStock,
    /** На сколько дней хватит того, что уже лежит в кластере; null — продаж нет. */
    val daysLeft: Double?,
    /** На сколько хватит вместе с тем, что уже едет. */
    val daysWithTransit: Double?,
    /** Сколько поставить, чтобы хватило на целевое число дней с учётом того, что уже едет. */
    val toShip: Int,
    val urgency: ClusterUrgency,
    /** Сколько выручки в день приносит товар в этом кластере, ₽. */
    val revenuePerDay: Double,
    /** Сколько чистой прибыли в день, ₽. */
    val profitPerDay: Double,
)

/** Итог по кластеру: сколько всего и чего срочно везти. */
data class ClusterSummary(
    val clusterId: Long,
    val cluster: String,
    val lines: List<ClusterLine>,
) {
    val toShip: Int get() = lines.sumOf { it.toShip }
    val urgent: Int get() = lines.count { it.urgency == ClusterUrgency.OUT || it.urgency == ClusterUrgency.CRITICAL }
    val perDay: Double get() = lines.sumOf { it.stock.perDay }
    val revenuePerDay: Double get() = lines.sumOf { it.revenuePerDay }

    /** Самая высокая срочность в кластере. */
    val worst: ClusterUrgency get() = lines.minOfOrNull { it.urgency.ordinal }?.let { ClusterUrgency.entries[it] } ?: ClusterUrgency.OK
}

data class ClusterPlan(val lines: List<ClusterLine>, val clusters: List<ClusterSummary>) {
    /** Что везти в первую очередь: срочные позиции, где больше всего денег в день. */
    val urgent: List<ClusterLine>
        get() = lines.filter { it.toShip > 0 && it.urgency.ordinal <= ClusterUrgency.SOON.ordinal }
            .sortedWith(compareBy<ClusterLine> { it.urgency.ordinal }.thenByDescending { it.revenuePerDay })
}

/**
 * План поставок по кластерам.
 *
 * Спрос — среднесуточные продажи товара в кластере за 28 дней по данным Ozon. Поставить = продажи в день ×
 * целевой запас − (лежит + едет). Срочность — по тому, на сколько дней хватит того, что уже лежит в кластере:
 * пока поставка доедет, продавать будет нечего. Цена и прибыль на штуку берутся из отчёта за 30 дней.
 */
fun buildClusterPlan(
    rows: List<ClusterStock>,
    recent: Report,
    products: Map<Long, Product>,
    settings: EconSettings,
): ClusterPlan {
    val merged = LinkedHashMap<Pair<Long, Long>, ClusterStock>()
    for (r in rows) {
        val key = r.sku to r.clusterId
        merged[key] = merged[key]?.plus(r) ?: r
    }
    val bySku = recent.lines.associateBy { it.sku }
    val target = settings.stockTargetDays.toDouble()
    val lines = merged.values.mapNotNull { s ->
        if (s.available + s.transit + s.returning == 0 && s.perDay <= 0) return@mapNotNull null
        val line = bySku[s.sku]
        val days = if (s.perDay > 0) s.available / s.perDay else null
        val daysT = if (s.perDay > 0) (s.available + s.transit) / s.perDay else null
        val urgency = when {
            s.perDay <= 0.0 -> if (s.available > 0) ClusterUrgency.EXCESS else ClusterUrgency.OK
            s.available == 0 -> ClusterUrgency.OUT
            days!! < 7 -> ClusterUrgency.CRITICAL
            days < 14 -> ClusterUrgency.SOON
            days > 120 -> ClusterUrgency.EXCESS
            else -> ClusterUrgency.OK
        }
        ClusterLine(
            sku = s.sku,
            product = products[s.sku] ?: line?.product ?: Product(s.sku, name = "SKU ${s.sku}"),
            stock = s,
            daysLeft = days,
            daysWithTransit = daysT,
            toShip = max(0, ceil(s.perDay * target - s.available - s.transit).toInt()),
            urgency = urgency,
            revenuePerDay = s.perDay * (line?.avgPrice ?: 0.0),
            profitPerDay = s.perDay * (line?.profitPerUnit ?: 0.0),
        )
    }
    val clusters = lines.groupBy { it.stock.clusterId }
        .map { (id, l) ->
            ClusterSummary(
                id, l.first().stock.cluster,
                l.sortedWith(compareBy<ClusterLine> { it.urgency.ordinal }.thenByDescending { it.revenuePerDay }),
            )
        }
        .sortedWith(compareBy<ClusterSummary> { it.worst.ordinal }.thenByDescending { it.revenuePerDay })
    return ClusterPlan(lines, clusters)
}
