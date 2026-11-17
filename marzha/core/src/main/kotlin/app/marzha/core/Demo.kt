package app.marzha.core

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

/** Демо-кабинет: правдоподобные данные, чтобы посмотреть приложение без ключа Ozon. */
class DemoData(
    val products: List<Product>,
    val costs: Map<Long, UnitCost>,
    val fin: List<FinDay>,
    val orders: List<OrderDay>,
    val stocks: List<Stock>,
    val expenses: List<Expense>,
    val ads: List<AdDay>,
)

fun demoData(today: LocalDate, days: Int = 62): DemoData {
    val rnd = Random(42)

    class Item(val name: String, val offer: String, val price: Double, val cost: UnitCost, val perDay: Double, val adShare: Double)

    val items = listOf(
        Item("Перчатки вязаные женские, чёрные", "Перчатки вяз черн", 1520.0, UnitCost(90.0, 5.5, 16.0), 120.0, 0.065),
        Item("Перчатки вязаные женские, бежевые", "Перчатки вяз беж", 1470.0, UnitCost(90.0, 5.5, 16.0), 140.0, 0.058),
        Item("Перчатки короткие, чёрные", "Перчатки корот черн", 1650.0, UnitCost(190.0, 5.5, 16.0), 95.0, 0.035),
        Item("Перчатки ажурные, серые", "Ажурные сер", 1540.0, UnitCost(90.0, 5.5, 16.0), 22.0, 0.095),
        Item("Носки пастельные, 5 пар", "Носки пастель 5", 1110.0, UnitCost(135.0, 0.0, 12.0), 300.0, 0.061),
        Item("Носки белые высокие, 5 пар", "Носки бел выс 5", 1150.0, UnitCost(150.0, 0.0, 12.0), 45.0, 0.14),
        Item("Носки чёрные короткие, 10 пар", "Носки черн кор 10", 1390.0, UnitCost(240.0, 0.0, 14.0), 38.0, 0.08),
        Item("Шапка бини, графит", "Шапка бини граф", 1890.0, UnitCost(310.0, 5.5, 18.0), 26.0, 0.07),
        Item("Шарф-снуд, молочный", "Снуд молочн", 2150.0, UnitCost(420.0, 5.5, 20.0), 14.0, 0.11),
        Item("Варежки детские, розовые", "Варежки дет роз", 990.0, UnitCost(0.0, 0.0, 0.0), 9.0, 0.05),
    )
    val products = items.mapIndexed { i, it -> Product(1_900_000_100L + i * 37, it.offer, it.name) }
    val costs = HashMap<Long, UnitCost>()
    val fin = ArrayList<FinDay>()
    val orders = ArrayList<OrderDay>()
    val ads = ArrayList<AdDay>()
    val stocks = ArrayList<Stock>()

    for ((i, item) in items.withIndex()) {
        val sku = products[i].sku
        if (item.cost.total > 0) costs[sku] = item.cost
        for (back in days downTo 1) {
            val date = today.minusDays(back.toLong())
            // Сезонный разгон к сегодняшнему дню и провал по выходным.
            val trend = 0.55 + 0.45 * (days - back) / days
            val weekend = if (date.dayOfWeek.value >= 6) 0.85 else 1.0
            val ordered = (item.perDay * trend * weekend * (0.8 + rnd.nextDouble() * 0.4)).roundToInt()
            val cancelled = (ordered * (0.16 + rnd.nextDouble() * 0.08)).roundToInt()
            // Свежие заказы ещё в пути; через неделю почти все уже доставлены или отменены.
            val delivered = if (back <= 3) 0 else ((ordered - cancelled) * if (back <= 8) 0.6 else 0.97).roundToInt()
            orders += OrderDay(date.toString(), sku, ordered, ordered * item.price, cancelled, delivered)
            val sold = (ordered * (0.70 + rnd.nextDouble() * 0.1)).roundToInt()
            val returned = (sold * 0.012 + rnd.nextDouble() * 0.6).toInt()
            val sales = sold * item.price
            val returns = -returned * item.price
            fin += FinDay(
                date = date.toString(), sku = sku, soldQty = sold, returnQty = returned,
                sales = sales, returns = returns,
                customerPaid = (sales + returns) * 0.36,
                commission = -(sales + returns) * 0.52,
                logistics = -sold * 98.0,
                returnLogistics = -(cancelled * 22.0 + returned * 70.0),
                ads = -ordered * item.price * item.adShare,
                acquiring = -(sales + returns) * 0.006,
                other = -sold * 4.0,
            )
        }
        val perDay = item.perDay
        stocks += Stock(
            sku,
            fbo = (perDay * (if (i == 3) 4 else 11 + i * 3)).roundToInt(),
            fbs = if (i % 4 == 0) (perDay * 0.5).roundToInt() else 0,
            inTransit = if (i % 3 == 0) (perDay * 6).roundToInt() else 0,
            returning = (perDay * 1.2).roundToInt(),
        )
    }
    // Общие начисления кабинета и рекламные кампании.
    val names = listOf("Носки пастель · поиск", "Перчатки беж · поиск", "Перчатки чёрные · поиск", "Ажурные серые · поиск")
    for (back in days downTo 1) {
        val date = today.minusDays(back.toLong()).toString()
        fin += FinDay(date, 0, storage = -900.0 - rnd.nextInt(400), crossdock = if (back % 7 == 2) -4700.0 else 0.0)
        for ((n, title) in names.withIndex()) {
            val views = 9000L / (n + 1) + rnd.nextInt(2500)
            val clicks = (views * (0.05 + rnd.nextDouble() * 0.04)).toLong()
            val ord = (clicks * 0.09).toLong()
            ads += AdDay(date, (44515960 + n).toString(), title, views, clicks, clicks * 2.6, ord, ord * 1400.0)
        }
    }
    val expenses = listOf(
        Expense(1, "Ведение кабинета", 20000.0, ExpenseKind.MONTHLY),
        Expense(2, "Фулфилмент и упаковщик", 9000.0, ExpenseKind.WEEKLY),
    )
    return DemoData(products, costs, fin, orders, stocks, expenses, ads)
}
