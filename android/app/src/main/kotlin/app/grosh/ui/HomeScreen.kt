package app.grosh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import app.grosh.AppViewModel
import app.grosh.Overlay
import app.grosh.capture.CaptureSources
import app.grosh.core.EntrySource
import app.grosh.core.Insights
import app.grosh.core.ReceiptQrParser
import app.grosh.data.Entry
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.LocalDate
import java.time.YearMonth

/** Запускает сканер QR-кода чека; результат открывается в форме новой операции. */
@Composable
fun rememberReceiptScanner(vm: AppViewModel, open: (Overlay) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val payload = result.contents ?: return@rememberLauncherForActivityResult
        val txn = ReceiptQrParser.parse(payload)
        if (txn == null) {
            vm.message = "Это не QR-код кассового чека."
        } else {
            open(Overlay.Edit(prefill = txn, source = EntrySource.QR))
        }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Наведите камеру на QR-код внизу чека")
                .setBeepEnabled(false)
                .setOrientationLocked(true),
        )
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, entries: List<Entry>, open: (Overlay) -> Unit, goToMore: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val monthEntries = entries.inMonth(month)
    val expenses = monthEntries.expenses()
    val spent = expenses.total()
    val income = monthEntries.incomes().total()
    val spentToday = expenses.filter { it.day == today }.total()
    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
    val budgetMinor = vm.budget * 100
    val safeToday = Insights.safeToSpendToday(budgetMinor, spent - spentToday, spentToday, daysLeft)
    val scan = rememberReceiptScanner(vm, open)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(monthTitle(month), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Главная карточка: дневной лимит.
        val over = budgetMinor in 1 until spent
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0B6B56), BrandDeep)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Можно потратить сегодня", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
            Text(moneyShort(safeToday), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            LinearProgressIndicator(
                progress = { if (budgetMinor > 0) (spent.toFloat() / budgetMinor).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (over) Color(0xFFFF6B5E) else Color.White,
                trackColor = Color.White.copy(alpha = 0.18f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Потрачено ${moneyShort(spent)} из ${moneyShort(budgetMinor)}", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
                Text("ещё $daysLeft ${daysWord(daysLeft)}", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
            }
            if (over) {
                Text("⚠️ Бюджет превышен на ${moneyShort(spent - budgetMinor)}", color = Color(0xFFFFD0CB), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("✍️", "Вручную", Modifier.weight(1f)) { open(Overlay.Edit()) }
            QuickAction("📋", "Из сообщения", Modifier.weight(1f)) { open(Overlay.Paste) }
            QuickAction("🔳", "Скан чека", Modifier.weight(1f), scan)
        }

        if (!CaptureSources.isListenerEnabled(context)) {
            GCard(onClick = goToMore) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge("🔔", Brand)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Включите автозапись", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Грош будет сам записывать покупки из уведомлений банков, Mir Pay и SMS.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Расходы", moneyShort(spent), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Stat("Доходы", moneyShort(income), IncomeColor, Modifier.weight(1f))
        }

        val markets = expenses.byMarketplace()
        if (markets.isNotEmpty()) {
            val total = markets.sumOf { it.total }
            GCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📦 Маркетплейсы", fontWeight = FontWeight.SemiBold)
                    Text("${if (spent > 0) total * 100 / spent else 0}% расходов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(moneyShort(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    markets.forEach { Box(Modifier.weight(it.total.toFloat().coerceAtLeast(1f)).height(10.dp).background(it.marketplace.color)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    markets.take(3).joinToString("   ") { "${it.marketplace.shortTitle} ${moneyShort(it.total)}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }

        if (entries.isEmpty()) {
            EmptyState("🗂️", "Пока нет операций", "Добавьте первую трату вручную или включите автозапись во вкладке «Ещё».")
        } else {
            SectionTitle("Последние операции", Modifier.padding(top = 4.dp))
            GCard {
                entries.take(8).forEach { entry -> EntryRow(entry) { open(Overlay.Edit(entry)) } }
            }
        }
    }
}

@Composable
private fun QuickAction(emoji: String, title: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(emoji, fontSize = 22.sp)
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Stat(title: String, value: String, color: Color, modifier: Modifier) {
    GCard(modifier) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var budgetText by remember { mutableStateOf(vm.budget.toString()) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(BrandDeep), contentAlignment = Alignment.Center) {
            Text("₽", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Text("Грош считает траты за вас", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Feature("🔔", "Сам записывает покупки", "Читает уведомления банков, Mir Pay и SMS — операции попадают в приложение без вашего участия.")
        Feature("📦", "Видит маркетплейсы", "Отдельно показывает, сколько уходит на Ozon, Wildberries и AliExpress.")
        Feature("🎯", "Говорит, сколько можно сегодня", "Один понятный лимит на день вместо сложных таблиц.")
        OutlinedTextField(
            value = budgetText,
            onValueChange = { budgetText = it.filter(Char::isDigit).take(9) },
            label = { Text("Бюджет на месяц, ₽") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                budgetText.toLongOrNull()?.let(vm::updateBudget)
                vm.finishOnboarding(withDemo = false)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Начать") }
        TextButton(
            onClick = {
                budgetText.toLongOrNull()?.let(vm::updateBudget)
                vm.finishOnboarding(withDemo = true)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Посмотреть на демо-данных") }
    }
}

@Composable
private fun Feature(emoji: String, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        EmojiBadge(emoji, Brand, 46.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
