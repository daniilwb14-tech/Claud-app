package app.marzha.data

import androidx.room.withTransaction
import app.marzha.core.OzonClient
import app.marzha.core.PerformanceClient
import app.marzha.core.Product
import app.marzha.core.dayChunks
import app.marzha.core.describeError
import java.time.LocalDate

/**
 * Загрузка данных из Ozon в локальную базу.
 *
 * Начисления тянутся по дням и перезаписываются целиком за день. Уже загруженные дни не запрашиваются
 * повторно, кроме трёх последних: Ozon доначисляет задним числом.
 */
class Sync(private val db: Db, private val store: Store) {
    private val dao = db.dao()

    /** @return предупреждение о необязательной части, которую не удалось загрузить, или null. */
    suspend fun run(wantFrom: LocalDate, today: LocalDate, progress: (String) -> Unit): String? {
        val api = OzonClient(store.clientId, store.apiKey)
        var warning: String? = null

        progress("Товары…")
        val products = api.products()
        if (products.isNotEmpty()) dao.putProducts(products.map { it.toE() })

        progress("Остатки…")
        val (stocks, offers) = api.stocks()
        db.withTransaction {
            dao.deleteStocks()
            dao.putStocks(stocks.map { it.toE() })
        }
        // Товары, которых нет в списке карточек (например, архивные), но есть на остатках.
        dao.addProducts(offers.map { (sku, offer) -> Product(sku, offer, offer).toE() })

        // Какие дни нужно загрузить.
        val haveFrom = store.loadedFrom
        val haveTo = store.loadedTo
        val days = ArrayList<LocalDate>()
        if (haveFrom == null || haveTo == null || wantFrom.isAfter(haveTo)) {
            var d = wantFrom
            while (!d.isAfter(today)) { days += d; d = d.plusDays(1) }
        } else {
            var d = wantFrom
            while (d.isBefore(haveFrom)) { days += d; d = d.plusDays(1) }
            d = maxOf(haveTo.minusDays(2), haveFrom)
            while (!d.isAfter(today)) { days += d; d = d.plusDays(1) }
        }

        val types = api.feeTypes()
        // От новых дней к старым: свежие цифры появляются первыми.
        for ((i, day) in days.sortedDescending().withIndex()) {
            progress("Начисления: ${day.dayOfMonth}.${"%02d".format(day.monthValue)} (${i + 1} из ${days.size})")
            val rows = api.accruals(day, types)
            db.withTransaction {
                dao.deleteFin(day.toString(), day.toString())
                dao.putFin(rows.map { it.toE() })
            }
        }
        val newFrom = if (haveFrom == null || haveTo == null || wantFrom.isAfter(haveTo)) wantFrom else minOf(haveFrom, wantFrom)
        store.loadedFrom = newFrom
        store.loadedTo = today

        // Заказы: новые дни и последняя неделя (статусы и отмены меняются).
        val orderDays = days.toSet() + (0..7L).map { today.minusDays(it) }
        val ranges = dayChunks(orderDays.min(), today, 2).filter { (a, b) -> a in orderDays || b in orderDays }
        for ((i, r) in ranges.sortedByDescending { it.first }.withIndex()) {
            progress("Заказы: ${i + 1} из ${ranges.size}")
            val (rows, names) = api.orders(r.first, r.second)
            db.withTransaction {
                dao.deleteOrders(r.first.toString(), r.second.toString())
                dao.putOrders(rows.map { it.toE() })
            }
            dao.addProducts(names.values.map { it.toE() })
        }

        // Реклама по кампаниям — только если подключён Performance API.
        if (store.hasPerf) {
            progress("Реклама…")
            try {
                val perf = PerformanceClient(store.perfId, store.perfSecret)
                for ((a, b) in dayChunks(newFrom, today, 30)) {
                    val rows = perf.daily(a, b)
                    db.withTransaction {
                        dao.deleteAds(a.toString(), b.toString())
                        dao.putAds(rows.map { it.toE() })
                    }
                }
            } catch (e: Exception) {
                warning = "Статистика рекламных кампаний не загрузилась: " + describeError(e) +
                    " Расходы на рекламу в прибыли всё равно учтены — они берутся из начислений."
            }
        }
        store.lastSync = System.currentTimeMillis()
        return warning
    }

    /** Проверка ключа перед сохранением: бросает исключение, если Ozon его не принял. */
    fun check(clientId: String, apiKey: String) = OzonClient(clientId, apiKey).check()
}
