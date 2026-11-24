package app.marzha.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/*
 * Разбор ответов Ozon Seller API. Всё читается деревом и терпимо к отсутствующим полям:
 * Ozon часто меняет схемы, и лучше показать чуть меньше, чем упасть.
 */

internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJson(text: String): JsonObject =
    (json.parseToJsonElement(text) as? JsonObject) ?: JsonObject(emptyMap())

internal fun JsonElement?.obj(key: String): JsonObject? = ((this as? JsonObject)?.get(key)) as? JsonObject
internal fun JsonElement?.arr(key: String): List<JsonElement> =
    (((this as? JsonObject)?.get(key)) as? JsonArray) ?: emptyList()

internal fun JsonElement?.str(key: String): String {
    val v = (this as? JsonObject)?.get(key)
    return if (v is JsonPrimitive && v !is JsonNull) v.content else ""
}

internal fun JsonElement?.num(key: String): Double = toNum((this as? JsonObject)?.get(key))
internal fun JsonElement?.long(key: String): Long = num(key).toLong()
internal fun JsonElement?.int(key: String): Int = num(key).toInt()

/** Число из числа, строки («1259.0000», «12,34») или объекта денег {amount}. */
internal fun toNum(v: JsonElement?): Double = when (v) {
    null, is JsonNull -> 0.0
    is JsonPrimitive -> v.content.replace(" ", "").replace("\u00A0", "").replace(',', '.').toDoubleOrNull() ?: 0.0
    is JsonObject -> toNum(v["amount"])
    else -> 0.0
}

/** Сумма из поля-объекта денег: {"amount": "-12.5", "currency": "RUB"}. */
internal fun JsonElement?.money(key: String): Double = toNum((this as? JsonObject)?.get(key))

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

/** Дата (yyyy-MM-dd, Москва) из отметки времени ISO; пустая строка, если не разобрать. */
fun moscowDate(iso: String): String {
    if (iso.length < 10) return ""
    return try {
        OffsetDateTime.parse(iso).atZoneSameInstant(MOSCOW).toLocalDate().toString()
    } catch (e: Exception) {
        try {
            Instant.parse(iso).atZone(MOSCOW).toLocalDate().toString()
        } catch (e2: Exception) {
            iso.substring(0, 10)
        }
    }
}

// ───────────────────────── Виды начислений ─────────────────────────

enum class FeeClass(val title: String) {
    SALES("Продажи и возвраты"),
    COMMISSION("Комиссия"),
    ACQUIRING("Эквайринг"),
    ADS("Реклама"),
    CROSSDOCK("Кросс-докинг"),
    STORAGE("Хранение"),
    RETURN_LOGISTICS("Обратная логистика"),
    LOGISTICS("Логистика"),
    OTHER("Прочие начисления"),

    /** Перевод денег продавцу — это не расход, в прибыль не входит. */
    TRANSFER("Выплаты на счёт — не расход"),
}

/** Относит вид начисления к статье расходов по его названию из справочника Ozon. */
fun classifyFee(name: String): FeeClass {
    val n = name.lowercase()
    fun has(vararg keys: String) = keys.any { n.contains(it) }
    return when {
        // Перевод заработанного на счёт продавца — не расход. Но «Досрочная выплата» и подобное — это плата Ozon
        // за услугу, то есть расход: такие названия сюда не попадают.
        (has("выплат", "перечислен", "перевод") && has("продавц", "счёт", "счет", "расчётн", "расчетн") &&
            !has("досроч", "комисси", "плата за", "услуг", "штраф", "компенсац", "возмещ", "покупател", "доставк")) -> FeeClass.TRANSFER
        has("эквайринг") -> FeeClass.ACQUIRING
        has(
            "реклам", "продвижен", "трафарет", "клик", "вывод в топ", "бренд", "отзыв", "рассылк",
            "оплата за заказ", "буст", "баннер", "спецразмещ", "медийн",
        ) -> FeeClass.ADS
        has("кросс-док", "кроссдок", "кросс док") -> FeeClass.CROSSDOCK
        has("хранен", "размещени") -> FeeClass.STORAGE
        has("возврат", "обратн", "невыкуп", "отмен", "утилизац") -> FeeClass.RETURN_LOGISTICS
        has(
            "логистик", "доставк", "магистрал", "последняя миля", "сборк", "обработк", "drop-off",
            "дроп-офф", "выдач", "отгрузк", "курьер", "перевозк", "транспорт",
        ) -> FeeClass.LOGISTICS
        else -> FeeClass.OTHER
    }
}

