package app.marzha.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.marzha.core.AdDay
import app.marzha.core.Expense
import app.marzha.core.ExpenseKind
import app.marzha.core.FinDay
import app.marzha.core.OrderDay
import app.marzha.core.Product
import app.marzha.core.Stock
import app.marzha.core.UnitCost

@Entity(tableName = "product")
data class ProductE(@PrimaryKey val sku: Long, val offerId: String, val name: String, val image: String)

@Entity(tableName = "cost")
data class CostE(
    @PrimaryKey val sku: Long, val purchase: Double, val marking: Double, val packaging: Double, val other: Double,
    /** Свои статьи себестоимости: «название<TAB>сумма», по строке на статью. */
    @ColumnInfo(defaultValue = "") val extras: String = "",
)

@Entity(tableName = "fin", primaryKeys = ["date", "sku"])
data class FinE(
    val date: String, val sku: Long, val soldQty: Int, val returnQty: Int, val sales: Double, val returns: Double,
    val customerPaid: Double, val commission: Double, val logistics: Double, val returnLogistics: Double,
    val ads: Double, val storage: Double, val acquiring: Double, val crossdock: Double, val other: Double,
)

@Entity(tableName = "orders", primaryKeys = ["date", "sku"])
data class OrderE(
    val date: String, val sku: Long, val orderedQty: Int, val orderedSum: Double, val cancelledQty: Int,
    @ColumnInfo(defaultValue = "0") val deliveredQty: Int = 0,
)

@Entity(tableName = "stock")
data class StockE(@PrimaryKey val sku: Long, val fbo: Int, val fbs: Int, val inTransit: Int, val returning: Int)

@Entity(tableName = "expense")
data class ExpenseE(@PrimaryKey(autoGenerate = true) val id: Long, val name: String, val amount: Double, val kind: String, val date: String)

@Entity(tableName = "ad", primaryKeys = ["date", "campaignId"])
data class AdE(
    val date: String, val campaignId: String, val title: String, val views: Long, val clicks: Long,
    val spent: Double, val orders: Long, val ordersSum: Double,
)

/** День, за который начисления Ozon загружены целиком. */
@Entity(tableName = "loaded_day")
data class LoadedDayE(@PrimaryKey val date: String, val at: Long)

/** Сумма за день по виду начисления Ozon — для проверки данных. */
@Entity(tableName = "acc_type", primaryKeys = ["date", "name"])
data class AccTypeE(val date: String, val name: String, val cls: String, val amount: Double, val count: Int)

fun ProductE.toCore() = Product(sku, offerId, name, image)
fun Product.toE() = ProductE(sku, offerId, name, image)
fun CostE.toCore() = UnitCost(purchase, marking, packaging, other, UnitCost.parseExtras(extras))
fun UnitCost.toE(sku: Long) = CostE(sku, purchase, marking, packaging, other, extrasText())
fun FinE.toCore() = FinDay(date, sku, soldQty, returnQty, sales, returns, customerPaid, commission, logistics, returnLogistics, ads, storage, acquiring, crossdock, other)
fun FinDay.toE() = FinE(date, sku, soldQty, returnQty, sales, returns, customerPaid, commission, logistics, returnLogistics, ads, storage, acquiring, crossdock, other)
fun OrderE.toCore() = OrderDay(date, sku, orderedQty, orderedSum, cancelledQty, deliveredQty)
fun OrderDay.toE() = OrderE(date, sku, orderedQty, orderedSum, cancelledQty, deliveredQty)
fun StockE.toCore() = Stock(sku, fbo, fbs, inTransit, returning)
fun Stock.toE() = StockE(sku, fbo, fbs, inTransit, returning)
fun ExpenseE.toCore() = Expense(id, name, amount, runCatching { ExpenseKind.valueOf(kind) }.getOrDefault(ExpenseKind.MONTHLY), date)
fun Expense.toE() = ExpenseE(id, name, amount, kind.name, date)
fun AdE.toCore() = AdDay(date, campaignId, title, views, clicks, spent, orders, ordersSum)
fun AdDay.toE() = AdE(date, campaignId, title, views, clicks, spent, orders, ordersSum)

