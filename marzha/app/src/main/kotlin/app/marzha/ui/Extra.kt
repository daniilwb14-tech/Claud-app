package app.marzha.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.marzha.AppViewModel
import app.marzha.BuildConfig
import app.marzha.core.FeeClass

@Composable
private fun Header(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private enum class Topic(val title: String) { QUESTION("Вопрос"), BUG("Ошибка в цифрах"), IDEA("Идея"), PAYMENT("Оплата") }

/**
 * Поддержка: продавец пишет вопрос, приложение открывает почту с готовым письмом на адрес поддержки.
 * К письму добавляются версия приложения и телефона — это помогает разобраться с ошибкой. Ключи Ozon не добавляются никогда.
 */
@Composable
fun SupportScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    var topic by rememberSaveable { mutableStateOf(Topic.QUESTION) }
    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val address = BuildConfig.SUPPORT_EMAIL

    Column(Modifier.fillMaxSize()) {
        Header("Поддержка", back)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint("Опишите вопрос или проблему — письмо уйдёт в поддержку, ответим на вашу почту.")
            ChoiceChips(Topic.entries.map { it to it.title }, topic, { topic = it })
            OutlinedTextField(
                message, { message = it.take(4000) },
                label = { Text("Сообщение") },
                placeholder = { Text(if (topic == Topic.BUG) "За какой период и где цифры не сходятся? Сколько показывает приложение и сколько должно быть?" else "Ваш вопрос") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                contact, { contact = it.trim().take(120) },
                label = { Text("Почта или Telegram для ответа") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val body = buildString {
                        append(message.trim()).append("\n\n")
                        if (contact.isNotBlank()) append("Контакт для ответа: ").append(contact).append('\n')
                        append("—\n")
                        append("Маржа ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                        append("Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                        append(if (vm.demo) "Демо-кабинет" else "Свой кабинет").append('\n')
                        vm.coverage?.let { append("Загружено дней в периоде: ${it.loaded} из ${it.total}\n") }
                    }
                    val subject = "Маржа: ${topic.title}"
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    status = try {
                        context.startActivity(Intent.createChooser(intent, "Отправить через"))
                        null
                    } catch (e: ActivityNotFoundException) {
                        "На телефоне нет почтового приложения. Напишите нам на $address."
                    }
                },
                enabled = message.isNotBlank() && address.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Отправить") }
            if (address.isEmpty()) Text("Адрес поддержки ещё не настроен в этой сборке.", color = lossColor, style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, color = lossColor, style = MaterialTheme.typography.bodySmall) }
            Hint(
                "Откроется ваше почтовое приложение с готовым письмом — останется нажать «Отправить». " +
                    "К письму добавятся версия приложения и модель телефона. Ключи Ozon и цифры вашего кабинета не отправляются."
            )
            if (address.isNotEmpty()) Hint("Адрес поддержки: $address")
        }
    }
}

/**
 * Проверка данных: все начисления Ozon за период по видам и по дням. Нужна, чтобы сверить приложение
 * с личным кабинетом или своей таблицей и найти, где именно расходятся цифры.
 */
@Composable
fun DataCheckScreen(vm: AppViewModel, back: () -> Unit) {
    val report = vm.report
    val lines = vm.typeLines
    Column(Modifier.fillMaxSize()) {
        Header("Проверка данных", back)
        PeriodChips(vm)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SyncBanner(vm) }
            if (vm.demo) item { Panel { Hint("В демо-кабинете расшифровки по видам нет — она появится после подключения своего кабинета.") } }

            if (lines.isNotEmpty()) {
                val byClass = lines.groupBy { it.cls }.mapValues { (_, l) -> l.sumOf { it.amount } }
                val counted = lines.filter { it.cls != FeeClass.TRANSFER }.sumOf { it.amount }
                item {
                    Panel {
                        Text("Итого начислено Ozon", fontWeight = FontWeight.SemiBold)
                        Hint("Должно совпасть с «Финансы → Начисления» за тот же период.")
                        Spacer(Modifier.height(6.dp))
                        for (c in FeeClass.entries) byClass[c]?.let { v -> if (kotlin.math.abs(v) >= 0.5) Line(c.title, rub(v)) }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Line("Входит в прибыль", rub(counted), bold = true, sub = "всё, кроме выплат на счёт")
                    }
                }
                item { SectionTitle("По видам начислений") }
                item {
                    Panel {
                        lines.forEach { l ->
                            Line(l.name, rub(l.amount), valueColor = if (l.cls == FeeClass.TRANSFER) MaterialTheme.colorScheme.onSurfaceVariant else signColor(l.amount), sub = "${l.cls.title} · ${qty(l.count)} шт")
                        }
                    }
                }
            } else if (!vm.demo) item {
                Panel { Hint("За период нет расшифровки. Она появляется для дней, загруженных после обновления приложения: нажмите «Обновить» в настройках.") }
            }

            if (report != null) {
                item { SectionTitle("По дням") }
                item {
                    Panel {
                        Hint("Сверяйте с таблицей день за днём: так сразу видно, за какой день расходятся цифры.")
                        Spacer(Modifier.height(4.dp))
                        report.daily.reversed().forEach { d ->
                            val loaded = vm.demo || d.date in vm.loadedDays
                            Line(
                                dayMonth(d.date) + if (loaded) "" else " · не загружен",
                                rub(d.profit),
                                valueColor = if (loaded) signColor(d.profit) else warnColor,
                                sub = "выкуплено ${rub(d.revenue)} · удержано ${rub(-d.ozon)} · ${qty(d.soldQty)} шт",
                            )
                        }
                    }
                }
            }
        }
    }
}
