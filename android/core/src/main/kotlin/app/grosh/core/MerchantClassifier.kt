package app.grosh.core

/** Определяет категорию и маркетплейс по названию продавца из банка/чека. */
object MerchantClassifier {

    /** Верхний регистр, Ё→Е, всё кроме букв/цифр/& заменяется пробелом. */
    fun normalize(raw: String): String {
        val out = StringBuilder()
        var lastWasSpace = true
        for (ch in raw.uppercase().replace('Ё', 'Е')) {
            if (ch.isLetterOrDigit() || ch == '&') {
                out.append(ch)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        return out.toString().trim()
    }

    fun marketplace(merchant: String): Marketplace? {
        val n = normalize(merchant)
        if (n.isEmpty()) return null
        val tokens = n.split(' ').toSet()
        fun has(vararg keys: String) = keys.any { n.contains(it) }

        return when {
            has("YANDEX MARKET", "MARKET YANDEX", "YANDEXMARKET", "ЯНДЕКС МАРКЕТ", "YM MARKET") -> Marketplace.YANDEX_MARKET
            has("MEGAMARKET", "МЕГАМАРКЕТ") -> Marketplace.MEGAMARKET
            // Ozon Банк — это переводы, а не покупки на маркетплейсе.
            has("OZON", "ОЗОН") -> if (has("BANK", "БАНК")) null else Marketplace.OZON
            has("WILDBERR", "ВАЙЛДБЕРРИ") || "WB" in tokens || "ВБ" in tokens -> Marketplace.WILDBERRIES
            has("ALIEXPRESS", "ALI EXPRESS", "АЛИЭКСПРЕСС", "ALIBABA") -> Marketplace.ALIEXPRESS
            has("LAMODA", "ЛАМОДА") -> Marketplace.LAMODA
            else -> null
        }
    }

    private val rules: List<Pair<SpendCategory, List<String>>> = listOf(
        SpendCategory.SUBSCRIPTIONS to listOf(
            "YANDEX PLUS", "ЯНДЕКС ПЛЮС", "NETFLIX", "SPOTIFY", "APPLE COM", "ITUNES", "KINOPOISK", "КИНОПОИСК",
            " IVI", "OKKO", "YOUTUBE", "TELEGRAM", "VK MUSIC", "ICLOUD", "CHATGPT", "OPENAI", "GOOGLE ONE", "ПОДПИСК",
        ),
        SpendCategory.GROCERIES to listOf(
            "PYATEROCHKA", "ПЯТЕРОЧКА", "MAGNIT", "МАГНИТ", "PEREKRESTOK", "ПЕРЕКРЕСТОК", "LENTA", "ЛЕНТА", "VKUSVILL",
            "ВКУСВИЛЛ", "AUCHAN", "АШАН", "SAMOKAT", "САМОКАТ", "DIXY", "ДИКСИ", "METRO C&C", "METRO CASH", "LAVKA",
            "ЛАВКА", "AZBUKA VKUSA", "АЗБУКА ВКУСА", " SPAR", "OKEY", "ПРОДУКТЫ", "PRODUKTY", "СУПЕРМАРКЕТ", "KRASNOE",
            "КРАСНОЕ&БЕЛОЕ", "КРАСНОЕ БЕЛОЕ", "FIX PRICE", "ФИКС ПРАЙС", "ЧИЖИК", "CHIZHIK", "ВЕРНЫЙ", "VERNYI",
        ),
        SpendCategory.CAFES to listOf(
            "KFC", "BURGER", "ВКУСНО И ТОЧКА", "VKUSNO I TOCHKA", "DODO", "ДОДО", "COFFEE", "КОФЕ", "CAFE", "КАФЕ",
            "RESTAURANT", "РЕСТОРАН", "SHOKOLADNICA", "ШОКОЛАДНИЦА", "YANDEX EDA", "ЯНДЕКС ЕДА", "DELIVERY CLUB",
            "STARBUCKS", "PIZZA", "ПИЦЦА", "SUSHI", "СУШИ", "ROSTIC", "STOLOVAYA", "СТОЛОВАЯ", "BAKERY", "ПЕКАРНЯ",
            "ФАСТФУД", "ШАУРМА", "TEREMOK", "ТЕРЕМОК",
        ),
        SpendCategory.TRANSPORT to listOf(
            "YANDEX GO", "YANDEX TAXI", "ЯНДЕКС ТАКСИ", "ЯНДЕКС GO", "TAXI", "ТАКСИ", "UBER", "CITYMOBIL", "MOSMETRO",
            "METRO", "МЕТРО", "LUKOIL", "ЛУКОЙЛ", "GAZPROMNEFT", "ГАЗПРОМНЕФТЬ", "ROSNEFT", "РОСНЕФТЬ", "АЗС", "AZS",
            "PARKING", "ПАРКОВК", "TROIKA", "ТРОЙКА", "WHOOSH", "КАРШЕРИНГ", "DELIMOBIL", "ТРАНСПОРТ", "ТОПЛИВО",
        ),
        SpendCategory.TRAVEL to listOf(
            "RZD", "РЖД", "AEROFLOT", "АЭРОФЛОТ", " S7", "POBEDA", "ПОБЕДА", "AVIASALES", "BOOKING", "OSTROVOK",
            "ОСТРОВОК", "HOTEL", "ОТЕЛЬ", "AIRBNB", "TUTU", "ТУТУ", "АВИАБИЛЕТ",
        ),
        SpendCategory.HEALTH to listOf(
            "APTEKA", "АПТЕК", "CLINIC", "КЛИНИКА", "GORZDRAV", "ГОРЗДРАВ", "STOMAT", "СТОМАТ", "INVITRO", "ИНВИТРО",
            "GEMOTEST", "ГЕМОТЕСТ", "PHARM", "ФАРМ", "MEDSI", "МЕДСИ", "МЕДИЦИН",
        ),
        SpendCategory.UTILITIES to listOf(
            "ЖКХ", "ZHKH", "MOSENERGO", "МОСЭНЕРГО", " MTS", " МТС", "BEELINE", "БИЛАЙН", "MEGAFON", "МЕГАФОН", "TELE2",
            "ТЕЛЕ2", " T2 ", "ROSTELECOM", "РОСТЕЛЕКОМ", "GOSUSLUGI", "ГОСУСЛУГИ", "КВАРТПЛАТА", "ЭЛЕКТРО", "МОБИЛЬНАЯ СВЯЗЬ",
            "КОММУНАЛ",
        ),
        SpendCategory.ELECTRONICS to listOf(
            " DNS", "MVIDEO", "М ВИДЕО", "ELDORADO", "ЭЛЬДОРАДО", "CITILINK", "СИТИЛИНК", "RE STORE", "RESTORE",
        ),
        SpendCategory.SHOPPING to listOf(
            "ZARA", "H&M", "GLORIA JEANS", "SPORTMASTER", "СПОРТМАСТЕР", "UNIQLO", "DECATHLON", "ДЕТСКИЙ МИР",
            "DETSKY MIR", "LETUAL", "ЛЭТУАЛЬ", "ЗОЛОТОЕ ЯБЛОКО", "GOLD APPLE", "IKEA", "ЛЕМАНА", "LEROY", "ОДЕЖДА",
        ),
        SpendCategory.ENTERTAINMENT to listOf(
            "KINO", "КИНО", "CINEMA", "STEAM", "PLAYSTATION", "XBOX", "THEATRE", "ТЕАТР", "CONCERT", "КОНЦЕРТ",
            "KASSIR", "АФИША", "AFISHA", "BOWLING", "FITNESS", "ФИТНЕС",
        ),
        SpendCategory.TRANSFERS to listOf("ПЕРЕВОД", "PEREVOD", "СБП", "SBP", "TRANSFER", "P2P", "СНЯТИЕ", "НАЛИЧНЫ"),
    )

    private val salaryKeys = listOf("ЗАРПЛАТ", "ЗАРАБОТН", "SALARY", "PAYROLL", "АВАНС")

    fun category(merchant: String, isIncome: Boolean = false): SpendCategory {
        val n = normalize(merchant)
        if (isIncome) {
            return if (salaryKeys.any { n.contains(it) }) SpendCategory.SALARY else SpendCategory.INCOME_OTHER
        }
        if (marketplace(merchant) != null) return SpendCategory.MARKETPLACES
        val padded = " $n "
        return rules.firstOrNull { (_, keys) -> keys.any { padded.contains(it) } }?.first ?: SpendCategory.OTHER
    }
}