/** Справочник видов начислений: id → класс. Незнакомые id попадают в «прочее». */
class FeeTypes(private val byId: Map<Int, FeeClass> = emptyMap(), val names: Map<Int, String> = emptyMap()) {
    fun classOf(typeId: Int): FeeClass = byId[typeId] ?: FeeClass.OTHER

    companion object {
        /** Ответ /v1/finance/accrual/types. */
        fun parse(text: String): FeeTypes {
            val cls = HashMap<Int, FeeClass>()
            val names = HashMap<Int, String>()
            for (t in parseJson(text).arr("accrual_types")) {
                val id = t.int("id")
                val name = t.str("name")
                // Название короткое («Логистика»), описание подробнее — смотрим оба.
                var c = classifyFee(name)
                if (c == FeeClass.OTHER) c = classifyFee(t.str("description"))
                cls[id] = c
                names[id] = name
            }
            return FeeTypes(cls, names)
        }
    }
}

// ───────────────────────── Начисления за день ─────────────────────────

/** Сумма за день по одному виду начисления — для экрана проверки данных. */
data class TypeTotal(val name: String, val cls: FeeClass, val amount: Double, val count: Int = 1) {
    operator fun plus(o: TypeTotal) = copy(amount = amount + o.amount, count = count + o.count)
}

/** Копилка сумм по видам начислений. */
class TypeAccumulator {
    private val map = LinkedHashMap<String, TypeTotal>()

    fun add(name: String, cls: FeeClass, amount: Double) {
        if (abs(amount) < 0.005) return
        val t = TypeTotal(name, cls, amount)
        map[name] = map[name]?.plus(t) ?: t
    }

    fun addAll(list: List<TypeTotal>) = list.forEach { t -> map[t.name] = map[t.name]?.plus(t) ?: t }
    fun list(): List<TypeTotal> = map.values.toList()
}

class FinPage(val rows: List<FinDay>, val lastId: String, val count: Int, val types: List<TypeTotal> = emptyList())

/** Копилка начислений по ключу «дата + SKU». */
class FinAccumulator {
    private val map = LinkedHashMap<String, FinDay>()

    fun add(row: FinDay) {
        val key = row.date + "|" + row.sku
        val old = map[key]
        map[key] = if (old == null) row else old + row
    }

    fun rows(): List<FinDay> = map.values.toList()
}

private fun feeRow(date: String, sku: Long, cls: FeeClass, amount: Double): FinDay {
    val base = FinDay(date, sku)
    return when (cls) {
        FeeClass.ACQUIRING -> base.copy(acquiring = amount)
        FeeClass.ADS -> base.copy(ads = amount)
        FeeClass.CROSSDOCK -> base.copy(crossdock = amount)
        FeeClass.STORAGE -> base.copy(storage = amount)
        FeeClass.RETURN_LOGISTICS -> base.copy(returnLogistics = amount)
        FeeClass.LOGISTICS -> base.copy(logistics = amount)
        FeeClass.OTHER, FeeClass.SALES, FeeClass.COMMISSION -> base.copy(other = amount)
        FeeClass.TRANSFER -> base
    }
}

/**
 * Разбирает страницу /v1/finance/accrual/by-day в начисления по дням и товарам.
 *
 * Ozon отдаёт по товару отправления: «реализовано на сумму», комиссию, баллы за скидки, соинвестирование
 * и услуги доставки. Что именно входит в «реализовано» (с баллами или без) и с каким знаком идёт комиссия,
 * в документации не сказано, поэтому каждое начисление сверяется со своим итогом `total_amount`:
 * выбирается то прочтение, при котором сумма частей сходится с итогом. Несошедшийся остаток уходит в «прочее»,
 * так что общая сумма всегда равна тому, что начислил Ozon.
 */
