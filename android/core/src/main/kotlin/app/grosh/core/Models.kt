package app.grosh.core

/** Маркетплейсы, траты по которым выделяются в отдельную аналитику. */
enum class Marketplace(val title: String, val shortTitle: String, val colorHex: Long) {
    OZON("Ozon", "Ozon", 0xFF005BFF),
    WILDBERRIES("Wildberries", "WB", 0xFFCB11AB),
    ALIEXPRESS("AliExpress", "Ali", 0xFFE8402A),
    YANDEX_MARKET("Яндекс Маркет", "Маркет", 0xFFF5B800),
    MEGAMARKET("Мегамаркет", "Мегамаркет", 0xFF21A038),
    LAMODA("Lamoda", "Lamoda", 0xFF555555);

    companion object {
        fun from(name: String?): Marketplace? = entries.firstOrNull { it.name == name }
    }
}

enum class SpendCategory(val title: String, val emoji: String, val colorHex: Long, val isIncome: Boolean = false) {
    GROCERIES("Продукты", "🛒", 0xFF34C759),
    CAFES("Кафе и еда", "🍽️", 0xFFFF9500),
    TRANSPORT("Транспорт", "🚕", 0xFF0A84FF),
    MARKETPLACES("Маркетплейсы", "📦", 0xFFBF5AF2),
    SHOPPING("Одежда", "👕", 0xFFFF2D55),
    ELECTRONICS("Техника", "💻", 0xFF5E5CE6),
    UTILITIES("ЖКХ и связь", "🏠", 0xFF8E8E93),
    HEALTH("Здоровье", "💊", 0xFFFF453A),
    ENTERTAINMENT("Развлечения", "🎮", 0xFFE0B400),
    SUBSCRIPTIONS("Подписки", "🔁", 0xFF32ADE6),
    TRAVEL("Путешествия", "✈️", 0xFF30B0C7),
    TRANSFERS("Переводы", "↔️", 0xFFAC8E68),
    OTHER("Другое", "🧾", 0xFF98989D),
    SALARY("Зарплата", "💰", 0xFF00B37A, isIncome = true),
    INCOME_OTHER("Другой доход", "➕", 0xFF00B37A, isIncome = true);

    companion object {
        val expenseCases get() = entries.filter { !it.isIncome }
        val incomeCases get() = entries.filter { it.isIncome }
        fun from(name: String?): SpendCategory = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Откуда запись попала в приложение. */
enum class EntrySource(val title: String, val emoji: String, val isAutomatic: Boolean = false) {
    MANUAL("Вручную", "✍️"),
    PUSH("Уведомление банка", "🔔", isAutomatic = true),
    MIR_PAY("Mir Pay", "📲", isAutomatic = true),
    SMS("SMS банка", "💬", isAutomatic = true),
    PASTE("Из сообщения", "📋"),
    CSV("Выписка CSV", "📄", isAutomatic = true),
    PDF("Выписка PDF", "📑", isAutomatic = true),
    QR("Чек по QR", "🔳"),
    DEMO("Демо", "✨");

    companion object {
        fun from(name: String?): EntrySource = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/**
 * Операция, распознанная из внешнего источника.
 * Сумма хранится в копейках, всегда положительная; знак задаёт [isIncome].
 */
data class ParsedTxn(
    val amountMinor: Long,
    val currency: String = "RUB",
    val dateMillis: Long,
    val merchant: String,
    val isIncome: Boolean = false,
    val cardTail: String? = null,
)
