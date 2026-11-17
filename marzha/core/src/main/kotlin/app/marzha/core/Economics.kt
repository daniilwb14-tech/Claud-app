package app.marzha.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Юнит-экономика за период. Все суммы со знаком: выручка — плюс, расходы — минус,
 * так что прибыль — это просто сумма всех статей.
 */
data class Breakdown(
    val revenue: Double = 0.0,
    val commission: Double = 0.0,
    val logistics: Double = 0.0,
    val returnLogistics: Double = 0.0,
    val ads: Double = 0.0,
    val storage: Double = 0.0,
    val acquiring: Double = 0.0,
    val crossdock: Double = 0.0,
    val otherOzon: Double = 0.0,
    val purchase: Double = 0.0,
    val marking: Double = 0.0,
    val packaging: Double = 0.0,
    val otherCost: Double = 0.0,
    val tax: Double = 0.0,
    val manager: Double = 0.0,
    val fixed: Double = 0.0,
) {
    /** Все удержания Ozon. */
    val ozon: Double
        get() = commission + logistics + returnLogistics + ads + storage + acquiring + crossdock + otherOzon

    /** Себестоимость проданного товара. */
    val cogs: Double get() = purchase + marking + packaging + otherCost

    /** К выплате от Ozon: выручка минус удержания. */
    val payout: Double get() = revenue + ozon

    val profit: Double get() = revenue + ozon + cogs + tax + manager + fixed

    operator fun plus(o: Breakdown) = Breakdown(
        revenue + o.revenue, commission + o.commission, logistics + o.logistics,
        returnLogistics + o.returnLogistics, ads + o.ads, storage + o.storage, acquiring + o.acquiring,
        crossdock + o.crossdock, otherOzon + o.otherOzon, purchase + o.purchase, marking + o.marking,
        packaging + o.packaging, otherCost + o.otherCost, tax + o.tax, manager + o.manager, fixed + o.fixed,
    )

    operator fun times(k: Double) = Breakdown(
        revenue * k, commission * k, logistics * k, returnLogistics * k, ads * k, storage * k, acquiring * k,
        crossdock * k, otherOzon * k, purchase * k, marking * k, packaging * k, otherCost * k, tax * k,
        manager * k, fixed * k,
    )
}

data class SkuReport(
    val sku: Long,
    val product: Product,
    val soldQty: Int = 0,
    val returnQty: Int = 0,
    val orderedQty: Int = 0,
    val orderedSum: Double = 0.0,
    val cancelledQty: Int = 0,
    /** Оплатили покупатели, ₽ (без баллов Ozon). */
    val customerPaid: Double = 0.0,
    val b: Breakdown = Breakdown(),
    /** Указана ли себестоимость. */
    val hasCost: Boolean = true,
) {
    val netQty: Int get() = soldQty - returnQty

    /** Маржа: доля прибыли в выручке. */
    val margin: Double get() = if (b.revenue > 0) b.profit / b.revenue else 0.0
    val profitPerUnit: Double get() = if (netQty > 0) b.profit / netQty else 0.0

    /** Рентабельность вложений: прибыль на рубль себестоимости. */
    val roi: Double get() = if (b.cogs < 0) b.profit / -b.cogs else 0.0

    /** ДРР: реклама к сумме заказов; если заказы не загружены — к выручке. */
    val drr: Double
        get() = when {
            orderedSum > 0 -> -b.ads / orderedSum
            b.revenue > 0 -> -b.ads / b.revenue
            else -> 0.0
        }

    /** Доля выкупа: выкуплено к заказано за тот же период (оценка, заказы доезжают с задержкой). */
    val buyout: Double get() = if (orderedQty > 0) (netQty.toDouble() / orderedQty).coerceIn(0.0, 1.0) else 0.0
    val avgPrice: Double get() = if (netQty > 0) b.revenue / netQty else 0.0

    /** Баллы и скидки за счёт Ozon. */
    val points: Double get() = b.revenue - customerPaid
}

data class DayPoint(
    val date: String,
    val revenue: Double,
    val profit: Double,
    val orderedQty: Int,
    val soldQty: Int,
    val orderedSum: Double = 0.0,
    /** Удержания Ozon за день (отрицательное). */
    val ozon: Double = 0.0,
) {
    /** Начислено к выплате за день. */
    val payout: Double get() = revenue + ozon

    operator fun plus(o: DayPoint) = DayPoint(
        date, revenue + o.revenue, profit + o.profit, orderedQty + o.orderedQty, soldQty + o.soldQty,
        orderedSum + o.orderedSum, ozon + o.ozon,
    )
}