fun parseAccruals(text: String, types: FeeTypes, fallbackDate: String): FinPage {
    val root = parseJson(text)
    val acc = FinAccumulator()
    val tt = TypeAccumulator()
    fun typeName(id: Int) = types.names[id] ?: "Вид начисления $id"
    val list = root.arr("accruals")
    for (a in list) {
        val date = a.str("date").take(10).ifEmpty { fallbackDate }
        val total = a.money("total_amount")
        var parsed = 0.0
        var mainSku = 0L

        // Начисления по отправлению: продажа или возврат и услуги доставки.
        val products = a.obj("posting").arr("products")
        if (products.isNotEmpty()) {
            class P(val sku: Long, val qty: Int, val sale: Double, val comm: Double, val bonus: Double, val deliv: Double)

            val ps = ArrayList<P>()
            for (p in products) {
                val sku = p.long("sku")
                if (mainSku == 0L) mainSku = sku
                val c = p.obj("commission")
                val delivery = p.obj("delivery")
                var deliv = 0.0
                for (s in delivery.arr("services")) {
                    val amount = s.money("accrued")
                    var cls = types.classOf(s.int("type_id"))
                    deliv += amount
                    // Услуги доставки у возврата — это обратная логистика.
                    if (cls == FeeClass.OTHER) cls = FeeClass.LOGISTICS
                    if (cls == FeeClass.LOGISTICS && c.money("sale_amount") < 0) cls = FeeClass.RETURN_LOGISTICS
                    acc.add(feeRow(date, sku, cls, amount))
                    tt.add(typeName(s.int("type_id")), cls, amount)
                }
                // Услуг списком нет, а итог по доставке есть.
                if (delivery.arr("services").isEmpty()) {
                    val t = delivery.money("total_accrued")
                    if (t != 0.0) {
                        deliv += t
                        val cls = if (c.money("sale_amount") < 0) FeeClass.RETURN_LOGISTICS else FeeClass.LOGISTICS
                        acc.add(feeRow(date, sku, cls, t))
                        tt.add("Доставка (без расшифровки)", cls, t)
                    }
                }
                ps += P(
                    sku, p.int("quantity").coerceAtLeast(1),
                    c.money("sale_amount"), c.money("commission"),
                    c.money("bonus") + c.money("coinvestment"), deliv,
                )
            }
            val sale = ps.sumOf { it.sale }
            val comm = ps.sumOf { it.comm }
            val bonus = ps.sumOf { it.bonus }
            val deliv = ps.sumOf { it.deliv }
            // Прочтения: комиссия со знаком как есть или наоборот; баллы внутри «реализовано» или сверху;
            // возврат отрицательной суммой или положительной. Берём то, что сходится с итогом начисления.
            var best = 0
            if (a.obj("total_amount") != null) {
                var bestErr = Double.MAX_VALUE
                for (v in 0..7) {
                    val cs = if (v and 1 == 0) 1.0 else -1.0
                    val bs = if (v and 2 == 0) 0.0 else 1.0
                    val ss = if (v and 4 == 0) 1.0 else -1.0
                    val err = abs(total - (ss * (sale + bs * bonus) + cs * comm + deliv))
                    if (err < bestErr - 0.005) {
                        bestErr = err
                        best = v
                    }
                }
            }
            val commSign = if (best and 1 == 0) 1.0 else -1.0
            val bonusOnTop = best and 2 != 0
            val saleSign = if (best and 4 == 0) 1.0 else -1.0
            for (p in ps) {
                val sale1 = saleSign * p.sale
                val bonus1 = saleSign * p.bonus
                val revenue = if (bonusOnTop) sale1 + bonus1 else sale1
                // Покупатель заплатил выручку за вычетом баллов и соинвестирования Ozon.
                val paid = if (bonusOnTop) sale1 else sale1 - if (sale1 >= 0) abs(bonus1) else -abs(bonus1)
                val commission = commSign * p.comm
                acc.add(
                    FinDay(
                        date = date, sku = p.sku,
                        soldQty = if (revenue > 0) p.qty else 0,
                        returnQty = if (revenue < 0) p.qty else 0,
                        sales = if (revenue > 0) revenue else 0.0,
                        returns = if (revenue < 0) revenue else 0.0,
                        customerPaid = paid,
                        commission = commission,
                    )
                )
                parsed += revenue + commission + p.deliv
                tt.add(if (revenue >= 0) "Продажи (реализовано)" else "Возвраты (реализовано)", FeeClass.SALES, revenue)
                tt.add("Комиссия за продажу", FeeClass.COMMISSION, commission)
            }
        }

        // Начисления по товару без отправления (продвижение с оплатой за заказ, хранение по товару и т. п.).
        for (item in a.obj("item_fees").arr("fees")) {
            val sku = item.long("sku")
            if (mainSku == 0L) mainSku = sku
            for (f in item.arr("fees")) {
                val amount = f.money("accrued")
                val cls = types.classOf(f.int("type_id"))
                acc.add(feeRow(date, sku, cls, amount))
                tt.add(typeName(f.int("type_id")), cls, amount)
                parsed += amount
            }
        }

        // Начисление по продавцу без привязки к товару.
        a.obj("non_item_fee")?.let { f ->
            val amount = f.money("accrued")
            if (amount != 0.0) {
                val cls = types.classOf(f.int("type_id"))
                acc.add(feeRow(date, 0, cls, amount))
                tt.add(typeName(f.int("type_id")), cls, amount)
                parsed += amount
            }
        }

        // Начисления по контейнеру (грузоместу поставки).
        for (f in a.obj("container_fees").arr("fees")) {
            val amount = f.money("accrued")
            var cls = types.classOf(f.int("type_id"))
            if (cls == FeeClass.OTHER) cls = FeeClass.CROSSDOCK
            acc.add(feeRow(date, 0, cls, amount))
            tt.add(typeName(f.int("type_id")), cls, amount)
            parsed += amount
        }

        // Остаток до итога Ozon — в «прочее», чтобы сумма сходилась с личным кабинетом.
        val rest = total - parsed
        if (a.obj("total_amount") != null && abs(rest) >= 0.01) {
            acc.add(FinDay(date, mainSku, other = rest))
            tt.add("Не разобрано (разница с итогом Ozon)", FeeClass.OTHER, rest)
        }
    }
    return FinPage(acc.rows(), root.str("last_id"), list.size, tt.list())
}

