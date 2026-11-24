package app.marzha.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

class ApiException(val code: Int, message: String) : Exception(message)

/** Транспорт HTTP. Вынесен в интерфейс, чтобы клиент проверялся тестами без сети. */
fun interface Transport {
    /** Возвращает тело ответа; при коде не 2xx бросает [ApiException]. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): String
}

/** Обычный HTTP с повтором при лимите запросов (429) и сбоях сервера (5xx). */
class HttpTransport(private val attempts: Int = 7) : Transport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): String {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = 90_000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (code in 200..299) return text
                val e = ApiException(code, text.take(400))
                if (code == 429 || code >= 500) {
                    last = e
                    // Ozon ограничивает частоту запросов: ждём всё дольше, до полуминуты.
                    Thread.sleep(minOf(30_000L, 2000L shl i))
                    continue
                }
                throw e
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(1000L * (i + 1))
            }
        }
        throw last ?: ApiException(0, "нет ответа")
    }
}

/** Понятное человеку описание ошибки API. */
fun describeError(e: Throwable): String = when {
    e is ApiException && (e.code == 401 || e.code == 403) ->
        "Ozon не принял ключ. Проверьте Client-Id и API-ключ и что у ключа есть доступ на чтение."
    e is ApiException && e.code == 404 -> "Ozon не нашёл метод или данные (404)."
    e is ApiException && e.code == 429 -> "Ozon ограничил частоту запросов. Попробуйте через пару минут."
    e is ApiException -> "Ошибка Ozon ${e.code}: ${e.message.orEmpty().take(160)}"
    e is java.net.UnknownHostException -> "Нет соединения с интернетом."
    e is java.io.IOException -> "Сбой сети: ${e.message.orEmpty().take(120)}"
    else -> e.message ?: e.toString()
}

/** Остатки: по товарам, артикулы и разбивка по кластерам (пустая, если аналитика недоступна). */
class StocksResult(val stocks: List<Stock>, val offers: Map<Long, String>, val clusters: List<ClusterStock>)

