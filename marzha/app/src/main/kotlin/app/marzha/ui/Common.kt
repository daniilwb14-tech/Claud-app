package app.marzha.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marzha.Period
import app.marzha.core.DayPoint
import app.marzha.core.Product
import coil.compose.AsyncImage
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ───────────────────────── Цвета ─────────────────────────

val Green = Color(0xFF0B6E5B)
val GreenDark = Color(0xFF0B5D4D)
private val ProfitLight = Color(0xFF0E8F5B)
private val LossLight = Color(0xFFD13A3A)
private val WarnLight = Color(0xFFB26A00)
private val ProfitDark = Color(0xFF6FD9B8)
private val LossDark = Color(0xFFFF8A80)
private val WarnDark = Color(0xFFFFC56B)

private val LightColors = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F2E8), onPrimaryContainer = Color(0xFF00382D),
    secondaryContainer = Color(0xFFD6F2E8), onSecondaryContainer = Color(0xFF00382D),
    background = Color(0xFFF4F6F3), onBackground = Color(0xFF171D1A),
    surface = Color.White, onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFE9EEEA), onSurfaceVariant = Color(0xFF5C6A64),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEFF3F0), surfaceContainerHighest = Color(0xFFE9EEEA),
    outline = Color(0xFFB9C4BE), outlineVariant = Color(0xFFDCE3DE),
    error = LossLight,
)

private val DarkColors = darkColorScheme(
    primary = ProfitDark, onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0B5D4D), onPrimaryContainer = Color(0xFFD6F2E8),
    secondaryContainer = Color(0xFF1F3B33), onSecondaryContainer = Color(0xFFD6F2E8),
    background = Color(0xFF0F1412), onBackground = Color(0xFFE3E9E5),
    surface = Color(0xFF171D1A), onSurface = Color(0xFFE3E9E5),
    surfaceVariant = Color(0xFF232B27), onSurfaceVariant = Color(0xFF9EABA4),
    surfaceContainer = Color(0xFF171D1A), surfaceContainerHigh = Color(0xFF1E2622), surfaceContainerHighest = Color(0xFF232B27),
    outline = Color(0xFF55615B), outlineVariant = Color(0xFF2E3833),
    error = LossDark,
)

@Composable
fun MarzhaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

val profitColor: Color
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) ProfitDark else ProfitLight
val lossColor: Color
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) LossDark else LossLight
val warnColor: Color
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) WarnDark else WarnLight

@Composable
@ReadOnlyComposable
fun signColor(v: Double): Color = when {
    v > 0.5 -> profitColor
    v < -0.5 -> lossColor
    else -> MaterialTheme.colorScheme.onSurface
}

// ───────────────────────── Числа ─────────────────────────

private val symbols = DecimalFormatSymbols(Locale("ru")).apply {
    groupingSeparator = ' '
    decimalSeparator = ','
    minusSign = '−'
}
private val f0 = DecimalFormat("#,##0", symbols)
private val f1 = DecimalFormat("#,##0.0", symbols)

/** 1 289 168 ₽ */
fun rub(v: Double): String = f0.format(if (abs(v) < 0.5) 0.0 else v) + " ₽"

/** Сумма на штуку: мелкие значения — с копейками. */
fun rubUnit(v: Double): String = (if (abs(v) < 100) f1.format(v) else f0.format(v)) + " ₽"

/** Коротко для плиток: 1,29 млн ₽, 438 тыс ₽. */
fun rubShort(v: Double): String = when {
    abs(v) >= 1_000_000 -> DecimalFormat("#,##0.00", symbols).format(v / 1_000_000) + " млн ₽"
    abs(v) >= 100_000 -> f0.format(v / 1000) + " тыс ₽"
    else -> rub(v)
}

fun pct(v: Double): String = f1.format(v * 100) + "%"
fun qty(n: Int): String = f0.format(n)
fun num(v: Double): String = if (abs(v) < 100) f1.format(v) else f0.format(v)
fun dayMonth(d: LocalDate): String = "%02d.%02d".format(d.dayOfMonth, d.monthValue)
fun dayMonth(iso: String): String = if (iso.length >= 10) iso.substring(8, 10) + "." + iso.substring(5, 7) else iso

/** Число из поля ввода: понимает запятую и пробелы. */
fun parseNum(s: String): Double = s.replace(" ", "").replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
fun fieldNum(v: Double): String = when {
    v == 0.0 -> ""
    v == Math.floor(v) -> v.toLong().toString()
    else -> v.toString().replace('.', ',')
}

// ───────────────────────── Кирпичики интерфейса ─────────────────────────

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Плитка показателя. */
@Composable
fun Kpi(title: String, value: String, sub: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Строка «название — значение». */
@Composable
fun Line(label: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false, sub: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            if (sub.isNotEmpty()) Hint(sub)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = valueColor)
    }
}

@Composable
fun PeriodChips(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in Period.entries) {
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.title) })
        }
    }
}

@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, title) in options) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(title) })
        }
    }
}

@Composable
fun ProductImage(product: Product, size: Int = 44) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (product.offerId.ifEmpty { product.name }).take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold,
        )
        if (product.image.isNotEmpty()) {
            AsyncImage(model = product.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Заголовок товара: артикул продавца крупно, название Ozon — подписью. */
fun productTitle(p: Product): String = p.offerId.ifEmpty { p.name.ifEmpty { "SKU ${p.sku}" } }

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String = "₽") {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == ',' || it == '.' }.take(12)) },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Столбики прибыли по дням: зелёные — плюс, красные — минус. */
@Composable
fun ProfitBars(points: List<DayPoint>, modifier: Modifier = Modifier) {
    val up = profitColor
    val down = lossColor
    val axis = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val maxUp = max(points.maxOf { it.profit }, 0.0)
        val maxDown = max(-points.minOf { it.profit }, 0.0)
        val span = (maxUp + maxDown).takeIf { it > 0 } ?: 1.0
        val zeroY = (size.height * (maxUp / span)).toFloat()
        val step = size.width / points.size
        val barW = (step * 0.68f).coerceAtMost(28.dp.toPx())
        val r = CornerRadius(barW / 4, barW / 4)
        for ((i, p) in points.withIndex()) {
            val h = (size.height * (abs(p.profit) / span)).toFloat().coerceAtLeast(if (p.profit != 0.0) 2f else 0f)
            val x = i * step + (step - barW) / 2
            drawRoundRect(
                color = if (p.profit >= 0) up else down,
                topLeft = Offset(x, if (p.profit >= 0) zeroY - h else zeroY),
                size = Size(barW, h), cornerRadius = r,
            )
        }
        drawLine(axis, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
    }
}

/** Тонкая полоска доли. */
@Composable
fun ShareBar(fraction: Double, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
    }
}