enum class Grouping { DAY, WEEK, MONTH }

/** Сворачивает точки по дням в недели (с понедельника) или месяцы; дата точки — первый день отрезка в периоде. */
fun groupPoints(points: List<DayPoint>, by: Grouping): List<DayPoint> {
    if (by == Grouping.DAY) return points
    val out = LinkedHashMap<String, DayPoint>()
    for (p in points) {
        val d = LocalDate.parse(p.date)
        val key = if (by == Grouping.WEEK) d.minusDays(d.dayOfWeek.value - 1L).toString() else p.date.substring(0, 7)
        out[key] = out[key]?.plus(p) ?: p
    }
    return out.values.toList()
}

data class Report(
    val from: LocalDate,
    val to: LocalDate,
    val total: SkuReport,
    val lines: List<SkuReport>,
    val daily: List<DayPoint>,
    /** Сколько продававшихся товаров без себестоимости. */
    val missingCost: Int,
)

/** Общие (без SKU) начисления Ozon как статьи расходов. */
private fun FinDay.toBreakdown() = Breakdown(
    revenue = revenue, commission = commission, logistics = logistics, returnLogistics = returnLogistics,
    ads = ads, storage = storage, acquiring = acquiring, crossdock = crossdock, otherOzon = other,
)

/** Сумма своих расходов, приходящаяся на день [date]. */
fun expensePerDay(expenses: List<Expense>, date: LocalDate): Double = expenses.sumOf { e ->
    when (e.kind) {
        ExpenseKind.MONTHLY -> e.amount / date.lengthOfMonth()
        ExpenseKind.WEEKLY -> e.amount / 7.0
        ExpenseKind.ONCE -> if (e.date == date.toString()) e.amount else 0.0
    }
}

fun expensesFor(expenses: List<Expense>, from: LocalDate, to: LocalDate): Double {
    var sum = 0.0
    var d = from
    while (!d.isAfter(to)) {
        sum += expensePerDay(expenses, d)
        d = d.plusDays(1)
    }
    return sum
}

/**
 * Считает юнит-экономику за период [from; to].
 *
 * Начисления без привязки к товару (sku = 0) и свои постоянные расходы распределяются по товарам
 * пропорционально выручке — как «ведение кабинета по доле выкупов» в недельной таблице.
 */
