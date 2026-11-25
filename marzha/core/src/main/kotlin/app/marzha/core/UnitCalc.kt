package app.marzha.core

import kotlin.math.max

/**
 * Входные данные калькулятора нового товара. Проценты — долями (0,2 = 20%).
 * Все суммы — на одну штуку.
 */
data class CalcInput(
    /** Цена продажи на Ozon (ваша цена до скидок за счёт Ozon), ₽. */
    val price: Double = 0.0,
    val purchase: Double = 0.0,
    val marking: Double = 0.0,
    val packaging: Double = 0.0,
    /** Доставка до склада Ozon и прочее на штуку, ₽. */
    val inbound: Double = 0.0,
    /** Комиссия Ozon от цены. */
    val commission: Double = 0.0,
    /** Логистика до покупателя за доставленную штуку, ₽. */
    val logistics: Double = 0.0,
    /** Обратная логистика за невыкупленную штуку, ₽. */
    val returnLogistics: Double = 0.0,
    /** Доля выкупа заказов. */
    val buyout: Double = 0.8,
    /** ДРР от суммы заказов. */
    val drr: Double = 0.0,
    val acquiring: Double = 0.0,
    /** Хранение на штуку, ₽. */
    val storage: Double = 0.0,
    /** Доля цены, которую покрывают баллы и скидки Ozon: влияет только на налог «с оплаты клиента». */
    val ozonPoints: Double = 0.0,
    val taxPct: Double = 0.06,
    val taxBase: TaxBase = TaxBase.ALL,
    val manager: Double = 0.0,
    /** Ожидаемые заказы в день — для прогноза на месяц. */
    val ordersPerDay: Double = 0.0,
    /** На сколько дней продаж закупать первую партию. */
    val stockDays: Int = 30,
) {
    val cogs: Double get() = purchase + marking + packaging + inbound
}

/** Экономика одной проданной штуки. Расходы — отрицательные. */
data class CalcResult(
    val price: Double,
    val commission: Double,
    val logistics: Double,
    /** Логистика туда и обратно за невыкупы, приходящаяся на одну выкупленную штуку. */
    val nonBuyout: Double,
    val ads: Double,
    val acquiring: Double,
    val storage: Double,
    val tax: Double,
    val manager: Double,
    val cogs: Double,
    /** При какой цене прибыль равна нулю; null — не выйти в ноль ни при какой цене. */
    val breakEvenPrice: Double?,
    /** При каком ДРР прибыль равна нулю. */
    val maxDrr: Double,
    val soldPerMonth: Double,
    /** Сколько денег нужно на первую партию. */
    val firstBatch: Double,
    val firstBatchUnits: Int,
) {
    val ozon: Double get() = commission + logistics + nonBuyout + ads + acquiring + storage
    val profit: Double get() = price + ozon + tax + manager + cogs
    val margin: Double get() = if (price > 0) profit / price else 0.0
    val roi: Double get() = if (cogs < 0) profit / -cogs else 0.0
    val monthProfit: Double get() = profit * soldPerMonth
    val monthRevenue: Double get() = price * soldPerMonth

    /** Вывод одной фразой. */
    val verdict: Verdict
        get() = when {
            price <= 0 -> Verdict.EMPTY
            profit < 0 -> Verdict.LOSS
            margin < 0.10 -> Verdict.THIN
            margin < 0.20 -> Verdict.OK
            else -> Verdict.GOOD
        }
}

enum class Verdict(val title: String, val text: String) {
    EMPTY("Введите цену", "Заполните цену и себестоимость — расчёт появится сразу."),
    LOSS("В минус", "При таких цифрах каждая продажа убыточна. Поднимите цену, снизьте ДРР или найдите закупку дешевле."),
    THIN("На грани", "Маржа меньше 10%: любой рост рекламы или логистики уведёт в минус. Запускать рискованно."),
    OK("Можно, но аккуратно", "Маржа 10–20%: товар окупается, но запаса прочности немного."),
    GOOD("Запускать", "Маржа больше 20%: хороший запас на рекламу, акции и рост логистики."),
}

/**
 * Юнит-экономика проданной штуки.
 *
 * Из каждого заказа выкупается доля [CalcInput.buyout]. Невыкупленный заказ стоит логистику туда и обратно,
 * товар возвращается на склад. Реклама платится со всех заказов, поэтому на выкупленную штуку приходится
 * ДРР × цена ÷ выкуп.
 */
