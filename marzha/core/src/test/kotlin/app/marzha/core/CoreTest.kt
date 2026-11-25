package app.marzha.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private fun near(a: Double, b: Double, eps: Double = 0.01) = assertTrue(abs(a - b) < eps, "$a != $b")

    private val types = FeeTypes.parse(
        """{"accrual_types":[
          {"id":1,"name":"Логистика","description":"Доставка до покупателя"},
          {"id":2,"name":"Обратная логистика","description":""},
          {"id":3,"name":"Продвижение с оплатой за заказ","description":""},
          {"id":4,"name":"Услуга размещения товаров на складе","description":""},
          {"id":5,"name":"Кросс-докинг","description":""},
          {"id":6,"name":"Оплата эквайринга","description":""},
          {"id":7,"name":"Оплата за клик","description":""},
          {"id":8,"name":"Подписка Premium","description":""}
        ]}"""
    )

    @Test
    fun feeClassification() {
        assertEquals(FeeClass.LOGISTICS, types.classOf(1))
        assertEquals(FeeClass.RETURN_LOGISTICS, types.classOf(2))
        assertEquals(FeeClass.ADS, types.classOf(3))
        assertEquals(FeeClass.STORAGE, types.classOf(4))
        assertEquals(FeeClass.CROSSDOCK, types.classOf(5))
        assertEquals(FeeClass.ACQUIRING, types.classOf(6))
        assertEquals(FeeClass.ADS, types.classOf(7))
        assertEquals(FeeClass.OTHER, types.classOf(8))
        assertEquals(FeeClass.OTHER, types.classOf(999))
    }

    private val accruals = """{"accruals":[
      {"accrual_id":1,"accrued_category":"POSTING","date":"2026-11-15","unit_number":"1-1",
       "total_amount":{"amount":"730.00","currency":"RUB"},
       "posting":{"delivery_schema":"FBO","products":[{"sku":100,"quantity":2,
         "commission":{"sale_amount":{"amount":"2000"},"commission":{"amount":"-1040"},
           "bonus":{"amount":"1200"},"coinvestment":{"amount":"0"},"seller_price":{"amount":"1000"}},
         "delivery":{"services":[{"type_id":1,"accrued":{"amount":"-200"}},{"type_id":6,"accrued":{"amount":"-30"}}],
           "total_accrued":{"amount":"-230"}}}]}},
      {"accrual_id":2,"accrued_category":"POSTING","date":"2026-11-15",
       "total_amount":{"amount":"-550"},
       "posting":{"products":[{"sku":100,"quantity":1,
         "commission":{"sale_amount":{"amount":"-1000"},"commission":{"amount":"520"}},
         "delivery":{"services":[{"type_id":1,"accrued":{"amount":"-70"}}]}}]}},
      {"accrual_id":3,"accrued_category":"ITEM","date":"2026-11-15","total_amount":{"amount":"-120"},
       "item_fees":{"fees":[{"sku":100,"quantity":1,"fees":[{"type_id":3,"accrued":{"amount":"-120"}}]}]}},
      {"accrual_id":4,"accrued_category":"NON_ITEM","date":"2026-11-15","total_amount":{"amount":"-300"},
       "non_item_fee":{"type_id":7,"accrued":{"amount":"-300"}}},
      {"accrual_id":5,"accrued_category":"CONTAINER_FEES","date":"2026-11-15","total_amount":{"amount":"-50"},
       "container_fees":{"fees":[{"type_id":5,"accrued":{"amount":"-45"}}]}}
    ],"last_id":"abc"}"""

    @Test
    fun accrualsParsing() {
        val page = parseAccruals(accruals, types, "2026-11-15")
        assertEquals("abc", page.lastId)
        assertEquals(5, page.count)
        val item = page.rows.first { it.sku == 100L }
        assertEquals(2, item.soldQty)
        assertEquals(1, item.returnQty)
        near(item.sales, 2000.0)
        near(item.returns, -1000.0)
        near(item.commission, -1040.0 + 520.0)
        near(item.logistics, -200.0)
        near(item.returnLogistics, -70.0)
        near(item.acquiring, -30.0)
        near(item.ads, -120.0)
        // Оплатили клиенты: 2000 − 1200 баллов за продажу и −1000 за возврат.
        near(item.customerPaid, 800.0 - 1000.0)
        val shared = page.rows.first { it.sku == 0L }
        near(shared.ads, -300.0)
        near(shared.crossdock, -45.0)
        near(shared.other, -5.0) // остаток до итога Ozon
        // Сумма всех статей равна сумме итогов Ozon.
        near(page.rows.sumOf { it.revenue + it.ozonTotal }, 730.0 - 550 - 120 - 300 - 50)
    }

    @Test
    fun payoutIsNotAnExpense() {
        val t = FeeTypes.parse("""{"accrual_types":[{"id":1,"name":"Выплата продавцу"},{"id":2,"name":"Компенсация за доставку"},
            {"id":3,"name":"Перечисление средств на расчётный счёт"},{"id":4,"name":"Досрочная выплата"},
            {"id":5,"name":"Комиссия за досрочную выплату продавцу"}]}""")
        assertEquals(FeeClass.TRANSFER, t.classOf(1))
        assertTrue(t.classOf(2) != FeeClass.TRANSFER)
        assertEquals(FeeClass.TRANSFER, t.classOf(3))
        // Досрочная выплата — это плата Ozon за услугу, то есть расход.
        assertEquals(FeeClass.OTHER, t.classOf(4))
        assertEquals(FeeClass.OTHER, t.classOf(5))
        val page = parseAccruals(
            """{"accruals":[{"date":"2026-11-10","total_amount":{"amount":"-500000"},"non_item_fee":{"type_id":1,"accrued":{"amount":"-500000"}}}]}""",
            t, "2026-11-10",
        )
        // Выплата не попадает ни в одну статью и не считается «неразобранной».
        assertTrue(page.rows.all { it.revenue + it.ozonTotal == 0.0 })
        assertEquals(FeeClass.TRANSFER, page.types.single().cls)
    }

    @Test
    fun accrualTypeTotals() {
        val page = parseAccruals(accruals, types, "2026-11-15")
        val byName = page.types.associateBy { it.name }
        near(byName.getValue("Продажи (реализовано)").amount, 2000.0)
        near(byName.getValue("Возвраты (реализовано)").amount, -1000.0)
        near(byName.getValue("Логистика").amount, -270.0)
        near(byName.getValue("Не разобрано (разница с итогом Ozon)").amount, -5.0)
        // Сумма по видам равна сумме итогов Ozon.
        near(page.types.sumOf { it.amount }, 730.0 - 550 - 120 - 300 - 50)
    }

    @Test
    fun accrualsBonusOnTopAndPositiveCommission() {
        // «Реализовано» без баллов, баллы начислены сверху, комиссия пришла положительным числом.
        val text = """{"accruals":[{"date":"2026-11-14","total_amount":{"amount":"1500"},
          "posting":{"products":[{"sku":7,"quantity":1,"commission":{"sale_amount":{"amount":"800"},
          "commission":{"amount":"500"},"bonus":{"amount":"1200"}},"delivery":{}}]}}]}"""
        val r = parseAccruals(text, types, "2026-11-14").rows.single()
        near(r.sales, 2000.0)
        near(r.commission, -500.0)
        near(r.customerPaid, 800.0)
        near(r.other, 0.0)
    }

    @Test
    fun accrualsReturnAsPositiveAmount() {
        // Возврат пришёл положительной суммой, а итог начисления отрицательный.
        val text = """{"accruals":[{"date":"2026-11-14","total_amount":{"amount":"-480"},
          "posting":{"products":[{"sku":7,"quantity":1,"commission":{"sale_amount":{"amount":"1000"},
          "commission":{"amount":"520"}},"delivery":{}}]}}]}"""
        val r = parseAccruals(text, types, "2026-11-14").rows.single()
        near(r.returns, -1000.0)
        assertEquals(1, r.returnQty)
        assertEquals(0, r.soldQty)
        near(r.commission, 520.0)
    }

    @Test
    fun postingsParsing() {
        val text = """{"cursor":"c2","has_next":true,"postings":[
          {"posting_number":"1","status":"delivered","in_process_at":"2026-11-14T22:30:00Z",
           "products":[{"sku":100,"name":"Товар","offer_id":"А-1","quantity":2,"price":{"amount":"1500.00","currency":"RUB"}}]},
          {"posting_number":"2","status":"cancelled","created_at":"2026-11-16T08:00:00Z",
           "products":[{"sku":100,"quantity":1,"price":"1500.0000"}]}]}"""
        val p = parsePostings(text)
        assertEquals(2, p.rows.sumOf { it.deliveredQty })
        assertTrue(p.hasNext)
        assertEquals("c2", p.cursor)
        assertEquals(2, p.rows.size)
        // 22:30 UTC — это уже следующий день по Москве.
        val day = p.rows.first { it.cancelledQty == 0 }
        assertEquals("2026-11-15", day.date)
        assertEquals(2, day.orderedQty)
        near(day.orderedSum, 3000.0)
        assertEquals("А-1", p.names[100L]?.offerId)
    }

    @Test
    fun productsAndStocks() {
        val info = parseProductInfo(
            """{"items":[{"id":5,"offer_id":"A","name":"N","sku":0,"sources":[{"sku":77,"source":"sds"}],
               "primary_image":["http://x/1.jpg"],"images":[]}]}"""
        )
        assertEquals(Product(77, "A", "N", "http://x/1.jpg"), info.single())
        val st = parseStocks(
            """{"cursor":"","items":[{"offer_id":"A","product_id":5,"stocks":[
               {"type":"fbo","present":10,"reserved":2,"sku":77},{"type":"fbs","present":3,"reserved":0,"sku":77}]}]}"""
        )
        assertEquals(Stock(77, fbo = 10, fbs = 3), st.stocks.single())
        val an = parseAnalyticsStocks(
            """{"items":[{"sku":77,"available_stock_count":6,"transit_stock_count":20,"requested_stock_count":5,
               "return_from_customer_stock_count":4},{"sku":77,"available_stock_count":1}]}"""
        )
        assertEquals(Stock(77, fbo = 7, inTransit = 25, returning = 4), an[77L])
    }

    @Test
    fun clientPaginatesAndSends() {
        val calls = ArrayList<String>()
        val http = Transport { _, url, headers, body ->
            calls += url.substringAfter(".ru") + " " + body
            assertEquals("cid", headers["Client-Id"])
            when {
                url.endsWith("/accrual/types") -> """{"accrual_types":[{"id":7,"name":"Оплата за клик"}]}"""
                url.endsWith("/accrual/by-day") && body!!.contains("\"last_id\":\"\"") ->
                    """{"accruals":[{"date":"2026-11-10","total_amount":{"amount":"-10"},"non_item_fee":{"type_id":7,"accrued":{"amount":"-10"}}}],"last_id":"p2"}"""
                url.endsWith("/accrual/by-day") && body!!.contains("p2") ->
                    """{"accruals":[{"date":"2026-11-10","total_amount":{"amount":"-5"},"non_item_fee":{"type_id":7,"accrued":{"amount":"-5"}}}],"last_id":""}"""
                else -> "{}"
            }
        }
        val api = OzonClient(" cid ", "key", http)
        val (rows, types) = api.accruals(LocalDate.of(2026, 11, 10), api.feeTypes())
        near(rows.single().ads, -15.0)
        assertEquals(TypeTotal("Оплата за клик", FeeClass.ADS, -15.0, 2), types.single())
        assertEquals(3, calls.size)
    }

    @Test
    fun reportMath() {
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        val fin = listOf(
            FinDay("2026-11-03", 1, soldQty = 10, returnQty = 1, sales = 10000.0, returns = -1000.0, customerPaid = 4000.0,
                commission = -4500.0, logistics = -900.0, returnLogistics = -80.0, ads = -600.0),
            FinDay("2026-11-04", 2, soldQty = 3, sales = 3000.0, customerPaid = 3000.0, commission = -1500.0, logistics = -300.0),
            FinDay("2026-11-04", 0, storage = -240.0, ads = -120.0),
            FinDay("2026-10-31", 1, soldQty = 99, sales = 99000.0), // вне периода
        )
        val orders = listOf(OrderDay("2026-11-02", 1, 14, 14000.0, 2, 10))
        val costs = mapOf(1L to UnitCost(100.0, 5.5, 16.0))
        val expenses = listOf(Expense(1, "Кабинет", 3000.0, ExpenseKind.MONTHLY), Expense(2, "Фото", 1200.0, ExpenseKind.ONCE, "2026-11-10"))
        val s = EconSettings(taxPct = 6.0, taxBase = TaxBase.CUSTOMER_PAID, managerPct = 1.0)
        val r = buildReport(from, to, fin, orders, emptyMap(), costs, expenses, s)

        assertEquals(2, r.lines.size)
        val a = r.lines.first { it.sku == 1L }
        val b = r.lines.first { it.sku == 2L }
        near(a.b.revenue, 9000.0)
        near(a.b.cogs, -9 * 121.5)
        near(a.b.tax, -240.0)
        near(a.b.manager, -90.0)
        // Общие расходы делятся по выручке: 9000 к 3000.
        near(a.b.storage, -180.0)
        near(b.b.storage, -60.0)
        near(a.b.ads, -600.0 - 90.0)
        near(a.b.fixed + b.b.fixed, -4200.0)
        near(a.drr, 690.0 / 14000.0, 1e-9)
        // Выкуп: 10 доставлено из 12 завершённых (2 заказа ещё в пути). Выкуплено за период больше заказанного — не важно.
        near(a.buyout!!, 10.0 / 12.0, 1e-9)
        assertEquals(null, b.buyout)
        assertTrue(a.hasCost)
        assertTrue(!b.hasCost)
        assertEquals(1, r.missingCost)

        val expected = 12000.0 - 6000 - 1200 - 80 - 720 - 240 - 9 * 121.5 - (240 + 180) - (90 + 30) - 4200
        near(r.total.b.profit, expected)
        // График по дням сходится с итогом.
        near(r.daily.sumOf { it.profit }, r.total.b.profit)
        near(r.daily.sumOf { it.revenue }, 12000.0)
        assertEquals(30, r.daily.size)
        assertTrue(reportCsv(r).lines().size >= 4)
    }

    @Test
    fun reportWithoutSalesKeepsSharedCosts() {
        val d = LocalDate.of(2026, 11, 5)
        val r = buildReport(d, d, listOf(FinDay("2026-11-05", 0, storage = -100.0)), emptyList(), emptyMap(), emptyMap(), emptyList(), EconSettings())
        near(r.total.b.profit, -100.0)
    }

    @Test
    fun stockReport() {
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        val fin = listOf(FinDay("2026-11-03", 1, soldQty = 60, sales = 60000.0, commission = -30000.0))
        val costs = mapOf(1L to UnitCost(100.0))
        val s = EconSettings(taxPct = 0.0, stockTargetDays = 30)
        val rep = buildReport(from, to, fin, emptyList(), emptyMap(), costs, emptyList(), s)
        val st = buildStockReport(listOf(Stock(1, fbo = 20, inTransit = 10), Stock(2)), rep, emptyMap(), costs, s)
        val l = st.lines.single()
        near(l.perDay, 2.0)
        near(l.daysLeft!!, 15.0)
        near(l.invested, 3000.0)
        near(l.expectedProfit, 30 * 400.0)
        assertEquals(30, l.toBuy)
        near(st.toBuySum, 3000.0)
        near(st.expectedRevenue, 30 * 1000.0)
        near(st.moneyBack, 3000.0 + 12000.0)
        // Если из 30 дней загружено только 10, скорость продаж считается по ним.
        val partial = buildStockReport(listOf(Stock(1, fbo = 20, inTransit = 10)), rep, emptyMap(), costs, s, loadedDays = 10)
        near(partial.lines.single().perDay, 6.0)
    }

    @Test
    fun demoIsConsistent() {
        val today = LocalDate.of(2026, 11, 16)
        val d = demoData(today)
        val r = buildReport(today.minusDays(30), today.minusDays(1), d.fin, d.orders, d.products.associateBy { it.sku }, d.costs, d.expenses, EconSettings())
        assertTrue(r.total.b.revenue > 0)
        assertTrue(r.total.margin > 0.05 && r.total.margin < 0.5, "маржа ${r.total.margin}")
        near(r.daily.sumOf { it.profit }, r.total.b.profit, 1.0)
        assertEquals(1, r.missingCost)
        assertTrue(campaigns(d.ads, today.minusDays(7), today.minusDays(1)).size == 4)
        val plan = buildClusterPlan(d.clusters, r, d.products.associateBy { it.sku }, EconSettings())
        assertTrue(plan.urgent.isNotEmpty() && plan.clusters.size == 9)
    }

    @Test
    fun extrasAndGrouping() {
        val c = UnitCost(100.0, 5.0, 10.0, 2.0, listOf(ExtraCost("Бирка", 3.5), ExtraCost("Фулфилмент", 20.0)))
        near(c.total, 140.5)
        assertEquals(c.extras, UnitCost.parseExtras(c.extrasText()))
        assertTrue(UnitCost.parseExtras("").isEmpty())
        val fin = listOf(FinDay("2026-11-03", 1, soldQty = 2, sales = 2000.0))
        val r = buildReport(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), fin, emptyList(), emptyMap(), mapOf(1L to c), emptyList(), EconSettings(taxPct = 0.0))
        near(r.total.b.otherCost, -2 * 25.5)
        // 1 ноября 2026 — воскресенье: 30 дней ложатся в 6 недель и один месяц.
        assertEquals(6, groupPoints(r.daily, Grouping.WEEK).size)
        val month = groupPoints(r.daily, Grouping.MONTH).single()
        near(month.revenue, 2000.0)
        near(month.payout, 2000.0)
    }

    @Test
    fun clusterPlan() {
        val text = """{"items":[
          {"sku":1,"cluster_id":10,"cluster_name":"Москва","warehouse_name":"Хоругвино","available_stock_count":30,"transit_stock_count":0,"ads_cluster":10},
          {"sku":1,"cluster_id":10,"cluster_name":"Москва","warehouse_name":"Пушкино","available_stock_count":10,"transit_stock_count":20,"ads_cluster":10},
          {"sku":1,"cluster_id":20,"cluster_name":"Казань","available_stock_count":0,"ads_cluster":3},
          {"sku":1,"cluster_id":30,"cluster_name":"Новосибирск","available_stock_count":50,"ads_cluster":0},
          {"sku":2,"cluster_id":20,"cluster_name":"Казань","available_stock_count":100,"ads_cluster":2}]}"""
        val rows = parseClusterStocks(text)
        assertEquals(5, rows.size)
        val from = LocalDate.of(2026, 11, 1)
        val rep = buildReport(from, from.plusDays(29), listOf(FinDay("2026-11-02", 1, soldQty = 30, sales = 30000.0)),
            emptyList(), emptyMap(), emptyMap(), emptyList(), EconSettings(taxPct = 0.0))
        val plan = buildClusterPlan(rows, rep, emptyMap(), EconSettings(stockTargetDays = 30))

        val msk = plan.lines.first { it.sku == 1L && it.stock.clusterId == 10L }
        // Два склада Москвы сложились, спрос кластера не удвоился.
        assertEquals(40, msk.stock.available)
        assertEquals(20, msk.stock.transit)
        near(msk.stock.perDay, 10.0)
        near(msk.daysLeft!!, 4.0)
        assertEquals(ClusterUrgency.CRITICAL, msk.urgency)
        assertEquals(10 * 30 - 40 - 20, msk.toShip)
        near(msk.revenuePerDay, 10 * 1000.0)

        val kzn = plan.lines.first { it.sku == 1L && it.stock.clusterId == 20L }
        assertEquals(ClusterUrgency.OUT, kzn.urgency)
        assertEquals(90, kzn.toShip)

        val nsk = plan.lines.first { it.stock.clusterId == 30L }
        assertEquals(ClusterUrgency.EXCESS, nsk.urgency)
        assertEquals(0, nsk.toShip)

        // Сначала то, что закончилось, затем самое срочное.
        assertEquals(listOf(20L, 10L), plan.urgent.map { it.stock.clusterId })
        assertEquals(ClusterUrgency.OUT, plan.clusters.first().worst)
    }

    @Test
    fun unitCalculator() {
        val i = CalcInput(
            price = 1000.0, purchase = 200.0, marking = 5.0, packaging = 15.0, inbound = 30.0,
            commission = 0.30, logistics = 100.0, returnLogistics = 50.0, buyout = 0.8, drr = 0.08,
            acquiring = 0.01, storage = 5.0, taxPct = 0.06, manager = 0.01, ordersPerDay = 10.0, stockDays = 30,
        )
        val r = calcUnit(i)
        near(r.commission, -300.0)
        // Невыкуп: на 1 выкупленную приходится 0,25 невыкупленной, каждая стоит 100 + 50.
        near(r.nonBuyout, -37.5)
        // Реклама со всех заказов: 8% × 1000 ÷ 0,8.
        near(r.ads, -100.0)
        near(r.cogs, -250.0)
        val expected = 1000 - 300 - 100 - 37.5 - 100 - 10 - 5 - 60 - 10 - 250
        near(r.profit, expected)
        near(r.margin, expected / 1000, 1e-9)
        // В точке безубыточности прибыль ноль.
        near(calcUnit(i.copy(price = r.breakEvenPrice!!)).profit, 0.0, 0.01)
        near(calcUnit(i.copy(drr = r.maxDrr)).profit, 0.0, 0.01)
        near(r.soldPerMonth, 240.0)
        assertEquals(240, r.firstBatchUnits)
        assertEquals(Verdict.OK, r.verdict)
        assertEquals(Verdict.LOSS, calcUnit(i.copy(price = 500.0)).verdict)
        // Налог с оплаты клиента: база меньше на долю баллов.
        near(calcUnit(i.copy(taxBase = TaxBase.CUSTOMER_PAID, ozonPoints = 0.5)).tax, -30.0)
    }

    @Test
    fun calcDefaultsFromSales() {
        val l = SkuReport(
            sku = 1, product = Product(1), soldQty = 100, returnQty = 0, orderedQty = 130, orderedSum = 130000.0,
            cancelledQty = 20, deliveredQty = 100, customerPaid = 40000.0,
            b = Breakdown(revenue = 100000.0, commission = -30000.0, logistics = -9000.0, returnLogistics = -1000.0, ads = -6500.0, acquiring = -800.0),
        )
        val c = calcFromSales(l, UnitCost(100.0, 5.0), EconSettings(taxPct = 6.0))
        near(c.price, 1000.0)
        near(c.commission, 0.3, 1e-9)
        near(c.logistics, 90.0)
        near(c.returnLogistics, 50.0)
        near(c.buyout, 100.0 / 120.0, 1e-9)
        near(c.drr, 0.05, 1e-9)
        near(c.ozonPoints, 0.6, 1e-9)
        near(c.purchase, 100.0)
        near(c.taxPct, 0.06, 1e-9)
    }

    @Test
    fun categoriesParsing() {
        val (list, next) = parseCategories(
            """{"has_next":true,"items":[{"id":"17","label":"Перчатки","metric_gmv":1.5E8,"metric_gmv_growth":12.5,
               "metric_aiv":1320,"metric_buyout":0.78,"metric_sellers":412,"metric_leader_share":34}]}"""
        )
        assertTrue(next)
        val c = list.single()
        near(c.avgPrice, 1320.0)
        near(c.buyout, 0.78, 1e-9)
        near(c.gmvGrowth, 0.125, 1e-9)
        near(c.leaderShare, 0.34, 1e-9)
    }

    @Test
    fun helpers() {
        assertEquals(3, dayChunks(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), 3).size)
        near(expensesFor(listOf(Expense(1, "x", 700.0, ExpenseKind.WEEKLY)), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 14)), 1400.0)
        assertEquals(12.34, toNum(kotlinx.serialization.json.JsonPrimitive("12,34")))
    }
}