fun buildReport(
    from: LocalDate,
    to: LocalDate,
    fin: List<FinDay>,
    orders: List<OrderDay>,
    products: Map<Long, Product>,
    costs: Map<Long, UnitCost>,
    expenses: List<Expense>,
    settings: EconSettings,
): Report {
    val f = from.toString()
    val t = to.toString()
    val finIn = fin.filter { it.date in f..t }
    val ordIn = orders.filter { it.date in f..t }

    val finBySku = HashMap<Long, FinDay>()
    for (r in finIn) finBySku[r.sku] = finBySku[r.sku]?.plus(r) ?: r
    val ordBySku = HashMap<Long, OrderDay>()
    for (r in ordIn) ordBySku[r.sku] = ordBySku[r.sku]?.plus(r) ?: r

    val taxRate = settings.taxPct / 100.0
    val mgrRate = settings.managerPct / 100.0

    /** Собственные статьи (себестоимость, налог, менеджер) для набора начислений одного товара. */
    fun own(sku: Long, r: FinDay): Breakdown {
        val c = costs[sku] ?: UnitCost()
        val net = (r.soldQty - r.returnQty).toDouble()
        val base = if (settings.taxBase == TaxBase.CUSTOMER_PAID) r.customerPaid else r.revenue
        return Breakdown(
            purchase = -net * c.purchase, marking = -net * c.marking,
            packaging = -net * c.packaging, otherCost = -net * c.otherTotal,
            tax = -taxRate * max(0.0, base), manager = -mgrRate * max(0.0, r.revenue),
        )
    }

    val shared = finBySku[0L]?.toBreakdown() ?: Breakdown()
    val fixedTotal = -expensesFor(expenses, from, to)
    val skus = (finBySku.keys + ordBySku.keys).filter { it != 0L }
    val revSum = skus.sumOf { max(0.0, finBySku[it]?.revenue ?: 0.0) }

    val lines = ArrayList<SkuReport>()
    for (sku in skus) {
        val r = finBySku[sku] ?: FinDay(f, sku)
        val o = ordBySku[sku]
        val share = if (revSum > 0) max(0.0, r.revenue) / revSum else 0.0
        val b = r.toBreakdown() + own(sku, r) + shared * share + Breakdown(fixed = fixedTotal * share)
        lines += SkuReport(
            sku = sku,
            product = products[sku] ?: Product(sku, name = "SKU $sku"),
            soldQty = r.soldQty, returnQty = r.returnQty,
            orderedQty = o?.orderedQty ?: 0, orderedSum = o?.orderedSum ?: 0.0, cancelledQty = o?.cancelledQty ?: 0,
            customerPaid = r.customerPaid, b = b,
            hasCost = (costs[sku]?.total ?: 0.0) > 0,
        )
    }
    // Продаж не было — общие расходы некуда распределить, показываем их отдельной строкой.
    if (revSum <= 0 && (shared.profit != 0.0 || fixedTotal != 0.0)) {
        lines += SkuReport(0, Product(0, name = "Общие расходы"), b = shared + Breakdown(fixed = fixedTotal))
    }
    lines.sortByDescending { it.b.profit }

    val total = SkuReport(
        sku = 0, product = Product(0, name = "Итого"),
        soldQty = lines.sumOf { it.soldQty }, returnQty = lines.sumOf { it.returnQty },
        orderedQty = lines.sumOf { it.orderedQty }, orderedSum = lines.sumOf { it.orderedSum },
        cancelledQty = lines.sumOf { it.cancelledQty }, customerPaid = lines.sumOf { it.customerPaid },
        b = lines.fold(Breakdown()) { a, l -> a + l.b },
    )

    // По дням — для графика.
    val finByDate = finIn.groupBy { it.date }
    val ordByDate = ordIn.groupBy { it.date }
    val daily = ArrayList<DayPoint>()
    var d = from
    while (!d.isAfter(to)) {
        val key = d.toString()
        val rows = finByDate[key].orEmpty()
        var b = Breakdown(fixed = -expensePerDay(expenses, d))
        for (r in rows) b = b + r.toBreakdown() + if (r.sku != 0L) own(r.sku, r) else Breakdown()
        val ord = ordByDate[key].orEmpty()
        daily += DayPoint(
            key, b.revenue, b.profit,
            ord.sumOf { it.orderedQty }, rows.sumOf { it.soldQty - it.returnQty },
            ord.sumOf { it.orderedSum }, b.ozon,
        )
        d = d.plusDays(1)
    }

    val missing = lines.count { !it.hasCost && it.netQty > 0 }
    return Report(from, to, total, lines, daily, missing)
}

// ───────────────────────── Остатки ─────────────────────────

data class StockLine(
    val sku: Long,
    val product: Product,
    val stock: Stock,
    /** Вложено по себестоимости, ₽. */
    val invested: Double,
    /** Средние продажи в день, шт. */
    val perDay: Double,
    /** На сколько дней хватит остатка; null — продаж нет. */
    val daysLeft: Double?,
    /** Сколько чистыми принесёт остаток при текущей прибыли на штуку. */
    val expectedProfit: Double,
    /** Сколько докупить, чтобы хватило на целевое число дней. */
    val toBuy: Int,
    val hasCost: Boolean,
    /** Сколько выручки даст остаток по средней цене продажи. */
    val expectedRevenue: Double = 0.0,
)

data class StockReport(
    val lines: List<StockLine>,
    val units: Int,
    val invested: Double,
    val expectedProfit: Double,
    val toBuyUnits: Int,
    val toBuySum: Double,
    /** Выручка, если продать весь остаток по текущим средним ценам. */
    val expectedRevenue: Double = 0.0,
) {
    /** Сколько денег вернётся, если продать весь остаток: вложенное плюс прибыль. */
    val moneyBack: Double get() = invested + expectedProfit
}

/**
 * Остатки и деньги в товаре. Скорость продаж и прибыль на штуку берутся из отчёта за последние дни
 * (обычно 30): «если продавать как в прошлом месяце».
 */
