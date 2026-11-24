package app.marzha

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.marzha.core.CampaignLine
import app.marzha.core.ClusterPlan
import app.marzha.core.buildClusterPlan
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
import app.marzha.data.Db
import app.marzha.data.Profiles
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
    TODAY("Сегодня"), YESTERDAY("Вчера"), D7("7 дней"), D30("30 дней"), MONTH("Этот месяц"), PREV_MONTH("Прошлый месяц"),
    CUSTOM("Свой период");

    /**
     * Границы периода. «7 дней» и «30 дней» — полные дни до вчера включительно, как в недельном отчёте.
     * Для своего периода границы хранит модель экрана, здесь — запасной вариант.
     */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        TODAY -> today to today
        YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        D7 -> today.minusDays(7) to today.minusDays(1)
        D30 -> today.minusDays(30) to today.minusDays(1)
        MONTH -> today.withDayOfMonth(1) to today
        PREV_MONTH -> today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
        CUSTOM -> today.minusDays(7) to today.minusDays(1)
    }
}

/** Сколько дней периода загружено из Ozon. */
data class Coverage(val loaded: Int, val total: Int, val firstMissing: LocalDate?) {
    val complete: Boolean get() = loaded >= total
}

/** Начисления Ozon за период по видам — для экрана проверки. */
data class TypeLine(val name: String, val cls: app.marzha.core.FeeClass, val amount: Double, val count: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val profilesStore = Profiles(app)

    // Всё ниже относится к текущему кабинету и подменяется при переключении.
    private var store = Store(app, profilesStore.current().prefsName)
    private var db = Db.get(app, profilesStore.current().dbName)
    private var dao = db.dao()
    private var sync = Sync(db, store)

    /** Номер «поколения» кабинета: загрузка, начатая в другом кабинете, не трогает экран текущего. */
    private var generation = 0

    var profiles by mutableStateOf(profilesStore.list())
        private set
    var profile by mutableStateOf(profilesStore.current())
        private set

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
    var historyFrom by mutableStateOf(store.historyFrom)
        private set

    /** Итог последней загрузки одной строкой. */
    var lastResult by mutableStateOf<String?>(null)
        private set
    var lastSync by mutableStateOf(store.lastSync)
        private set
    var clientId by mutableStateOf(store.clientId)
        private set
    var hasPerf by mutableStateOf(store.hasPerf)
        private set
    var theme by mutableStateOf(profilesStore.theme)
        private set

    fun chooseTheme(name: String) {
        profilesStore.theme = name
        theme = name
    }

    /** Переключает приложение на другой кабинет: свои ключи, настройки и данные. */
    fun switchProfile(id: Int) {
        if (id == profile.id && profiles.any { it.id == id }) return
        profilesStore.currentId = id
        openCurrent()
    }

    /** Новый кабинет: откроется экран подключения ключа Ozon. */
    fun addProfile(name: String) {
        val p = profilesStore.add(name)
        profilesStore.currentId = p.id
        openCurrent()
    }

    fun renameProfile(id: Int, name: String) {
        profilesStore.rename(id, name)
        profiles = profilesStore.list()
        profile = profilesStore.current()
    }

    /** Удаляет кабинет и всё, что о нём хранится на телефоне. Последний кабинет не удаляется — только очищается. */
    fun deleteProfile(id: Int) {
        if (profiles.size <= 1) {
            logout()
            return
        }
        val wasCurrent = id == profile.id
        viewModelScope.launch {
            if (wasCurrent) {
                generation++
                recomputeJob?.cancel()
            }
            withContext(Dispatchers.IO) { profilesStore.remove(id) }
            if (wasCurrent) openCurrent() else profiles = profilesStore.list()
        }
    }

    /** Подключает объекты текущего кабинета и заново читает всё его состояние. */
    private fun openCurrent() {
        generation++
        recomputeJob?.cancel()
        val p = profilesStore.current()
        val app = getApplication<Application>()
        store = Store(app, p.prefsName)
        db = Db.get(app, p.dbName)
        dao = db.dao()
        sync = Sync(db, store)
        profiles = profilesStore.list()
        profile = p
        connected = store.hasKeys || store.demo
        demo = store.demo
        settings = store.settings
        historyFrom = store.historyFrom
        lastSync = store.lastSync
        clientId = store.clientId
        hasPerf = store.hasPerf
        syncing = false
        syncStatus = ""
        error = null
        lastResult = null
        report = null
        stockReport = null
        campaignLines = emptyList()
        costs = emptyMap()
        expenses = emptyList()
        loadedDays = emptySet()
        coverage = null
        typeLines = emptyList()
        clusterPlan = null
        if (connected) {
            recompute()
            if (!demo && System.currentTimeMillis() - store.lastSync > 3 * 3600_000L) sync()
        }
    }

    private var recomputeJob: Job? = null

    /** Границы своего периода. */
    var customFrom by mutableStateOf(LocalDate.now(MOSCOW).minusDays(14))
        private set
    var customTo by mutableStateOf(LocalDate.now(MOSCOW).minusDays(1))
        private set

    /** Дни, за которые начисления загружены. */
    var loadedDays by mutableStateOf<Set<String>>(emptySet())
        private set
    var coverage by mutableStateOf<Coverage?>(null)
        private set
    var typeLines by mutableStateOf<List<TypeLine>>(emptyList())
        private set

    /** План поставок по кластерам; null — Ozon не отдал разбивку. */
    var clusterPlan by mutableStateOf<ClusterPlan?>(null)
        private set

    /** Границы выбранного периода. */
    fun range(): Pair<LocalDate, LocalDate> = if (period == Period.CUSTOM) customFrom to customTo else period.range(today())

    fun chooseCustom(from: LocalDate, to: LocalDate) {
        val today = today()
        customFrom = minOf(from, to).coerceAtMost(today)
        customTo = maxOf(from, to).coerceAtMost(today)
        period = Period.CUSTOM
        recompute()
    }

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
        val gen = generation
        val dao = dao
        val store = store
        recomputeJob = viewModelScope.launch {
            val today = today()
            val (from, to) = range()
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
                val loaded = if (demo) emptySet() else dao.loadedDays().toSet()
                val rep = buildReport(from, to, fin, orders, products, costMap, exp, s)
                val recent = buildReport(recentFrom, recentTo, fin, orders, products, costMap, exp, s)
                // Скорость продаж — по загруженным дням, иначе пропуски в истории занижают её.
                val recentLoaded = if (demo) null else countDays(recentFrom, recentTo) { it in loaded }
                val stock = buildStockReport(dao.stocks().map { it.toCore() }, recent, products, costMap, s, recentLoaded?.coerceAtLeast(1))
                val ads = campaigns(dao.ads(from.toString(), to.toString()).map { it.toCore() }, from, to)
                val clusterRows = dao.clusterStocks().map { it.toCore() }
                val plan = if (clusterRows.isEmpty()) null else buildClusterPlan(clusterRows, recent, products, s)
                val types = dao.accTypes(from.toString(), to.toString())
                    .groupBy { it.name }
                    .map { (name, rows) ->
                        TypeLine(
                            name, runCatching { app.marzha.core.FeeClass.valueOf(rows.first().cls) }.getOrDefault(app.marzha.core.FeeClass.OTHER),
                            rows.sumOf { it.amount }, rows.sumOf { it.count },
                        )
                    }
                    .sortedByDescending { kotlin.math.abs(it.amount) }
                // Сегодняшний день всегда неполный, его в покрытие не считаем.
                val last = minOf(to, today.minusDays(1))
                val cov = if (demo || last.isBefore(from)) null else {
                    var first: LocalDate? = null
                    val n = countDays(from, last) { d -> (d in loaded).also { ok -> if (!ok && first == null) first = LocalDate.parse(d) } }
                    Coverage(n, countDays(from, last) { true }, first)
                }
                Computed(rep, stock, ads, costMap, exp, loaded, cov, types, plan)
            }
            if (gen != generation) return@launch
            loadedDays = result.loaded
            coverage = result.coverage
            typeLines = result.types
            clusterPlan = result.plan
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
        val loaded: Set<String>, val coverage: Coverage?, val types: List<TypeLine>,
        val plan: ClusterPlan?,
    )

    private fun countDays(from: LocalDate, to: LocalDate, ok: (String) -> Boolean): Int {
        var n = 0
        var d = from
        while (!d.isAfter(to)) {
            if (ok(d.toString())) n++
            d = d.plusDays(1)
        }
        return n
    }

    /** Догружает недостающие дни выбранного периода. */
    fun loadPeriod() = sync(range().first)

    /** Загружает данные из Ozon. [from] — с какого дня нужна история; по умолчанию как уже загружено. */
    fun sync(from: LocalDate? = null) {
        if (syncing || !connected) return
        if (demo) {
            recompute()
            return
        }
        val today = today()
        // С какого дня нужна история: запрошенный, иначе уже выбранная глубина, иначе 35 дней.
        val want = minOf(from ?: today, store.historyFrom ?: today.minusDays(initialDays))
        syncing = true
        error = null
        syncStatus = "Подключаемся к Ozon…"
        val gen = generation
        val sync = sync
        val store = store
        viewModelScope.launch {
            var lastShown = 0L
            try {
                val result = withContext(Dispatchers.IO) {
                    sync.run(want, today) { msg ->
                        viewModelScope.launch {
                            // Пока грузился этот кабинет, пользователь мог переключиться на другой.
                            if (gen != generation) return@launch
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
                if (gen != generation) return@launch
                error = result.warning
                lastResult = when {
                    result.daysFailed.isEmpty() -> "Готово: загружено дней — ${result.daysLoaded}."
                    else -> "Загружено дней — ${result.daysLoaded}, не загрузилось — ${result.daysFailed.size}."
                }
            } catch (e: Exception) {
                if (gen == generation) error = describeError(e)
            } finally {
                if (gen != generation) return@launch
                syncing = false
                syncStatus = ""
                historyFrom = store.historyFrom
                lastSync = store.lastSync
                recompute()
            }
        }
    }

    /** Проверяет ключ в Ozon и, если он рабочий, сохраняет и запускает первую загрузку. */
    fun connect(id: String, key: String, name: String = "", onResult: (String?) -> Unit) {
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
                // Другой кабинет или выход из демо — старые данные не должны смешиваться с новыми.
                if (demo || (store.clientId.isNotEmpty() && store.clientId != id.trim())) withContext(Dispatchers.IO) { wipeData() }
                store.demo = false
                demo = false
                store.clientId = id
                store.apiKey = key
                clientId = store.clientId
                if (name.isNotBlank()) renameProfile(profile.id, name)
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
                    dao.putCosts(d.costs.map { (sku, c) -> c.toE(sku) })
                    dao.putFin(d.fin.map { it.toE() })
                    dao.putOrders(d.orders.map { it.toE() })
                    dao.putStocks(d.stocks.map { it.toE() })
                    dao.putExpenses(d.expenses.map { it.toE() })
                    dao.putAds(d.ads.map { it.toE() })
                    dao.putClusterStocks(d.clusters.map { it.toE() })
                }
            }
            store.demo = true
            store.historyFrom = today.minusDays(62)
            store.lastSync = System.currentTimeMillis()
            demo = true
            historyFrom = store.historyFrom
            lastSync = store.lastSync
            connected = true
            recompute()
        }
    }

    private fun wipeData() {
        db.clearAllTables()
        store.historyFrom = null
        store.lastSync = 0
    }

    /** Выход: стирает ключи и все загруженные данные кабинета. Если кабинетов несколько — кабинет удаляется. */
    fun logout() {
        if (profiles.size > 1) {
            deleteProfile(profile.id)
            return
        }
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
            clusterPlan = null
            settings = store.settings
            historyFrom = null
            lastResult = null
            lastSync = 0
            clientId = ""
            hasPerf = false
            error = null
        }
    }

    fun saveCost(skus: Collection<Long>, cost: UnitCost) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.putCosts(skus.map { cost.toE(it) })
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
