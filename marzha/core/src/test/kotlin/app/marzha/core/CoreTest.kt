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
        val rows = api.accruals(LocalDate.of(2026, 11, 10), api.feeTypes())
        near(rows.single().ads, -15.0)
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
        val orders = listOf(OrderDay("2026-11-02", 1, 14, 14000.0, 2))
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
    }

    @Test
    fun helpers() {
        assertEquals(3, dayChunks(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), 3).size)
        near(expensesFor(listOf(Expense(1, "x", 700.0, ExpenseKind.WEEKLY)), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 14)), 1400.0)
        assertEquals(12.34, toNum(kotlinx.serialization.json.JsonPrimitive("12,34")))
    }
}
