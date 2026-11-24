package app.marzha.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.marzha.AppViewModel
import app.marzha.core.Expense
import app.marzha.core.ExpenseKind
import app.marzha.core.TaxBase
import app.marzha.core.expensesFor
import app.marzha.core.reportCsv

@Composable
fun MoreScreen(vm: AppViewModel, openAds: () -> Unit, openSupport: () -> Unit, openCheck: () -> Unit) {
    val context = LocalContext.current
    var editExpense by remember { mutableStateOf<Expense?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    var showPerf by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var explainCsv by remember { mutableStateOf(false) }
    var addProfile by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf<app.marzha.data.Profile?>(null) }
    val s = vm.settings

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Настройки", Modifier.padding(start = 4.dp, top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Panel(onClick = openSupport) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Написать в поддержку", fontWeight = FontWeight.SemiBold)
                        Hint("Вопрос, ошибка в цифрах или идея — ответим на почту.")
                    }
                    Text("›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // ── Кабинеты ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Кабинеты", Modifier.weight(1f))
                IconButton(onClick = { addProfile = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить кабинет") }
            }
        }
        item {
            Panel {
                vm.profiles.forEach { p ->
                    val current = p.id == vm.profile.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.switchProfile(p.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current, onClick = { vm.switchProfile(p.id) })
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                            Hint(if (current) "открыт сейчас" else "нажмите, чтобы переключиться")
                        }
                        TextButton(onClick = { editProfile = p }) { Text("Изменить") }
                    }
                }
                TextButton(onClick = { addProfile = true }) { Text("Добавить кабинет") }
                Hint("У каждого кабинета свои ключи Ozon, себестоимость, расходы, налог и данные. Переключаться можно и на главной — нажмите на название магазина.")
            }
        }

        // ── Оформление ──
        item { SectionTitle("Цветовая гамма") }
        items(AppTheme.entries.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in pair) {
                    val selected = vm.theme == t.name
                    Surface(
                        onClick = { vm.chooseTheme(t.name) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in t.swatch) Box(Modifier.size(22.dp).clip(CircleShape).background(c))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, fontWeight = FontWeight.Medium)
                            Hint(if (selected) "выбрана" else "нажмите, чтобы включить")
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Hint("Тёмный вариант каждой гаммы включается вместе с тёмной темой телефона.", Modifier.padding(start = 4.dp)) }

        // ── Налог и ставки ──
        item { SectionTitle("Налог и ставки") }
        item {
            Panel {
                var tax by remember(s.taxPct) { mutableStateOf(fieldNum(s.taxPct)) }
                var mgr by remember(s.managerPct) { mutableStateOf(fieldNum(s.managerPct)) }
                var days by remember(s.stockTargetDays) { mutableStateOf(s.stockTargetDays.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Налог", tax, { tax = it; vm.saveSettings(vm.settings.copy(taxPct = parseNum(it).coerceIn(0.0, 100.0))) }, Modifier.weight(1f), "%")
                    NumberField("Менеджер", mgr, { mgr = it; vm.saveSettings(vm.settings.copy(managerPct = parseNum(it).coerceIn(0.0, 100.0))) }, Modifier.weight(1f), "%")
                }
                Hint("Налог — общая ставка, например УСН 1% + НДС 5% = 6. Менеджер — процент от выкупов.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Text("С какой суммы считать налог", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                ChoiceChips(
                    listOf(TaxBase.ALL to "Со всех выкупов", TaxBase.CUSTOMER_PAID to "Только с оплаты клиента"),
                    s.taxBase, { vm.saveSettings(vm.settings.copy(taxBase = it)) },
                )
                Hint("Второй вариант не считает налог с баллов и скидок за счёт Ozon. Уточните у бухгалтера, какой подходит вам.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                NumberField("Запас товара, на сколько дней", days, {
                    days = it.filter(Char::isDigit).take(3)
                    days.toIntOrNull()?.let { d -> if (d in 1..365) vm.saveSettings(vm.settings.copy(stockTargetDays = d)) }
                }, Modifier.fillMaxWidth(), "дн.")
                Hint("Нужно для расчёта, сколько докупить.", Modifier.padding(top = 6.dp))
            }
        }

        // ── Свои расходы ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Свои расходы", Modifier.weight(1f))
                IconButton(onClick = { editExpense = Expense(0, "", 0.0, ExpenseKind.MONTHLY) }) { Icon(Icons.Filled.Add, contentDescription = "Добавить расход") }
            }
        }
        if (vm.expenses.isEmpty()) item {
            Panel(onClick = { editExpense = Expense(0, "", 0.0, ExpenseKind.MONTHLY) }) {
                Text("Добавить расход", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Hint("Ведение кабинета, зарплаты, аренда склада, фотосессии — всё, что не списывает Ozon. Расходы делятся между товарами по доле выкупов.")
            }
        }
        items(vm.expenses, key = { it.id }) { e ->
            Panel(onClick = { editExpense = e }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.Medium)
                        Hint(
                            when (e.kind) {
                                ExpenseKind.MONTHLY -> "каждый месяц"
                                ExpenseKind.WEEKLY -> "каждую неделю"
                                ExpenseKind.ONCE -> "разово, " + dayMonth(e.date)
                            }
                        )
                    }
                    Text(rub(e.amount), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        vm.report?.let { r ->
            if (vm.expenses.isNotEmpty()) item {
                Hint("За выбранный период (${dayMonth(r.from)}–${dayMonth(r.to)}): ${rub(expensesFor(vm.expenses, r.from, r.to))}", Modifier.padding(start = 4.dp))
            }
        }

        // ── Ozon ──
        item { SectionTitle("Подключение Ozon") }
        item {
            Panel {
                if (vm.demo) {
                    Text("Демо-кабинет", fontWeight = FontWeight.SemiBold)
                    Hint("Данные выдуманы. Подключите свой кабинет Ozon, чтобы увидеть настоящие цифры.")
                    Button(onClick = { showKeys = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Подключить Ozon") }
                } else {
                    Line("Client-Id", vm.clientId)
                    Line("API-ключ", "сохранён на устройстве", sub = "хранится в зашифрованном виде и никуда не передаётся, кроме Ozon")
                    TextButton(onClick = { showKeys = true }) { Text("Заменить ключ") }
                }
            }
        }
        if (!vm.demo) item {
            Panel(onClick = { showPerf = true }) {
                Text("Статистика рекламных кампаний", fontWeight = FontWeight.SemiBold)
                Hint(
                    if (vm.hasPerf) "Performance API подключён. Нажмите, чтобы заменить ключ."
                    else "Необязательно. Расходы на рекламу уже учтены в прибыли — они берутся из начислений. Ключ Performance API добавит показы, клики и ДРР по каждой кампании."
                )
            }
        }
        item {
            Panel(onClick = openAds) { Text("Реклама по товарам и кампаниям", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary) }
        }

        // ── Данные ──
        item { SectionTitle("Данные") }
        item { SyncBanner(vm) }
        item {
            Panel {
                vm.historyFrom?.let { Line("История загружается с", dayMonth(it) + "." + it.year) }
                vm.coverage?.let { c -> Line("Загружено дней в выбранном периоде", "${c.loaded} из ${c.total}") }
                vm.lastResult?.let { Hint(it, Modifier.padding(vertical = 4.dp)) }
                if (!vm.demo) {
                    TextButton(onClick = { vm.sync() }, enabled = !vm.syncing) { Text(if (vm.syncing) "Идёт загрузка…" else "Обновить и догрузить пропущенные дни") }
                    TextButton(onClick = { vm.sync(vm.today().minusDays(92)) }, enabled = !vm.syncing) { Text("Загрузить историю за 3 месяца") }
                    Hint("Три месяца — это около 90 запросов к Ozon по дню на каждый, обычно 5–15 минут. Ход загрузки виден здесь и на сводке.")
                }
                TextButton(onClick = openCheck, enabled = vm.report != null) { Text("Проверка данных: начисления Ozon по видам и дням") }
                TextButton(onClick = { explainCsv = true }, enabled = vm.report != null) { Text("Выгрузить отчёт по товарам (CSV)") }
                TextButton(onClick = { confirmLogout = true }, enabled = !vm.syncing) {
                    Text(
                        when {
                            vm.profiles.size > 1 -> "Удалить кабинет «${vm.profile.name}» с телефона"
                            vm.demo -> "Выйти из демо"
                            else -> "Выйти и стереть данные с телефона"
                        },
                        color = lossColor,
                    )
                }
            }
        }

        item { SectionTitle("Как всё считается") }
        items(formulas) { (title, text) ->
            Panel {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    editExpense?.let { e ->
        ExpenseDialog(e, today = vm.today().toString(), onDismiss = { editExpense = null }, onSave = { vm.saveExpense(it); editExpense = null }, onDelete = { vm.deleteExpense(e.id); editExpense = null })
    }
    if (showKeys) KeysDialog(vm, onDismiss = { showKeys = false })
    if (addProfile) AddProfileDialog(vm, onDismiss = { addProfile = false })
    editProfile?.let { p -> EditProfileDialog(vm, p, onDismiss = { editProfile = null }) }
    if (explainCsv) vm.report?.let { r ->
        AlertDialog(
            onDismissRequest = { explainCsv = false },
            title = { Text("Отчёт по товарам") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Таблица юнит-экономики за ${dayMonth(r.from)}–${dayMonth(r.to)} — тот же расчёт, что на экране «Товары».")
                    Hint(
                        "Строка — товар, последняя строка — итог. Столбцы: артикул, название, SKU, заказано, выкуплено, возвраты, " +
                            "выкуплено в рублях, оплатили клиенты, комиссия, логистика, обратная логистика, реклама, хранение, эквайринг, " +
                            "кросс-докинг, прочие удержания, закупка, маркировка, упаковка, прочая себестоимость, налог, менеджер, " +
                            "свои расходы, чистая прибыль, маржа и прибыль на 1 шт."
                    )
                    Hint("Период — тот, что выбран сейчас на сводке. Чтобы выгрузить другой, смените период и вернитесь сюда.")
                    Text("Как открыть", fontWeight = FontWeight.SemiBold)
                    Hint(
                        "Нажмите «Отправить» и выберите, куда сохранить файл: себе в Telegram, на почту, в Google Диск или в «Файлы». " +
                            "Дальше откройте его в Excel или Google Таблицах (Файл → Импорт). Разделитель — точка с запятой, кириллица отображается правильно."
                    )
                }
            },
            confirmButton = {
                Button(onClick = { explainCsv = false; shareCsv(context, reportCsv(r), "marzha_${r.from}_${r.to}.csv") }) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { explainCsv = false }) { Text("Отмена") } },
        )
    }
    if (showPerf) PerfDialog(onDismiss = { showPerf = false }, onSave = { id, secret -> vm.savePerformanceKeys(id, secret); showPerf = false })
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(if (vm.demo) "Выйти из демо?" else "Стереть данные?") },
        text = {
            Text(
                when {
                    vm.profiles.size > 1 -> "Кабинет «${vm.profile.name}» удалится с телефона вместе с ключом, данными, себестоимостью и расходами. Остальные кабинеты останутся. В самом Ozon ничего не изменится."
                    vm.demo -> "Демо-данные будут удалены."
                    else -> "С телефона удалятся ключ Ozon, загруженные данные, себестоимость и расходы. В кабинете Ozon ничего не изменится."
                }
            )
        },
        confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Выйти", color = lossColor) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
    )
}

/** Формулы всех показателей — чтобы было видно, что из чего получается. */
private val formulas: List<Pair<String, String>> = listOf(
    "Откуда данные" to
        "Выкупы, возвраты и все удержания берутся из начислений Ozon (Финансы → Начисления) по дате начисления, " +
        "поэтому суммы сходятся с личным кабинетом. Заказы — из списка отправлений FBO и FBS по дате оформления, " +
        "время московское. Остатки — из карточек товаров и аналитики остатков. " +
        "Себестоимость, налог, процент менеджера и свои расходы вводите вы.",
    "Выкуплено" to
        "Выкуплено, ₽ = сумма продаж − сумма возвратов за период (с баллами Ozon).\n" +
        "Выкуплено, шт = проданные штуки − возвращённые штуки.\n" +
        "Средний чек = выкуплено, ₽ ÷ выкуплено, шт.",
    "Оплатили клиенты и баллы Ozon" to
        "Оплатили клиенты = выкуплено, ₽ − баллы за скидки − соинвестирование Ozon.\n" +
        "Баллы Ozon = выкуплено, ₽ − оплатили клиенты.\n" +
        "Это та часть цены, которую покупатель не платил: её доплатил вам Ozon.",
    "Заказано и отмены" to
        "Заказано, шт и ₽ — все отправления, оформленные за период, включая отменённые; сумма = цена × количество.\n" +
        "Отмены — штуки в отправлениях со статусом «отменено».",
    "Процент выкупа" to
        "Берутся заказы, оформленные в выбранном периоде, и из них — те, судьба которых уже известна: " +
        "доставлены покупателю или отменены.\n" +
        "Процент выкупа = доставлено ÷ (доставлено + отменено).\n" +
        "Заказы, которые ещё едут, не учитываются, пока не станет ясно, выкупят их или нет. Поэтому за «Сегодня» " +
        "и «Вчера» процента обычно нет, а самая точная цифра — за 30 дней или прошлый месяц.\n" +
        "Это не «выкуплено за период ÷ заказано за период»: выкупы недели во многом приходят из заказов прошлой недели, " +
        "и такое деление может дать больше 100%.",
    "Удержания Ozon" to
        "Каждое начисление Ozon относится к статье по его названию в справочнике Ozon:\n" +
        "• комиссия — комиссия за продажу с учётом скидок;\n" +
        "• логистика — доставка до покупателя, магистраль, последняя миля, обработка отправления;\n" +
        "• обратная логистика — возвраты, отмены, невыкуп;\n" +
        "• реклама и продвижение — оплата за клик, оплата за заказ, трафареты, бренд-полка, отзывы за баллы;\n" +
        "• хранение, эквайринг, кросс-докинг — отдельными статьями;\n" +
        "• прочие начисления — всё остальное.\n" +
        "Каждое начисление сверяется со своим итогом: если части не сошлись, остаток уходит в «прочие». " +
        "Так сумма статей всегда равна тому, что начислил Ozon.\n" +
        "Выплаты на ваш расчётный счёт — это не расход, в прибыль они не входят. А вот плата за досрочную выплату — " +
        "расход, она попадает в «прочие начисления». " +
        "Все виды начислений с суммами можно посмотреть в «Настройки → Данные → Проверка данных».",
    "К выплате от Ozon" to
        "К выплате = выкуплено, ₽ + все удержания Ozon (они отрицательные).\n" +
        "«Удержано, %» = удержания Ozon ÷ выкуплено, ₽.\n" +
        "Это начислено, а не переведено на счёт: сами выплаты Ozon делает по своему графику.",
    "Реклама и ДРР" to
        "Реклама — сумма рекламных начислений Ozon за период. Реклама, привязанная к товару (оплата за заказ), " +
        "ложится на этот товар; реклама кабинета без товара делится между товарами по доле выкупов.\n" +
        "ДРР = реклама ÷ заказано, ₽. Если заказы за период не загружены — реклама ÷ выкуплено, ₽.\n" +
        "В списке кампаний: ДРР кампании = расход ÷ сумма заказов с рекламы, CTR = клики ÷ показы, " +
        "цена заказа = расход ÷ заказы.",
    "Себестоимость" to
        "Себестоимость 1 шт = закупка + маркировка (КИЗ) + упаковка + прочее + ваши собственные статьи.\n" +
        "Себестоимость за период = себестоимость 1 шт × выкуплено, шт (за вычетом возвратов).\n" +
        "Если себестоимость не указана, она считается нулевой и прибыль по товару завышена — " +
        "такие товары помечены.",
    "Налог" to
        "Налог = ставка × налоговая база.\n" +
        "База «со всех выкупов» = выкуплено, ₽. База «только с оплаты клиента» = оплатили клиенты, ₽ " +
        "(без баллов и скидок за счёт Ozon). Отрицательная база считается нулём.",
    "Менеджер и свои расходы" to
        "Менеджер = процент × выкуплено, ₽.\n" +
        "Свои расходы: ежемесячный делится на число дней месяца, еженедельный — на 7, и берётся столько дней, " +
        "сколько в периоде; разовый учитывается целиком в день своей даты.\n" +
        "Свои расходы и начисления Ozon без товара (хранение, кросс-докинг, реклама кабинета) делятся между " +
        "товарами по доле в выкупах: доля товара = его выкуп, ₽ ÷ общий выкуп, ₽.",
    "Чистая прибыль" to
        "Чистая прибыль = выкуплено, ₽ − удержания Ozon − себестоимость − налог − менеджер − свои расходы.\n" +
        "Маржа = чистая прибыль ÷ выкуплено, ₽.\n" +
        "Прибыль на 1 шт = чистая прибыль ÷ выкуплено, шт.\n" +
        "На вложенный рубль = чистая прибыль ÷ себестоимость проданного.",
    "Остатки" to
        "Остаток = склады Ozon + свой склад (FBS) + в пути на склад (поставки и заявки) + возвраты от покупателей, " +
        "которые едут обратно на склад и снова пойдут в продажу.\n" +
        "Вложено в товар = остаток × себестоимость 1 шт.\n" +
        "Продаж в день = выкуплено, шт за последние 30 дней ÷ число загруженных дней из этих 30.\n" +
        "Хватит на = остаток ÷ продаж в день.\n" +
        "Принесёт чистой прибыли = остаток × прибыль на 1 шт за 30 дней.\n" +
        "Вернётся денег = вложено в товар + принесёт чистой прибыли: сколько окажется у вас после продажи всего остатка.\n" +
        "Выручка, если продать всё = остаток × средний чек за 30 дней (с баллами Ozon, до удержаний).\n" +
        "Докупить = продаж в день × целевой запас в днях − остаток (если больше нуля).\n" +
        "Цвета: красный — хватит меньше чем на 7 дней, жёлтый — меньше чем на 14, зелёный — запас есть, " +
        "синий — больше 90 дней, серый — продаж нет.",
    "Поставки по кластерам" to
        "Спрос в кластере — среднесуточные продажи товара в этом кластере за 28 дней по аналитике остатков Ozon.\n" +
        "Хватит на = лежит на складах кластера ÷ продаж в день. То, что едет, сюда не входит: пока поставка в пути, " +
        "продавать можно только то, что уже лежит.\n" +
        "Поставить = продаж в день × целевой запас в днях − (лежит + едет), если больше нуля.\n" +
        "Срочность: закончился — товара нет, а спрос есть; красный — хватит меньше чем на 7 дней; жёлтый — меньше чем на 14; " +
        "серый «лишний» — продаж почти нет или запаса больше чем на 120 дней: туда лучше не везти.\n" +
        "Сколько денег в день теряется без товара = продаж в день × средний чек за 30 дней; прибыль — × прибыль на 1 шт.\n" +
        "Ozon показывает кластеры, где товар лежит или куда едет. Если в каком-то регионе товара не было вовсе, его спрос сюда не попадёт.",
    "Периоды" to
        "«7 дней» и «30 дней» — полные дни до вчерашнего включительно. «Этот месяц» — с 1-го числа по сегодня. " +
        "«Свой период» — любые даты из календаря. Если какие-то дни периода ещё не загружены, сверху появится предупреждение. " +
        "Сегодняшние начисления Ozon неполные и дополняются в течение нескольких дней, поэтому три последних дня " +
        "при каждом обновлении загружаются заново.",
)

/** Сохраняет отчёт файлом и открывает «Поделиться»: так Excel и Google Таблицы откроют его как таблицу. */
private fun shareCsv(context: Context, csv: String, fileName: String) {
    val dir = java.io.File(context.cacheDir, "reports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = java.io.File(dir, fileName)
    // Метка BOM нужна Excel, чтобы он понял кодировку UTF-8 и не исказил кириллицу.
    file.writeText("\uFEFF" + csv, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "Юнит-экономика Ozon")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Куда отправить отчёт"))
}

@Composable
private fun ExpenseDialog(initial: Expense, today: String, onDismiss: () -> Unit, onSave: (Expense) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var amount by remember { mutableStateOf(fieldNum(initial.amount)) }
    var kind by remember { mutableStateOf(initial.kind) }
    var date by remember { mutableStateOf(initial.date.ifEmpty { today }) }
    val dateOk = kind != ExpenseKind.ONCE || runCatching { java.time.LocalDate.parse(date) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый расход" else "Расход") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField("Сумма", amount, { amount = it }, Modifier.fillMaxWidth())
                ChoiceChips(listOf(ExpenseKind.MONTHLY to "В месяц", ExpenseKind.WEEKLY to "В неделю", ExpenseKind.ONCE to "Разово"), kind, { kind = it })
                if (kind == ExpenseKind.ONCE) {
                    OutlinedTextField(date, { date = it.take(10) }, label = { Text("Дата (ГГГГ-ММ-ДД)") }, singleLine = true, isError = !dateOk, modifier = Modifier.fillMaxWidth())
                }
                if (initial.id != 0L) TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = lossColor)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить", color = lossColor)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(initial.copy(name = name.trim(), amount = parseNum(amount), kind = kind, date = if (kind == ExpenseKind.ONCE) date else "")) },
                enabled = name.isNotBlank() && parseNum(amount) > 0 && dateOk,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Форма ключа Seller API: и на первом экране, и в настройках. */
@Composable
fun KeysForm(vm: AppViewModel, onConnected: () -> Unit, modifier: Modifier = Modifier, askName: Boolean = false) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (askName) OutlinedTextField(
            name, { name = it.take(40) }, label = { Text("Название магазина") },
            placeholder = { Text(vm.profile.name) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(id, { id = it.filter(Char::isDigit).take(12) }, label = { Text("Client-Id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it.trim().take(80) }, label = { Text("API-ключ") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        err?.let { Text(it, color = lossColor, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                busy = true
                err = null
                vm.connect(id, key, name) { e ->
                    busy = false
                    err = e
                    if (e == null) onConnected()
                }
            },
            enabled = !busy && id.isNotEmpty() && key.length >= 8,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Подключить Ozon")
        }
        Hint("Где взять: Ozon Seller → Настройки → Seller API → Сгенерировать ключ. Достаточно роли «Admin read only» — приложение только читает данные и ничего не меняет в кабинете.")
        Hint("Ключ хранится только на этом телефоне в зашифрованном виде и отправляется только в Ozon.")
    }
}

/** Новый кабинет: только название, ключ вводится на следующем экране. */
@Composable
fun AddProfileDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый кабинет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Название магазина") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint("Дальше откроется экран подключения: для нового кабинета нужен свой ключ Seller API из его личного кабинета Ozon.")
            }
        },
        confirmButton = { Button(onClick = { vm.addProfile(name); onDismiss() }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun EditProfileDialog(vm: AppViewModel, p: app.marzha.data.Profile, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кабинет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Название магазина") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vm.profiles.size > 1) {
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = lossColor)
                        Spacer(Modifier.width(6.dp))
                        Text("Удалить кабинет", color = lossColor)
                    } else {
                        Text("Удалить «${p.name}» с телефона вместе с ключом, данными, себестоимостью и расходами? В Ozon ничего не изменится.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.deleteProfile(p.id); onDismiss() }) { Text("Да, удалить", color = lossColor) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.renameProfile(p.id, name); onDismiss() }, enabled = name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun KeysDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ Ozon Seller API") },
        text = { KeysForm(vm, onConnected = onDismiss, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun PerfDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Performance API") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it.trim().take(120) }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it.trim().take(200) }, label = { Text("Client Secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Hint("Где взять: Ozon Seller → Настройки → API-ключи → Performance API → создать сервисный аккаунт.")
            }
        },
        confirmButton = { Button(onClick = { onSave(id, secret) }, enabled = id.isNotEmpty() && secret.isNotEmpty()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ScreenHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Реклама: расходы по товарам (из начислений) и, если подключён Performance API, по кампаниям. */
@Composable
fun AdsScreen(vm: AppViewModel, back: () -> Unit, openProduct: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Реклама", back)
        PeriodChips(vm)
        val report = vm.report
        val products = report?.lines.orEmpty().filter { it.sku != 0L && it.b.ads < -0.5 }.sortedBy { it.b.ads }
        val campaigns = vm.campaignLines
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report != null) item {
                val t = report.total
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Потрачено на рекламу", rubShort(-t.b.ads), if (t.b.revenue > 0) "${pct(-t.b.ads / t.b.revenue)} от выкупов" else "", Modifier.weight(1f))
                    Kpi("ДРР", pct(t.drr), "реклама к сумме заказов", Modifier.weight(1f))
                }
            }
            if (products.isNotEmpty()) {
                item { SectionTitle("По товарам") }
                item {
                    Panel {
                        products.forEach { l -> ProductRow(l, ProductSort.ADS, onClick = { openProduct(l.sku) }) }
                        Hint("Реклама кабинета без привязки к товару разделена между товарами по доле выкупов.", Modifier.padding(top = 6.dp))
                    }
                }
            } else item { Panel { Hint("За период рекламных начислений нет.") } }

            item { SectionTitle("По кампаниям") }
            if (campaigns.isEmpty()) item {
                Panel {
                    Hint(
                        if (vm.hasPerf || vm.demo) "За период кампаний нет."
                        else "Показы, клики и ДРР по каждой кампании появятся, если добавить ключ Performance API в настройках. " +
                            "Сами расходы на рекламу уже учтены выше — они берутся из начислений."
                    )
                }
            }
            items(campaigns, key = { it.id }) { c ->
                Panel {
                    Text(c.title.ifEmpty { "Кампания ${c.id}" }, fontWeight = FontWeight.SemiBold)
                    Hint("ID ${c.id}")
                    Spacer(Modifier.height(6.dp))
                    Line("Расход", rub(c.spent), bold = true)
                    Line("Показы → клики", "${qty(c.views.toInt())} → ${qty(c.clicks.toInt())}", sub = "CTR ${pct(c.ctr)}")
                    if (c.orders > 0 || c.ordersSum > 0) {
                        Line("Заказы", "${qty(c.orders.toInt())} шт · ${rub(c.ordersSum)}", sub = "заказ стоит ${rubUnit(c.cpo)}")
                        Line("ДРР", pct(c.drr), valueColor = if (c.drr > 0.15) lossColor else profitColor)
                    } else {
                        // Кампании с оплатой за заказ: Ozon отдаёт по ним только расход, без заказов.
                        val all = report?.total?.orderedSum ?: 0.0
                        Line("Заказы", "Ozon не передаёт", sub = "у кампаний с оплатой за заказ в статистике только расход")
                        if (all > 0) Line("ДРР от всех заказов", pct(c.spent / all), sub = "расход кампании ÷ все заказы за период, ${rubShort(all)}")
                    }
                }
            }
        }
    }
}

/** Начисления к выплате: из чего складывается сумма и как она набиралась по дням. */
@Composable
fun PayoutScreen(vm: AppViewModel, back: () -> Unit, openCheck: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("К выплате от Ozon", back)
        PeriodChips(vm)
        val report = vm.report
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report == null) return@LazyColumn
            val t = report.total
            val b = t.b
            item {
                Panel {
                    Text("Из чего складывается", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Line("Выкуплено", rub(b.revenue), bold = true, sub = "оплатили клиенты ${rub(t.customerPaid)} · баллы Ozon ${rub(t.points)}")
                    for ((label, v) in listOf(
                        "Комиссия Ozon" to b.commission, "Логистика" to b.logistics, "Обратная логистика" to b.returnLogistics,
                        "Реклама и продвижение" to b.ads, "Хранение" to b.storage, "Эквайринг" to b.acquiring,
                        "Кросс-докинг" to b.crossdock, "Прочие начисления" to b.otherOzon,
                    )) {
                        if (kotlin.math.abs(v) >= 0.5) Line(label, rub(v), sub = if (b.revenue > 0) pct(-v / b.revenue) + " от выкупов" else "")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Line("К выплате", rub(b.payout), valueColor = signColor(b.payout), bold = true, sub = if (b.revenue > 0) "остаётся ${pct(b.payout / b.revenue)} от выкупов" else "")
                    Hint("Это начислено Ozon за период, а не переведено на счёт: выплаты Ozon делает по своему графику.", Modifier.padding(top = 6.dp))
                    TextButton(onClick = openCheck) { Text("Все начисления по видам и дням") }
                }
            }
            val days = report.daily.filter { it.revenue != 0.0 || it.ozon != 0.0 }.reversed()
            if (days.isNotEmpty()) {
                item { SectionTitle("По дням") }
                item {
                    Panel {
                        days.forEach { d ->
                            Line(dayMonth(d.date), rub(d.payout), valueColor = signColor(d.payout), sub = "выкуплено ${rub(d.revenue)} · удержано ${rub(-d.ozon)}")
                        }
                    }
                }
            }
        }
    }
}
