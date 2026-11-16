package app.marzha

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.marzha.core.CampaignLine
import app.marzha.core.EconSettings
import app.marzha.core.Expense
import app.marzha.core.MOSCOW
import app.marzha.core.Product
import app.marzha.core.Report
import app.marzha.core.StockReport
import app.marzha.core.UnitCost
import app.marzha.core.buildReport
import app.marzha.core.buildStockReport
import app.marzha.core.campaigns
import app.marzha.core.demoData
import app.marzha.core.describeError
import app.marzha.data.CostE
import app.marzha.data.Db
import app.marzha.data.Store
import app.marzha.data.Sync
import app.marzha.data.toCore
import app.marzha.data.toE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Period(val title: String) {
    TODAY("Сегодня"), YESTERDAY("Вчера"), D7("7 дней"), D30("30 дней"), MONTH("Этот месяц"), PREV_MONTH("Прошлый месяц");

    /** Границы периода. «7 дней» и «30 дней» — полные дни до вчера включительно, как в недельном отчёте. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        TODAY -> today to today
        YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        D7 -> today.minusDays(7) to today.minusDays(1)
        D30 -> today.minusDays(30) to today.minusDays(1)
        MONTH -> today.withDayOfMonth(1) to today
        PREV_MONTH -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val db = Db.get(app)
    private val dao = db.dao()
    private val sync = Sync(db, store)

    /** Сколько дней истории грузим при первом подключении. */
    private val initialDays = 35L

    var connected by mutableStateOf(store.hasKeys || store.demo)
        private set
    var demo by mutableStateOf(store.demo)
        private set
    var period by mutableStateOf(Period.D7)
        private set
    var report by mutableStateOf<Report?>(null)
        private set
    var stockReport by mutableStateOf<StockReport?>(null)
        private set
    var campaignLines by mutableStateOf<List<CampaignLine>>(emptyList())
        private set
    var costs by mutableStateOf<Map<Long, UnitCost>>(emptyMap())
        private set
    var expenses by mutableStateOf<List<Expense>>(emptyList())
        private set
    var settings by mutableStateOf(store.settings)
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncStatus by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var loadedFrom by mutableStateOf(store.loadedFrom)
        private set
    var lastSync by mutableStateOf(store.lastSync)
        private set
    var clientId by mutableStateOf(store.clientId)
        private set
    var hasPerf by mutableStateOf(store.hasPerf)
        private set

    private var recomputeJob: Job? = null

    fun today(): LocalDate = LocalDate.now(MOSCOW)

    init {
        if (connected) {
            recompute()
            // Данные старше трёх часов обновляем сами при запуске.
            if (!demo && System.currentTimeMillis() - store.lastSync > 3 * 3600_000L) sync()
        }
    }

    fun choosePeriod(p: Period) {
        period = p
        recompute()
    }

    /** Пересчитывает отчёты из локальной базы. */
    fun recompute() {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            val today = today()
            val (from, to) = period.range(today)
            val recentFrom = today.minusDays(30)
            val recentTo = today.minusDays(1)
            val loadFrom = minOf(from, recentFrom).toString()
            val result = withContext(Dispatchers.IO) {
                val products = dao.products().associate { it.sku to it.toCore() }
                val costMap = dao.costs().associate { it.sku to it.toCore() }
                val exp = dao.expenses().map { it.toCore() }
                val fin = dao.fin(loadFrom, today.toString()).map { it.toCore() }
                val orders = dao.orders(loadFrom, today.toString()).map { it.toCore() }
                val s = store.settings
                val rep = buildReport(from, to, fin, orders, products, costMap, exp, s)
                val recent = buildReport(recentFrom, recentTo, fin, orders, products, costMap, exp, s)
                val stock = buildStockReport(dao.stocks().map { it.toCore() }, recent, products, costMap, s)
                val ads = campaigns(dao.ads(from.toString(), to.toString()).map { it.toCore() }, from, to)
                Computed(rep, stock, ads, costMap, exp)
            }
            report = result.report
            stockReport = result.stock
            campaignLines = result.ads
            costs = result.costs
            expenses = result.expenses
        }
    }

    private class Computed(
        val report: Report, val stock: StockReport, val ads: List<CampaignLine>,
        val costs: Map<Long, UnitCost>, val expenses: List<Expense>,
    )

    /** Загружает данные из Ozon. [from] — с какого дня нужна история; по умолчанию как уже загружено. */
    fun sync(from: LocalDate? = null) {
        if (syncing || !connected) return
        if (demo) {
            recompute()
            return
        }
        val today = today()
        val want = from ?: store.loadedFrom ?: today.minusDays(initialDays)
        syncing = true
        error = null
        syncStatus = "Подключаемся к Ozon…"
        viewModelScope.launch {
            var lastShown = 0L
            try {
                val warning = withContext(Dispatchers.IO) {
                    sync.run(want, today) { msg ->
                        viewModelScope.launch {
                            syncStatus = msg
                            // Показываем цифры по мере загрузки, но не чаще раза в несколько секунд.
                            val now = System.currentTimeMillis()
                            if (syncing && now - lastShown > 4000) {
                                lastShown = now
                                recompute()
                            }
                        }
                    }
                }
                error = warning
            } catch (e: Exception) {
                error = describeError(e)
            } finally {
                syncing = false
                syncStatus = ""
                loadedFrom = store.loadedFrom
                lastSync = store.lastSync
                recompute()
            }
        }
    }

    /** Проверяет ключ в Ozon и, если он рабочий, сохраняет и запускает первую загрузку. */
    fun connect(id: String, key: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    sync.check(id, key)
                    null
                } catch (e: Exception) {
                    describeError(e)
                }
            }
            if (err == null) {
                if (demo) withContext(Dispatchers.IO) { wipeData() }
                store.demo = false
                demo = false
                store.clientId = id
                store.apiKey = key
                clientId = store.clientId
                connected = true
                sync()
            }
            onResult(err)
        }
    }

    fun savePerformanceKeys(id: String, secret: String) {
        store.perfId = id
        store.perfSecret = secret
        hasPerf = store.hasPerf
        if (hasPerf) sync()
    }

    /** Демо-кабинет: всё работает без ключа, на выдуманных данных. */
    fun startDemo() {
        viewModelScope.launch {
            val today = today()
            withContext(Dispatchers.IO) {
                wipeData()
                val d = demoData(today)
                db.withTransaction {
                    dao.putProducts(d.products.map { it.toE() })
                    dao.putCosts(d.costs.map { (sku, c) -> CostE(sku, c.purchase, c.marking, c.packaging, c.other) })
                    dao.putFin(d.fin.map { it.toE() })
                    dao.putOrders(d.orders.map { it.toE() })
                    dao.putStocks(d.stocks.map { it.toE() })
                    dao.putExpenses(d.expenses.map { it.toE() })
                    dao.putAds(d.ads.map { it.toE() })
                }
            }
            store.demo = true
            store.loadedFrom = today.minusDays(62)
            store.loadedTo = today
            store.lastSync = System.currentTimeMillis()
            demo = true
            loadedFrom = store.loadedFrom
            lastSync = store.lastSync
            connected = true
            recompute()
        }
    }

    private fun wipeData() {
        db.clearAllTables()
        store.loadedFrom = null
        store.loadedTo = null
        store.lastSync = 0
    }

    /** Выход: стирает ключи и все загруженные данные с устройства. */
    fun logout() {
        viewModelScope.launch {
            recomputeJob?.cancel()
            withContext(Dispatchers.IO) { wipeData() }
            store.clear()
            connected = false
            demo = false
            report = null
            stockReport = null
            campaignLines = emptyList()
            costs = emptyMap()
            expenses = emptyList()
            settings = store.settings
            loadedFrom = null
            lastSync = 0
            clientId = ""
            hasPerf = false
            error = null
        }
    }

    fun saveCost(skus: Collection<Long>, cost: UnitCost) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.putCosts(skus.map { CostE(it, cost.purchase, cost.marking, cost.packaging, cost.other) })
            }
            recompute()
        }
    }

    fun saveExpense(e: Expense) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.putExpense(e.toE()) }
            recompute()
        }
    }

    fun deleteExpense(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.deleteExpense(id) }
            recompute()
        }
    }

    fun saveSettings(s: EconSettings) {
        store.settings = s
        settings = s
        recompute()
    }

    fun product(sku: Long): Product? = report?.lines?.firstOrNull { it.sku == sku }?.product
        ?: stockReport?.lines?.firstOrNull { it.sku == sku }?.product
}