fun buildStockReport(
    stocks: List<Stock>,
    recent: Report,
    products: Map<Long, Product>,
    costs: Map<Long, UnitCost>,
    settings: EconSettings,
    /** Сколько дней из отчёта реально загружено; по умолчанию — все дни периода. */
    loadedDays: Int? = null,
): StockReport {
    val days = (loadedDays ?: (ChronoUnit.DAYS.between(recent.from, recent.to) + 1).toInt()).coerceAtLeast(1)
    val bySku = recent.lines.associateBy { it.sku }
    val lines = stocks.filter { it.total > 0 || (bySku[it.sku]?.netQty ?: 0) > 0 }.map { s ->
        val line = bySku[s.sku]
        val cost = costs[s.sku]?.total ?: 0.0
        val perDay = max(0, line?.netQty ?: 0).toDouble() / days
        val need = ceil(perDay * settings.stockTargetDays - s.total).toInt()
        StockLine(
            sku = s.sku,
            product = products[s.sku] ?: line?.product ?: Product(s.sku, name = "SKU ${s.sku}"),
            stock = s,
            invested = s.total * cost,
            perDay = perDay,
            daysLeft = if (perDay > 0) s.total / perDay else null,
            expectedProfit = s.total * (line?.profitPerUnit ?: 0.0),
            toBuy = max(0, need),
            hasCost = cost > 0,
            expectedRevenue = s.total * (line?.avgPrice ?: 0.0),
        )
    }.sortedByDescending { it.invested.takeIf { v -> abs(v) > 0 } ?: it.stock.total.toDouble() / 1e6 }
    return StockReport(
        lines = lines,
        units = lines.sumOf { it.stock.total },
        invested = lines.sumOf { it.invested },
        expectedProfit = lines.sumOf { it.expectedProfit },
        toBuyUnits = lines.sumOf { it.toBuy },
        toBuySum = lines.sumOf { it.toBuy * (costs[it.sku]?.total ?: 0.0) },
        expectedRevenue = lines.sumOf { it.expectedRevenue },
    )
}

// ───────────────────────── Реклама по кампаниям ─────────────────────────

data class CampaignLine(
    val id: String,
    val title: String,
    val views: Long,
    val clicks: Long,
    val spent: Double,
    val orders: Long,
    val ordersSum: Double,
) {
    val drr: Double get() = if (ordersSum > 0) spent / ordersSum else 0.0
    val ctr: Double get() = if (views > 0) clicks.toDouble() / views else 0.0
    val cpo: Double get() = if (orders > 0) spent / orders else 0.0
}

fun campaigns(ads: List<AdDay>, from: LocalDate, to: LocalDate): List<CampaignLine> =
    ads.filter { it.date in from.toString()..to.toString() }.groupBy { it.campaignId }.map { (id, rows) ->
        CampaignLine(
            id, rows.last().title, rows.sumOf { it.views }, rows.sumOf { it.clicks },
            rows.sumOf { it.spent }, rows.sumOf { it.orders }, rows.sumOf { it.ordersSum },
        )
    }.sortedByDescending { it.spent }

/** Отчёт по товарам в CSV (разделитель «;», открывается в Excel и Google Таблицах). */
fun reportCsv(r: Report): String {
    fun n(v: Double) = String.format(java.util.Locale.US, "%.2f", v).replace('.', ',')
    val sb = StringBuilder()
    sb.append("Артикул;Название;SKU;Заказано, шт;Выкуплено, шт;Возвраты, шт;Выкуплено, ₽;Оплатили клиенты, ₽;Комиссия Ozon;Логистика;Обратная логистика;Реклама;Хранение;Эквайринг;Кросс-докинг;Прочие удержания;Закупка;Маркировка;Упаковка;Прочая себестоимость;Налог;Менеджер;Постоянные расходы;ЧИСТАЯ ПРИБЫЛЬ;Маржа, %;Прибыль на 1 шт\n")
    for (l in r.lines + r.total) {
        val b = l.b
        sb.append(
            listOf(
                l.product.offerId, l.product.name.replace(';', ','), if (l.sku == 0L) "" else l.sku.toString(),
                l.orderedQty.toString(), l.soldQty.toString(), l.returnQty.toString(),
                n(b.revenue), n(l.customerPaid), n(b.commission), n(b.logistics), n(b.returnLogistics), n(b.ads),
                n(b.storage), n(b.acquiring), n(b.crossdock), n(b.otherOzon), n(b.purchase), n(b.marking),
                n(b.packaging), n(b.otherCost), n(b.tax), n(b.manager), n(b.fixed), n(b.profit),
                n(l.margin * 100), n(l.profitPerUnit),
            ).joinToString(";")
        ).append('\n')
    }
    return sb.toString()
}
