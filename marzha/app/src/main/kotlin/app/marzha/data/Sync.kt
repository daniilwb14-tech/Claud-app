package app.marzha.data

import androidx.room.withTransaction
import app.marzha.core.ApiException
import app.marzha.core.OzonClient
import app.marzha.core.PerformanceClient
import app.marzha.core.Product
import app.marzha.core.dayChunks
import app.marzha.core.describeError
import java.time.LocalDate

/** Итог загрузки. */
class SyncResult(
    /** Сколько дней начислений загружено в этот раз. */
    val daysLoaded: Int,
    /** Дни, которые не удалось загрузить. */
    val daysFailed: List<String>,
    /** Предупреждения о необязательных частях и причина сбоев. */
    val warning: String?,
)

/**
 * Загрузка данных из Ozon в локальную базу.
 *
 * Начисления тянутся по дням. Каждый день запоминается как загруженный сразу после успеха, поэтому
 * сбой на одном дне не останавливает остальные, а следующая загрузка докачивает только недостающее.
 * Три последних дня загружаются заново всегда: Ozon доначисляет задним числом.
 */
class Sync(private val db: Db, private val store: Store) {
    private val dao = db.dao()

    companion object {
        /**
         * Версия правил разбора начислений и заказов. 4 — «Досрочная выплата» снова считается расходом,
         * в заказах появился статус доставки. При повышении все дни загружаются заново.
         */
        const val DATA_VERSION = 4

        /** Сколько последних дней заказов обновлять каждый раз: статусы «доставлен» и «отменён» приходят позже. */
        const val ORDER_REFRESH_DAYS = 21L
    }

    suspend fun run(wantFrom: LocalDate, today: LocalDate, progress: (String) -> Unit): SyncResult {
        val api = OzonClient(store.clientId, store.apiKey)
        val warnings = ArrayList<String>()

        progress("Товары…")
        val products = api.products()
        if (products.isNotEmpty()) dao.putProducts(products.map { it.toE() })

        progress("Остатки…")
        try {
            val (stocks, offers) = api.stocks()
            db.withTransaction {
                dao.deleteStocks()
                dao.putStocks(stocks.map { it.toE() })
            }
            // Товары, которых нет в списке карточек (например, архивные), но есть на остатках.
            dao.addProducts(offers.map { (sku, offer) -> Product(sku, offer, offer).toE() })
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) throw e
            warnings += "Остатки не обновились: " + describeError(e)
        }

        // Правила разбора поменялись — старые дни пересчитаем, скачав заново.
        val reloadAll = store.dataVersion < DATA_VERSION
        if (reloadAll) {
            dao.clearLoadedDays()
            // Сразу отмечаем: недогруженные дни и так докачаются потом, а полная перезагрузка нужна один раз.
            store.dataVersion = DATA_VERSION
        }

        // Какие дни нужно загрузить: всё, чего нет, плюс три последних дня.
        val have = dao.loadedDays().toSet()
        val days = ArrayList<LocalDate>()
        var d = wantFrom
        while (!d.isAfter(today)) {
            if (d.toString() !in have || !d.isBefore(today.minusDays(2))) days += d
            d = d.plusDays(1)
        }

        val types = api.feeTypes()
        val failed = ArrayList<String>()
        var firstError: String? = null
        var inARow = 0
        var loaded = 0
        // От новых дней к старым: свежие цифры появляются первыми.
        for ((i, day) in days.sortedDescending().withIndex()) {
            progress("Начисления: ${day.dayOfMonth}.${"%02d".format(day.monthValue)} (${i + 1} из ${days.size})")
            try {
                val (rows, typeTotals) = api.accruals(day, types)
                val key = day.toString()
                db.withTransaction {
                    dao.deleteFin(key, key)
                    dao.putFin(rows.map { it.toE() })
                    dao.deleteAccTypes(key)
                    dao.putAccTypes(typeTotals.map { AccTypeE(key, it.name, it.cls.name, it.amount, it.count) })
                    // Сегодняшний день неполный — его не отмечаем, он загрузится и в следующий раз.
                    if (day.isBefore(today)) dao.putLoadedDay(LoadedDayE(key, System.currentTimeMillis()))
                }
                loaded++
                inARow = 0
            } catch (e: Exception) {
                if (e is ApiException && (e.code == 401 || e.code == 403)) throw e
                failed += day.toString()
                if (firstError == null) firstError = describeError(e)
                // Если Ozon отказывает раз за разом, дальше нет смысла: остальное догрузится в следующий раз.
                if (++inARow >= 5) {
                    days.sortedDescending().drop(i + 1).forEach { failed += it.toString() }
                    break
                }
            }
        }
        if (failed.isNotEmpty()) {
            warnings += "Не загрузились начисления за ${failed.size} дн. Нажмите «Обновить», чтобы догрузить. Причина: $firstError"
        }
        store.historyFrom = minOf(store.historyFrom ?: wantFrom, wantFrom)

        // Заказы: новые дни и последние три недели (статусы доставки и отмены меняются). После смены правил — вся история.
        val orderDays = days.toSet() + (0..ORDER_REFRESH_DAYS).map { today.minusDays(it) } +
            if (reloadAll) generateSequence(wantFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList() else emptyList()
        val ranges = dayChunks(orderDays.min(), today, 2).filter { (a, b) -> a in orderDays || b in orderDays }
        var ordersFailed = 0
        for ((i, r) in ranges.sortedByDescending { it.first }.withIndex()) {
            progress("Заказы: ${i + 1} из ${ranges.size}")
            try {
                val (rows, names) = api.orders(r.first, r.second)
                db.withTransaction {
                    dao.deleteOrders(r.first.toString(), r.second.toString())
                    dao.putOrders(rows.map { it.toE() })
                }
                dao.addProducts(names.values.map { it.toE() })
            } catch (e: Exception) {
                if (e is ApiException && (e.code == 401 || e.code == 403)) throw e
                ordersFailed++
            }
        }
        if (ordersFailed > 0) warnings += "Часть заказов не загрузилась — ДРР и процент выкупа могут быть неточными."

        // Реклама по кампаниям — только если подключён Performance API.
        if (store.hasPerf) {
            progress("Реклама…")
            try {
                val perf = PerformanceClient(store.perfId, store.perfSecret)
                for ((a, b) in dayChunks(wantFrom, today, 30)) {
                    val rows = perf.daily(a, b)
                    db.withTransaction {
                        dao.deleteAds(a.toString(), b.toString())
                        dao.putAds(rows.map { it.toE() })
                    }
                }
            } catch (e: Exception) {
                warnings += "Статистика рекламных кампаний не загрузилась: " + describeError(e) +
                    " Расходы на рекламу в прибыли всё равно учтены — они берутся из начислений."
            }
        }
        store.lastSync = System.currentTimeMillis()
        return SyncResult(loaded, failed, warnings.joinToString("\n\n").ifEmpty { null })
    }

    /** Проверка ключа перед сохранением: бросает исключение, если Ozon его не принял. */
    fun check(clientId: String, apiKey: String) = OzonClient(clientId, apiKey).check()
}
