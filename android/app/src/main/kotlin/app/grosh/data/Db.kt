package app.grosh.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import app.grosh.core.Deduplicator
import app.grosh.core.EntrySource
import app.grosh.core.Marketplace
import app.grosh.core.MerchantClassifier
import app.grosh.core.ParsedTxn
import app.grosh.core.SpendCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Одна операция: трата или поступление. Сумма в копейках, всегда положительная. */
@Entity(tableName = "entries", indices = [Index("dateMillis")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String = "RUB",
    val dateMillis: Long,
    val merchant: String,
    val note: String = "",
    val isIncome: Boolean = false,
    val category: String = SpendCategory.OTHER.name,
    val marketplace: String? = null,
    val source: String = EntrySource.MANUAL.name,
    val cardTail: String? = null,
) {
    val cat: SpendCategory get() = SpendCategory.from(category)
    val market: Marketplace? get() = Marketplace.from(marketplace)
    val src: EntrySource get() = EntrySource.from(source)
    val title: String get() = merchant.ifBlank { cat.title }
    val day: LocalDate get() = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        /** Запись из распознанной операции: категория и маркетплейс определяются автоматически. */
        fun from(txn: ParsedTxn, source: EntrySource) = Entry(
            amountMinor = txn.amountMinor,
            currency = txn.currency,
            dateMillis = txn.dateMillis,
            merchant = txn.merchant,
            isIncome = txn.isIncome,
            category = MerchantClassifier.category(txn.merchant, txn.isIncome).name,
            marketplace = if (txn.isIncome) null else MerchantClassifier.marketplace(txn.merchant)?.name,
            source = source.name,
            cardTail = txn.cardTail,
        )
    }
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries ORDER BY dateMillis DESC")
    suspend fun getAll(): List<Entry>

    @Insert
    suspend fun insert(entry: Entry): Long

    @Insert
    suspend fun insertAll(entries: List<Entry>)

    @Update
    suspend fun update(entry: Entry)

    @Delete
    suspend fun delete(entry: Entry)

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}

@Database(entities = [Entry::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): EntryDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "grosh.db").build().also { instance = it }
        }
    }
}

/** Единая точка, через которую в базу попадают автоматически распознанные операции. */
object Repo {
    enum class Dedupe { NONE, BY_DAY, NEAR_TIME }

    data class Outcome(val added: Int = 0, val duplicates: Int = 0)

    // Пуш и SMS об одной покупке приходят почти одновременно — без блокировки запишутся обе.
    private val mutex = Mutex()

    suspend fun save(context: Context, txns: List<ParsedTxn>, source: EntrySource, dedupe: Dedupe): Outcome = mutex.withLock {
        if (txns.isEmpty()) return Outcome()
        val dao = AppDb.get(context).dao()
        val fresh: List<ParsedTxn> = when (dedupe) {
            Dedupe.NONE -> txns
            Dedupe.BY_DAY -> Deduplicator.filterNewByDay(txns, existing(dao))
            Dedupe.NEAR_TIME -> {
                val known = existing(dao).toMutableList()
                txns.filter { txn ->
                    val duplicate = Deduplicator.isNearDuplicate(txn, known)
                    if (!duplicate) known += Deduplicator.Existing(txn.amountMinor, txn.isIncome, txn.dateMillis)
                    !duplicate
                }
            }
        }
        if (fresh.isNotEmpty()) dao.insertAll(fresh.map { Entry.from(it, source) })
        Outcome(added = fresh.size, duplicates = txns.size - fresh.size)
    }

    private suspend fun existing(dao: EntryDao) = dao.getAll().map { Deduplicator.Existing(it.amountMinor, it.isIncome, it.dateMillis) }

    /** CSV со всеми операциями для Excel. */
    fun exportCsv(entries: List<Entry>): String {
        val format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
        fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = mutableListOf("Дата;Тип;Сумма;Валюта;Категория;Маркетплейс;Продавец;Заметка;Источник")
        for (e in entries) {
            val amount = "%d,%02d".format(e.amountMinor / 100, e.amountMinor % 100)
            rows += listOf(
                format.format(Instant.ofEpochMilli(e.dateMillis)),
                if (e.isIncome) "Доход" else "Расход",
                (if (e.isIncome) "" else "-") + amount,
                e.currency,
                quote(e.cat.title),
                quote(e.market?.title.orEmpty()),
                quote(e.merchant),
                quote(e.note),
                quote(e.src.title),
            ).joinToString(";")
        }
        // BOM нужен, чтобы Excel правильно открыл кириллицу.
        return "\uFEFF" + rows.joinToString("\n")
    }

    /** Три месяца правдоподобных трат — чтобы посмотреть приложение в деле до подключения банка. */
    suspend fun insertDemo(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val random = Random(42)
        val out = mutableListOf<Entry>()

        fun add(merchant: String, rubles: Int, daysAgo: Int, income: Boolean = false) {
            if (daysAgo < 0) return
            val millis = today.minusDays(daysAgo.toLong()).atTime(random.nextInt(9, 22), random.nextInt(0, 60)).atZone(zone).toInstant().toEpochMilli()
            if (millis > now) return
            out += Entry.from(ParsedTxn(amountMinor = rubles * 100L, dateMillis = millis, merchant = merchant, isIncome = income), EntrySource.DEMO)
        }

        val daily = listOf(
            Triple("Пятёрочка", 350..1900, 0.55), Triple("ВкусВилл", 400..2200, 0.25), Triple("Яндекс Такси", 180..950, 0.3),
            Triple("Кофе Хауз", 190..420, 0.35), Triple("Додо Пицца", 600..1700, 0.1), Triple("Метро", 60..120, 0.5),
            Triple("OZON", 300..6500, 0.22), Triple("WILDBERRIES", 250..5200, 0.25), Triple("ALIEXPRESS", 150..3200, 0.07),
            Triple("Яндекс Маркет", 500..7000, 0.06), Triple("Аптека Горздрав", 200..1800, 0.07), Triple("Лукойл АЗС", 1500..3200, 0.08),
            Triple("Кинотеатр", 450..900, 0.04), Triple("DNS", 1200..15000, 0.02),
        )
        for (daysAgo in 0 until 90) {
            for ((merchant, range, chance) in daily) {
                if (random.nextDouble() < chance) add(merchant, random.nextInt(range.first, range.last + 1), daysAgo)
            }
        }
        for (month in 0 until 3) {
            val base = month * 30 + today.dayOfMonth
            add("Яндекс Плюс", 399, base - 5)
            add("Кинопоиск", 299, base - 12)
            add("iCloud", 149, base - 18)
            add("МТС связь", 650, base - 3)
            add("Квартплата ЖКХ", 6800, base - 10)
            add("Зарплата", 95000, base - 5, income = true)
            add("Аванс", 45000, base - 20, income = true)
        }
        AppDb.get(context).dao().insertAll(out)
    }
}