// ───────────────────────── Заказы ─────────────────────────

class OrdersPage(val rows: List<OrderDay>, val cursor: String, val hasNext: Boolean, val count: Int, val names: Map<Long, Product>)

/** Страница /v3/posting/fbo/list или /v4/posting/fbs/list → заказы по дням и товарам. */
fun parsePostings(text: String): OrdersPage {
    val root = parseJson(text)
    // Новые методы отдают {postings, cursor, has_next}; на случай обёртки result читаем и её.
    val box: JsonElement = root.obj("result") ?: root
    val postings = box.arr("postings").ifEmpty { root.arr("result") }
    val map = LinkedHashMap<String, OrderDay>()
    val names = HashMap<Long, Product>()
    for (p in postings) {
        val date = moscowDate(p.str("in_process_at").ifEmpty { p.str("created_at") })
        if (date.isEmpty()) continue
        val status = p.str("status")
        val cancelled = status == "cancelled"
        val delivered = status == "delivered"
        for (item in p.arr("products")) {
            val sku = item.long("sku")
            if (sku == 0L) continue
            val qty = item.int("quantity").coerceAtLeast(1)
            val row = OrderDay(date, sku, qty, item.num("price") * qty, if (cancelled) qty else 0, if (delivered) qty else 0)
            val key = "$date|$sku"
            map[key] = map[key]?.plus(row) ?: row
            if (sku !in names) names[sku] = Product(sku, item.str("offer_id"), item.str("name"))
        }
    }
    return OrdersPage(
        map.values.toList(), box.str("cursor"),
        ((box as? JsonObject)?.get("has_next") as? JsonPrimitive)?.content == "true",
        postings.size, names,
    )
}

// ───────────────────────── Товары и остатки ─────────────────────────

class ProductIdsPage(val ids: List<Long>, val lastId: String)

