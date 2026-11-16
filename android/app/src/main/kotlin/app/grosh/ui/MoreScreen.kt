package app.grosh.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.grosh.AppViewModel
import app.grosh.Overlay
import app.grosh.capture.CaptureSources
import app.grosh.data.Entry

private fun hasSmsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun MoreScreen(vm: AppViewModel, entries: List<Entry>, open: (Overlay) -> Unit) {
    val context = LocalContext.current
    val scan = rememberReceiptScanner(vm, open)

    // Статусы доступов перечитываются при возврате из системных настроек.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listenerOn = remember(refresh) { CaptureSources.isListenerEnabled(context) }
    val smsOn = remember(refresh) { hasSmsPermission(context) }

    var confirmDelete by remember { mutableStateOf(false) }
    var smsDenied by remember { mutableStateOf(false) }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        refresh++
        if (granted.values.all { it }) vm.importSmsHistory() else smsDenied = true
    }
    val pickStatement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readStatement(uri)
    }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionTitle("Автозапись операций")
        GCard {
            MenuRow(
                "🔔", "Уведомления банков и Mir Pay",
                if (listenerOn) "Включено — покупки записываются сами" else "Сбер, Т-Банк, Альфа, ВТБ, Mir Pay и другие",
                status = if (listenerOn) "✓" else "Включить",
            ) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow(
                "💬", "SMS от банков",
                if (smsOn) "Включено — новые SMS разбираются сами" else "Новые SMS и импорт истории за полгода",
                status = if (smsOn) "✓" else "Включить",
            ) {
                if (smsOn) vm.message = "Доступ к SMS уже выдан. Новые сообщения банков записываются автоматически."
                else smsPermission.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
            }
            if (smsOn) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuRow("🕘", "Загрузить историю SMS", "Операции из SMS банков за последние 6 месяцев") { vm.importSmsHistory() }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("❓", "Не получается включить?", "Как снять ограничение Android для приложений не из магазина") { open(Overlay.Guide) }
        }

        SectionTitle("Добавить операции")
        GCard {
            MenuRow("📑", "Импорт выписки", "PDF или CSV из Т-Банка, Сбера, Альфы и других банков") {
                pickStatement.launch(arrayOf("application/pdf", "text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("📋", "Вставить текст сообщения", "SMS или пуш банка, скопированный вручную") { open(Overlay.Paste) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("🔳", "Скан QR-кода с чека", "Сумма и дата читаются с кассового чека", onClick = scan)
        }

        SectionTitle("Инструменты")
        GCard {
            MenuRow("🔁", "Радар подписок", "Регулярные списания и их стоимость в год") { open(Overlay.Subscriptions) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("📤", "Экспорт в CSV", "Все операции одним файлом для Excel") {
                if (entries.isEmpty()) vm.message = "Пока нечего экспортировать." else saveExport.launch("grosh-export.csv")
            }
        }

        SectionTitle("Бюджет")
        GCard {
            NumberField("Бюджет на месяц, ₽", vm.budget, vm::updateBudget)
            Spacer(Modifier.padding(top = 10.dp))
            NumberField("Доход за час работы, ₽", vm.hourlyRate, vm::updateHourlyRate)
            Text(
                "Если указать доход за час, Грош покажет, сколько часов работы стоит каждая покупка.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionTitle("Данные")
        GCard {
            MenuRow("✨", "Добавить демо-данные", "Три месяца примеров, чтобы посмотреть приложение в деле") { vm.addDemo() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("🗑️", "Удалить все операции", "Операций в базе: ${entries.size}") {
                if (entries.isNotEmpty()) confirmDelete = true
            }
        }

        Text(
            "Все данные хранятся только на этом устройстве. Тестовая сборка 1.0 — все функции открыты.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить все операции?") },
            text = { Text("Это действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmDelete = false }) { Text("Удалить всё") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
    if (smsDenied) {
        AlertDialog(
            onDismissRequest = { smsDenied = false },
            title = { Text("Доступ к SMS не выдан") },
            text = { Text("Если Android не показал запрос или переключатель неактивен, откройте настройки приложения → ⋮ → «Разрешить ограниченные настройки», затем выдайте разрешение «SMS».") },
            confirmButton = { TextButton(onClick = { smsDenied = false; openAppSettings(context) }) { Text("Открыть настройки") } },
            dismissButton = { TextButton(onClick = { smsDenied = false }) { Text("Позже") } },
        )
    }
}

@Composable
private fun MenuRow(emoji: String, title: String, subtitle: String, status: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiBadge(emoji, Brand, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (status != null) {
            Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        } else {
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NumberField(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(if (value == 0L) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit).take(9)
            onChange(text.toLongOrNull() ?: 0L)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: Инструкция по доступам

@Composable
fun GuideScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    OverlayScaffold("Как включить автозапись", onClose) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Android 13 и новее по умолчанию не даёт приложениям, установленным из файла (не из магазина), читать уведомления и SMS. Это снимается один раз вручную.",
                style = MaterialTheme.typography.bodyMedium,
            )
            GCard {
                Text("Шаг 1. Снять ограничение", fontWeight = FontWeight.SemiBold)
                Steps(
                    "Нажмите кнопку ниже — откроются настройки приложения «Грош».",
                    "Нажмите ⋮ в правом верхнем углу → «Разрешить ограниченные настройки» и подтвердите.",
                    "Если пункта ⋮ нет — сначала попробуйте включить доступ (шаг 2): после отказа Android пункт появится.",
                )
                TextButton(onClick = { openAppSettings(context) }) { Text("Открыть настройки приложения") }
            }
            GCard {
                Text("Шаг 2. Доступ к уведомлениям", fontWeight = FontWeight.SemiBold)
                Steps(
                    "Откройте список приложений с доступом к уведомлениям.",
                    "Найдите «Грош» и включите переключатель.",
                    "Проверьте, что у банковских приложений и Mir Pay уведомления включены и показывают текст (не «скрыто»).",
                )
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Открыть доступ к уведомлениям") }
            }
            GCard {
                Text("Шаг 3. Чтобы Android не выгружал Грош", fontWeight = FontWeight.SemiBold)
                Steps(
                    "В настройках приложения откройте «Батарея» и выберите «Без ограничений».",
                    "На Xiaomi, Huawei, Honor, Realme дополнительно включите «Автозапуск».",
                )
            }
            Text(
                "Грош читает только уведомления банков, Mir Pay и SMS-приложений, и только чтобы найти в них сумму и продавца. Данные не покидают телефон: у приложения нет доступа в интернет.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row {
                Text("${index + 1}.", fontWeight = FontWeight.Bold, color = Color(0xFF00A870), modifier = Modifier.width(22.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