fun calcUnit(i: CalcInput): CalcResult {
    val p = i.price
    val b = i.buyout.coerceIn(0.05, 1.0)
    val baseShare = if (i.taxBase == TaxBase.CUSTOMER_PAID) (1 - i.ozonPoints).coerceIn(0.0, 1.0) else 1.0
    val nonBuyout = -(1 - b) / b * (i.logistics + i.returnLogistics)

    // Прибыль линейна по цене: прибыль = цена × k − постоянные расходы на штуку.
    val k = 1 - i.commission - i.drr / b - i.acquiring - i.taxPct * baseShare - i.manager
    val fixed = i.logistics + (1 - b) / b * (i.logistics + i.returnLogistics) + i.storage + i.cogs
    val adsFree = p * (1 - i.commission - i.acquiring - i.taxPct * baseShare - i.manager) - fixed

    val sold = i.ordersPerDay * b * 30
    val batch = (i.ordersPerDay * b * i.stockDays).let { kotlin.math.ceil(it).toInt() }
    return CalcResult(
        price = p,
        commission = -i.commission * p,
        logistics = -i.logistics,
        nonBuyout = nonBuyout,
        ads = -i.drr * p / b,
        acquiring = -i.acquiring * p,
        storage = -i.storage,
        tax = -i.taxPct * p * baseShare,
        manager = -i.manager * p,
        cogs = -i.cogs,
        breakEvenPrice = if (k > 0.001) fixed / k else null,
        maxDrr = if (p > 0) max(0.0, adsFree * b / p) else 0.0,
        soldPerMonth = sold,
        firstBatch = batch * i.cogs,
        firstBatchUnits = batch,
    )
}

/**
 * Цифры для калькулятора из реальных продаж: всего магазина или одного товара за последние 30 дней.
 * Себестоимость берётся из введённой, если она есть.
 */
fun calcFromSales(l: SkuReport, cost: UnitCost?, settings: EconSettings, base: CalcInput = CalcInput()): CalcInput {
    val b = l.b
    val rev = b.revenue
    val n = l.netQty.coerceAtLeast(1)
    val notBought = (l.cancelledQty + l.returnQty).coerceAtLeast(1)
    return base.copy(
        price = if (l.netQty > 0) l.avgPrice else base.price,
        purchase = cost?.purchase ?: base.purchase,
        marking = cost?.marking ?: base.marking,
        packaging = cost?.packaging ?: base.packaging,
        inbound = cost?.otherTotal ?: base.inbound,
        commission = if (rev > 0) -b.commission / rev else base.commission,
        logistics = if (l.netQty > 0) -b.logistics / n else base.logistics,
        returnLogistics = if (b.returnLogistics < 0) -b.returnLogistics / notBought else base.returnLogistics,
        buyout = l.buyout ?: base.buyout,
        drr = if (l.drr > 0) l.drr else base.drr,
        acquiring = if (rev > 0) -b.acquiring / rev else base.acquiring,
        storage = if (l.netQty > 0) -b.storage / n else base.storage,
        ozonPoints = if (rev > 0) (l.points / rev).coerceIn(0.0, 0.95) else base.ozonPoints,
        taxPct = settings.taxPct / 100,
        taxBase = settings.taxBase,
        manager = settings.managerPct / 100,
        stockDays = settings.stockTargetDays,
    )
}

/** Категория из аналитики Ozon «Что продавать → Категории». */
data class CategoryStat(
    val id: String,
    val name: String,
    /** Сумма заказов за период, ₽. */
    val gmv: Double,
    /** Рост суммы заказов к прошлому периоду, доля. */
    val gmvGrowth: Double,
    val items: Double,
    /** Средняя цена, ₽. */
    val avgPrice: Double,
    val buyout: Double,
    val sellers: Double,
    /** Доля топ-5 продавцов. */
    val leaderShare: Double,
)

/** Ответ /v1/analytics/category/comparison. Доли Ozon может отдавать и в процентах — приводим к долям. */
fun parseCategories(text: String): Pair<List<CategoryStat>, Boolean> {
    val root = parseJson(text)
    fun share(v: Double) = if (v > 1.0) v / 100 else v
    val list = root.arr("items").mapNotNull { i ->
        val name = i.str("label")
        if (name.isEmpty()) null else CategoryStat(
            id = i.str("id"), name = name,
            gmv = i.num("metric_gmv"), gmvGrowth = share(i.num("metric_gmv_growth")),
            items = i.num("metric_items"), avgPrice = i.num("metric_aiv"),
            buyout = share(i.num("metric_buyout")), sellers = i.num("metric_sellers"),
            leaderShare = share(i.num("metric_leader_share")),
        )
    }
    val hasNext = ((root as? kotlinx.serialization.json.JsonObject)?.get("has_next") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    return list to hasNext
}