/** Клиент Ozon Seller API: только чтение. */
class OzonClient(
    private val clientId: String,
    private val apiKey: String,
    private val http: Transport = HttpTransport(),
    private val base: String = "https://api-seller.ozon.ru",
) {
    private fun post(path: String, body: JsonObject): String =
        http.request("POST", base + path, mapOf("Client-Id" to clientId.trim(), "Api-Key" to apiKey.trim()), body.toString())

    /** Проверка ключа: самый лёгкий запрос. Бросает исключение, если ключ не подходит. */
    fun check() {
        post("/v3/product/list", buildJsonObject {
            putJsonObject("filter") { put("visibility", "ALL") }
            put("last_id", "")
            put("limit", 1)
        })
    }

    /** Все товары кабинета с названиями и картинками. */
    fun products(): List<Product> {
        val ids = ArrayList<Long>()
        var lastId = ""
        for (page in 0 until 500) {
            val p = parseProductIds(post("/v3/product/list", buildJsonObject {
                putJsonObject("filter") { put("visibility", "ALL") }
                put("last_id", lastId)
                put("limit", 1000)
            }))
            ids += p.ids
            if (p.ids.size < 1000 || p.lastId.isEmpty() || p.lastId == lastId) break
            lastId = p.lastId
        }
        val out = ArrayList<Product>()
        for (chunk in ids.distinct().chunked(1000)) {
            out += parseProductInfo(post("/v3/product/info/list", buildJsonObject {
                putJsonArray("product_id") { chunk.forEach { add(JsonPrimitive(it.toString())) } }
            }))
        }
        return out
    }

    /** Остатки: FBO и FBS из карточек, плюс «в пути» и «возвращается» из аналитики остатков. */
    fun stocks(): StocksResult {
        val bySku = LinkedHashMap<Long, Stock>()
        val offers = HashMap<Long, String>()
        var cursor = ""
        for (page in 0 until 500) {
            val p = parseStocks(post("/v4/product/info/stocks", buildJsonObject {
                put("cursor", cursor)
                putJsonObject("filter") { put("visibility", "ALL") }
                put("limit", 1000)
            }))
            for (s in p.stocks) bySku[s.sku] = bySku[s.sku]?.let { it.copy(fbo = it.fbo + s.fbo, fbs = it.fbs + s.fbs) } ?: s
            offers += p.offers
            if (p.count < 1000 || p.cursor.isEmpty() || p.cursor == cursor) break
            cursor = p.cursor
        }
        // Аналитика точнее по FBO и знает про поставки в пути; если недоступна — остаёмся на данных карточек.
        val clusters = ArrayList<ClusterStock>()
        try {
            for (chunk in bySku.keys.toList().chunked(100)) {
                val text = post("/v1/analytics/stocks", buildJsonObject {
                    putJsonArray("skus") { chunk.forEach { add(JsonPrimitive(it.toString())) } }
                })
                clusters += parseClusterStocks(text)
                val a = parseAnalyticsStocks(text)
                for ((sku, s) in a) {
                    val old = bySku[sku] ?: Stock(sku)
                    bySku[sku] = old.copy(fbo = s.fbo, inTransit = s.inTransit, returning = s.returning)
                }
            }
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) throw e
        }
        return StocksResult(bySku.values.toList(), offers, clusters)
    }

    /** Справочник видов начислений. */
    fun feeTypes(): FeeTypes = FeeTypes.parse(post("/v1/finance/accrual/types", buildJsonObject { }))

    /** Начисления за один день (по дате начисления Ozon), свёрнутые по товарам, и суммы по видам начислений. */
    fun accruals(date: LocalDate, types: FeeTypes): Pair<List<FinDay>, List<TypeTotal>> {
        val acc = FinAccumulator()
        val tt = TypeAccumulator()
        var lastId = ""
        var dateArg = date.toString()
        for (page in 0 until 5000) {
            val text = try {
                post("/v1/finance/accrual/by-day", buildJsonObject { put("date", dateArg); put("last_id", lastId) })
            } catch (e: ApiException) {
                // На случай, если метод ждёт дату со временем.
                if (e.code == 400 && page == 0 && dateArg.length == 10) {
                    dateArg = date.toString() + "T00:00:00Z"
                    post("/v1/finance/accrual/by-day", buildJsonObject { put("date", dateArg); put("last_id", lastId) })
                } else throw e
            }
            val p = parseAccruals(text, types, date.toString())
            p.rows.forEach(acc::add)
            tt.addAll(p.types)
            if (p.count == 0 || p.lastId.isEmpty() || p.lastId == lastId) break
            lastId = p.lastId
        }
        // Всё, что пришло, относим к запрошенному дню: так день перезаписывается целиком при обновлении.
        val day = date.toString()
        val out = FinAccumulator()
        acc.rows().forEach { out.add(it.copy(date = day)) }
        return out.rows() to tt.list()
    }

    /** Заказы FBO и FBS за дни [from; to] по московскому времени. */
    fun orders(from: LocalDate, to: LocalDate): Pair<List<OrderDay>, Map<Long, Product>> {
        val map = LinkedHashMap<String, OrderDay>()
        val names = HashMap<Long, Product>()
        val since = from.atStartOfDay(MOSCOW).withZoneSameInstant(ZoneOffset.UTC).toInstant().toString()
        val till = to.plusDays(1).atStartOfDay(MOSCOW).withZoneSameInstant(ZoneOffset.UTC).toInstant().minusSeconds(1).toString()
        for (path in listOf("/v3/posting/fbo/list", "/v4/posting/fbs/list")) {
            var cursor = ""
            var limit = 1000
            var page = 0
            while (page < 2000) {
                val body = buildJsonObject {
                    put("cursor", cursor)
                    putJsonObject("filter") { put("since", since); put("to", till) }
                    put("limit", limit)
                    put("sort_dir", "ASC")
                }
                val text = try {
                    post(path, body)
                } catch (e: ApiException) {
                    if (e.code == 400 && limit > 100 && page == 0) {
                        limit = 100
                        continue
                    }
                    // Продавец может не работать по одной из схем — это не ошибка.
                    if (e.code == 400 || e.code == 404) break
                    throw e
                }
                val p = parsePostings(text)
                for (r in p.rows) {
                    if (r.date < from.toString() || r.date > to.toString()) continue
                    val key = r.date + "|" + r.sku
                    map[key] = map[key]?.plus(r) ?: r
                }
                for ((sku, pr) in p.names) names.putIfAbsent(sku, pr)
                if (!p.hasNext || p.cursor.isEmpty() || p.cursor == cursor || p.count == 0) break
                cursor = p.cursor
                page++
            }
        }
        return map.values.toList() to names
    }
}

/** Клиент Ozon Performance API (реклама): статистика кампаний по дням. */
class PerformanceClient(
    private val clientId: String,
    private val secret: String,
    private val http: Transport = HttpTransport(),
    private val base: String = "https://api-performance.ozon.ru",
) {
    private fun token(): String {
        val body = buildJsonObject {
            put("client_id", clientId.trim())
            put("client_secret", secret.trim())
            put("grant_type", "client_credentials")
        }
        val t = parseJson(http.request("POST", "$base/api/client/token", emptyMap(), body.toString())).str("access_token")
        if (t.isEmpty()) throw ApiException(401, "Performance API не выдал токен")
        return t
    }

    fun daily(from: LocalDate, to: LocalDate): List<AdDay> {
        val text = http.request(
            "GET", "$base/api/client/statistics/daily/json?dateFrom=$from&dateTo=$to",
            mapOf("Authorization" to "Bearer " + token()), null,
        )
        return parseAdDaily(text)
    }
}

/** Делит период на отрезки не длиннее [size] дней. */
fun dayChunks(from: LocalDate, to: LocalDate, size: Int): List<Pair<LocalDate, LocalDate>> {
    val out = ArrayList<Pair<LocalDate, LocalDate>>()
    var a = from
    while (!a.isAfter(to)) {
        val b = minOf(a.plusDays(size - 1L), to)
        out += a to b
        a = b.plusDays(1)
    }
    return out
}
