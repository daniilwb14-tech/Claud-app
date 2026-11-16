package app.grosh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grosh.core.Marketplace
import app.grosh.core.SpendCategory
import app.grosh.data.Entry
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val Brand = Color(0xFF00A870)
val BrandDeep = Color(0xFF063D33)
val IncomeColor = Color(0xFF00A870)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E8),
    onPrimaryContainer = Color(0xFF00382A),
    secondaryContainer = Color(0xFFD3F5E8),
    onSecondaryContainer = Color(0xFF00382A),
    background = Color(0xFFF2F4F3),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7ECEA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDFA6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B5A46),
    onPrimaryContainer = Color(0xFFD3F5E8),
    secondaryContainer = Color(0xFF0B5A46),
    onSecondaryContainer = Color(0xFFD3F5E8),
    background = Color(0xFF0E1412),
    surface = Color(0xFF18201D),
    surfaceVariant = Color(0xFF26302C),
    surfaceContainer = Color(0xFF18201D),
    surfaceContainerHigh = Color(0xFF202A26),
)

@Composable
fun GroshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

val SpendCategory.color: Color get() = Color(colorHex)
val Marketplace.color: Color get() = Color(colorHex)

// MARK: Форматирование

private val ru = Locale("ru", "RU")

private fun grouped(value: Long): String {
    val digits = value.toString()
    val out = StringBuilder()
    for ((i, ch) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) out.append('\u00A0')
        out.append(ch)
    }
    return out.toString()
}

private fun symbol(currency: String) = when (currency) {
    "RUB" -> "₽"
    "USD" -> "$"
    "EUR" -> "€"
    "KZT" -> "₸"
    else -> currency
}

/** «1 234,50 ₽»; копейки показываются, только если они есть. */
fun money(minor: Long, currency: String = "RUB"): String {
    val abs = kotlin.math.abs(minor)
    val kopecks = abs % 100
    val body = grouped(abs / 100) + if (kopecks != 0L) ",%02d".format(kopecks) else ""
    return (if (minor < 0) "−" else "") + body + "\u00A0" + symbol(currency)
}

/** Без копеек — для крупных цифр на карточках. */
fun moneyShort(minor: Long, currency: String = "RUB"): String {
    val rounded = Math.round(minor / 100.0)
    return (if (rounded < 0) "−" else "") + grouped(kotlin.math.abs(rounded)) + "\u00A0" + symbol(currency)
}

fun monthTitle(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.titlecase(ru) }

fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", ru))
    }
}

fun daysWord(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "день"
        mod10 in 2..4 && mod100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

// MARK: Подсчёты

fun List<Entry>.expenses() = filter { !it.isIncome }
fun List<Entry>.incomes() = filter { it.isIncome }
fun List<Entry>.total() = sumOf { it.amountMinor }
fun List<Entry>.inMonth(month: YearMonth) = filter { YearMonth.from(it.day) == month }

data class MarketTotal(val marketplace: Marketplace, val total: Long, val count: Int)

fun List<Entry>.byMarketplace(): List<MarketTotal> =
    filter { it.market != null }.groupBy { it.market!! }
        .map { (market, items) -> MarketTotal(market, items.total(), items.size) }
        .sortedByDescending { it.total }

fun List<Entry>.byCategory(): List<Pair<SpendCategory, Long>> =
    groupBy { it.cat }.map { (category, items) -> category to items.total() }.sortedByDescending { it.second }

// MARK: Общие элементы

@Composable
fun GCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun EmojiBadge(emoji: String, color: Color, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.46f).sp)
    }
}

@Composable
fun EntryRow(entry: Entry, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiBadge(entry.cat.emoji, entry.market?.color ?: entry.cat.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = if (entry.src == app.grosh.core.EntrySource.MANUAL) "" else " · ${entry.src.emoji} ${entry.src.title}"
            Text(
                (entry.market?.title ?: entry.cat.title) + source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (entry.isIncome) "+" else "−") + money(entry.amountMinor, entry.currency),
            fontWeight = FontWeight.SemiBold,
            color = if (entry.isIncome) IncomeColor else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun EmptyState(emoji: String, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Значок «столбики» для вкладки аналитики (в базовом наборе Material его нет). */
val BarsIcon: ImageVector = ImageVector.Builder("Bars", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(4f, 12f); horizontalLineTo(8f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(10f, 4f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(10f); close()
        moveTo(16f, 9f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(16f); close()
    }
}.build()