@Dao
interface AppDao {
    @Query("SELECT * FROM product") suspend fun products(): List<ProductE>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProducts(rows: List<ProductE>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addProducts(rows: List<ProductE>)

    @Query("SELECT * FROM cost") suspend fun costs(): List<CostE>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCosts(rows: List<CostE>)

    @Query("SELECT * FROM fin WHERE date >= :from AND date <= :to") suspend fun fin(from: String, to: String): List<FinE>
    @Query("DELETE FROM fin WHERE date >= :from AND date <= :to") suspend fun deleteFin(from: String, to: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFin(rows: List<FinE>)

    @Query("SELECT * FROM orders WHERE date >= :from AND date <= :to") suspend fun orders(from: String, to: String): List<OrderE>
    @Query("DELETE FROM orders WHERE date >= :from AND date <= :to") suspend fun deleteOrders(from: String, to: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOrders(rows: List<OrderE>)

    @Query("SELECT * FROM stock") suspend fun stocks(): List<StockE>
    @Query("DELETE FROM stock") suspend fun deleteStocks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStocks(rows: List<StockE>)

    @Query("SELECT * FROM expense ORDER BY id") suspend fun expenses(): List<ExpenseE>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExpense(row: ExpenseE)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExpenses(rows: List<ExpenseE>)
    @Query("DELETE FROM expense WHERE id = :id") suspend fun deleteExpense(id: Long)

    @Query("SELECT date FROM loaded_day") suspend fun loadedDays(): List<String>
    @Query("DELETE FROM loaded_day") suspend fun clearLoadedDays()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLoadedDay(row: LoadedDayE)

    @Query("SELECT * FROM acc_type WHERE date >= :from AND date <= :to") suspend fun accTypes(from: String, to: String): List<AccTypeE>
    @Query("DELETE FROM acc_type WHERE date = :date") suspend fun deleteAccTypes(date: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccTypes(rows: List<AccTypeE>)

    @Query("SELECT * FROM ad WHERE date >= :from AND date <= :to") suspend fun ads(from: String, to: String): List<AdE>
    @Query("DELETE FROM ad WHERE date >= :from AND date <= :to") suspend fun deleteAds(from: String, to: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAds(rows: List<AdE>)
}

@Database(
    entities = [ProductE::class, CostE::class, FinE::class, OrderE::class, StockE::class, ExpenseE::class, AdE::class, LoadedDayE::class, AccTypeE::class],
    version = 4,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** Версия 4: сколько из заказанного уже доставлено — для честного процента выкупа. */
        private val migration3to4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE orders ADD COLUMN deliveredQty INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Версия 2: свои статьи себестоимости. Введённые данные сохраняются. */
        private val migration1to2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cost ADD COLUMN extras TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Версия 3: учёт загруженных дней и суммы по видам начислений. Старые дни загрузятся заново. */
        private val migration2to3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `loaded_day` (`date` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`date`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `acc_type` (`date` TEXT NOT NULL, `name` TEXT NOT NULL, `cls` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`date`, `name`))"
                )
            }
        }

        private val instances = HashMap<String, Db>()

        /** База кабинета: у каждого кабинета свой файл. */
        fun get(context: Context, name: String = "marzha.db"): Db = synchronized(this) {
            instances.getOrPut(name) {
                Room.databaseBuilder(context.applicationContext, Db::class.java, name)
                    .addMigrations(migration1to2, migration2to3, migration3to4)
                    .fallbackToDestructiveMigration()
                    .build()
            }
        }

        /** Закрывает базу перед удалением её файла. */
        fun close(name: String) = synchronized(this) { instances.remove(name)?.close() }
    }
}