/** Страница /v3/product/list. */
fun parseProductIds(text: String): ProductIdsPage {
    val r = parseJson(text).obj("result")
    return ProductIdsPage(r.arr("items").map { it.long("product_id") }.filter { it != 0L }, r.str("last_id"))
}

/** Ответ /v3/product/info/list. */
fun parseProductInfo(text: String): List<Product> {
    val root = parseJson(text)
    val items = root.arr("items").ifEmpty { root.obj("result").arr("items") }
    return items.mapNotNull { p ->
        var sku = p.long("sku")
        if (sku == 0L) sku = p.arr("sources").firstOrNull()?.long("sku") ?: 0L
        if (sku == 0L) return@mapNotNull null
        val primary = (p as JsonObject)["primary_image"]
        val image = when (primary) {
            is JsonArray -> (primary.firstOrNull() as? JsonPrimitive)?.content ?: ""
            is JsonPrimitive -> if (primary is JsonNull) "" else primary.content
            else -> ""
        }.ifEmpty { (p.arr("images").firstOrNull() as? JsonPrimitive)?.content ?: "" }
        Product(sku, p.str("offer_id"), p.str("name"), image)
    }
}

class StocksPage(val stocks: List<Stock>, val offers: Map<Long, String>, val cursor: String, val count: Int)

/** Страница /v4/product/info/stocks: остатки FBO и FBS по товарам. */
fun parseStocks(text: String): StocksPage {
    val root = parseJson(text)
    val items = root.arr("items")
    val map = LinkedHashMap<Long, Stock>()
    val offers = HashMap<Long, String>()
    for (item in items) {
        for (s in item.arr("stocks")) {
            val sku = s.long("sku")
            if (sku == 0L) continue
            offers[sku] = item.str("offer_id")
            val old = map[sku] ?: Stock(sku)
            val present = s.int("present")
            map[sku] = if (s.str("type").lowercase().contains("fbo")) old.copy(fbo = old.fbo + present)
            else old.copy(fbs = old.fbs + present)
        }
    }
    return StocksPage(map.values.toList(), offers, root.str("cursor"), items.size)
}

/** Остатки FBO из аналитики: доступно на складах, едет в поставках, возвращается от покупателей. */
fun parseAnalyticsStocks(text: String): Map<Long, Stock> {
    val map = HashMap<Long, Stock>()
    for (i in parseJson(text).arr("items")) {
        val sku = i.long("sku")
        if (sku == 0L) continue
        val old = map[sku] ?: Stock(sku)
        map[sku] = old.copy(
            fbo = old.fbo + i.int("available_stock_count") + i.int("other_stock_count"),
            inTransit = old.inTransit + i.int("transit_stock_count") + i.int("requested_stock_count"),
            returning = old.returning + i.int("return_from_customer_stock_count"),
        )
    }
    return map
}

/** Остатки и спрос по кластерам из той же аналитики остатков: строка на товар и склад. */
fun parseClusterStocks(text: String): List<ClusterStock> =
    parseJson(text).arr("items").mapNotNull { i ->
        val sku = i.long("sku")
        if (sku == 0L) return@mapNotNull null
        val id = i.long("cluster_id").takeIf { it != 0L } ?: i.long("macrolocal_cluster_id")
        ClusterStock(
            sku = sku,
            clusterId = id,
            cluster = i.str("cluster_name").ifEmpty { i.str("warehouse_name").ifEmpty { "Кластер $id" } },
            available = i.int("available_stock_count") + i.int("other_stock_count"),
            transit = i.int("transit_stock_count") + i.int("requested_stock_count"),
            returning = i.int("return_from_customer_stock_count"),
            perDay = i.num("ads_cluster"),
        )
    }

// ───────────────────────── Реклама (Performance API) ─────────────────────────

/** Ответ /api/client/statistics/daily/json. */
fun parseAdDaily(text: String): List<AdDay> =
    parseJson(text).arr("rows").mapNotNull { r ->
        val id = r.str("id")
        val date = r.str("date").take(10)
        if (id.isEmpty() || date.isEmpty()) null
        else AdDay(
            date, id, r.str("title"), r.num("views").toLong(), r.num("clicks").toLong(),
            r.num("moneySpent"), r.num("orders").toLong(), r.num("ordersMoney"),
        )
    }
