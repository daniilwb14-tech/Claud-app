package app.marzha.core

/** Товар продавца. Ключ везде — SKU Ozon. */
data class Product(
    val sku: Long,
    val offerId: String = "",
    val name: String = "",
    val image: String = "",
)

/** Своя статья себестоимости на штуку, которую продавец добавил сам: «Пошив бирки», «Фулфилмент» и т. п. */
data class ExtraCost(val name: String, val amount: Double)

/** Себестоимость одной штуки, как в листе «Себестоимость»: закупка + маркировка (КИЗ) + упаковка + прочее + свои статьи. */
data class UnitCost(
    val purchase: Double = 0.0,
    val marking: Double = 0.0,
    val packaging: Double = 0.0,
    val other: Double = 0.0,
    val extras: List<ExtraCost> = emptyList(),
) {
    /** Прочее вместе со своими статьями. */
    val otherTotal: Double get() = other + extras.sumOf { it.amount }
    val total: Double get() = purchase + marking + packaging + otherTotal

    /** Свои статьи одной строкой для хранения: «название<TAB>сумма», по строке на статью. */
    fun extrasText(): String = extras.joinToString("\n") { it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.amount }

    companion object {
        fun parseExtras(text: String): List<ExtraCost> = text.split('\n').mapNotNull { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) null else line.substring(i + 1).toDoubleOrNull()?.let { ExtraCost(line.substring(0, i), it) }
        }
    }
}

/**
 * Начисления Ozon за день по одному товару. Суммы со знаком: доход — плюс, удержание — минус.
 * sku = 0 — начисления без привязки к товару (реклама кабинета, хранение, кросс-докинг и т. п.).
 */
data class FinDay(
    val date: String,
    val sku: Long,
    val soldQty: Int = 0,
    val returnQty: Int = 0,
    /** Выкуплено, ₽ (с баллами Ozon). */
    val sales: Double = 0.0,
    /** Возвраты, ₽ (отрицательное). */
    val returns: Double = 0.0,
    /** Сколько из выкупов (за вычетом возвратов) оплатили сами покупатели — без баллов и скидок за счёт Ozon. */
    val customerPaid: Double = 0.0,
    val commission: Double = 0.0,
    val logistics: Double = 0.0,
    val returnLogistics: Double = 0.0,
    val ads: Double = 0.0,
    val storage: Double = 0.0,
    val acquiring: Double = 0.0,
    val crossdock: Double = 0.0,
    val other: Double = 0.0,
) {
    val revenue: Double get() = sales + returns
    val ozonTotal: Double
        get() = commission + logistics + returnLogistics + ads + storage + acquiring + crossdock + other

    operator fun plus(o: FinDay) = copy(
        soldQty = soldQty + o.soldQty,
        returnQty = returnQty + o.returnQty,
        sales = sales + o.sales,
        returns = returns + o.returns,
        customerPaid = customerPaid + o.customerPaid,
        commission = commission + o.commission,
        logistics = logistics + o.logistics,
        returnLogistics = returnLogistics + o.returnLogistics,
        ads = ads + o.ads,
        storage = storage + o.storage,
        acquiring = acquiring + o.acquiring,
        crossdock = crossdock + o.crossdock,
        other = other + o.other,
    )
}

/** Заказы за день по товару (по дате оформления заказа, время московское). */
data class OrderDay(
    val date: String,
    val sku: Long,
    val orderedQty: Int = 0,
    val orderedSum: Double = 0.0,
    val cancelledQty: Int = 0,
) {
    operator fun plus(o: OrderDay) = copy(
        orderedQty = orderedQty + o.orderedQty,
        orderedSum = orderedSum + o.orderedSum,
        cancelledQty = cancelledQty + o.cancelledQty,
    )
}

/** Остаток товара: на складах Ozon, на своём складе, в пути на склад и в возвратах от покупателей. */
data class Stock(
    val sku: Long,
    val fbo: Int = 0,
    val fbs: Int = 0,
    val inTransit: Int = 0,
    val returning: Int = 0,
) {
    val total: Int get() = fbo + fbs + inTransit + returning
}

enum class ExpenseKind { MONTHLY, WEEKLY, ONCE }

/** Свой расход вне Ozon: ведение кабинета, зарплата, аренда, фотосессия и т. п. */
data class Expense(
    val id: Long = 0,
    val name: String,
    val amount: Double,
    val kind: ExpenseKind,
    /** Для разового расхода — дата yyyy-MM-dd. */
    val date: String = "",
)

enum class TaxBase {
    /** Со всей суммы выкупов (с баллами Ozon). */
    ALL,

    /** Только с того, что заплатили покупатели. */
    CUSTOMER_PAID,
}

data class EconSettings(
    /** Налог, % (например 6 = УСН 1% + НДС 5%). */
    val taxPct: Double = 6.0,
    val taxBase: TaxBase = TaxBase.ALL,
    /** Менеджер, % от выкупов. */
    val managerPct: Double = 0.0,
    /** На сколько дней продаж держать запас — для расчёта закупки. */
    val stockTargetDays: Int = 30,
)

/** Рекламная кампания за день (Performance API). */
data class AdDay(
    val date: String,
    val campaignId: String,
    val title: String,
    val views: Long = 0,
    val clicks: Long = 0,
    val spent: Double = 0.0,
    val orders: Long = 0,
    val ordersSum: Double = 0.0,
)
