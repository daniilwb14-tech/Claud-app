package app.marzha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marzha.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MarzhaTheme(AppTheme.of(vm.theme)) {
                val bar = LocalPalette.current.heroFrom.toArgb()
                SideEffect {
                    window.statusBarColor = bar
                    window.navigationBarColor = bar
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (vm.connected) Main(vm) else Onboarding(vm)
                }
            }
        }
    }
}

@Composable
private fun Main(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Экран поверх вкладок: 0 — нет, −1 — реклама, −2 — выплаты, −3 — поддержка, −4 — проверка данных, −5 — кластеры, иначе SKU товара.
    var overlay by rememberSaveable { mutableLongStateOf(0L) }
    // Откуда открыли товар: чтобы «назад» из карточки возвращал в рекламу, а не на вкладку.
    var under by rememberSaveable { mutableLongStateOf(0L) }
    var productSort by rememberSaveable { mutableStateOf(ProductSort.PROFIT) }
    fun closeOverlay() {
        overlay = if (overlay > 0L) under else 0L
        under = 0L
    }
    BackHandler(enabled = overlay != 0L || tab != 0) {
        if (overlay != 0L) closeOverlay() else tab = 0
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val tabs = listOf(
                    "Сводка" to Icons.Filled.Home,
                    "Товары" to Icons.AutoMirrored.Filled.List,
                    "Остатки" to Icons.Filled.ShoppingCart,
                    "Настройки" to Icons.Filled.Settings,
                )
                tabs.forEachIndexed { i, (title, icon) ->
                    NavigationBarItem(
                        selected = tab == i && overlay == 0L,
                        onClick = { tab = i; overlay = 0L; under = 0L },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                overlay == -1L -> AdsScreen(vm, back = { closeOverlay() }, openProduct = { under = -1L; overlay = it })
                overlay == -2L -> PayoutScreen(vm, back = { closeOverlay() }, openCheck = { overlay = -4L })
                overlay == -3L -> SupportScreen(vm, back = { closeOverlay() })
                overlay == -4L -> DataCheckScreen(vm, back = { closeOverlay() })
                overlay == -5L -> ClustersScreen(vm, back = { closeOverlay() }, openProduct = { under = -5L; overlay = it })
                overlay != 0L -> ProductDetailScreen(vm, overlay, back = { closeOverlay() })
                tab == 0 -> DashboardScreen(
                    vm,
                    openProduct = { overlay = it },
                    openProducts = { productSort = it; tab = 1 },
                    openAds = { overlay = -1L },
                    openPayout = { overlay = -2L },
                    openSettings = { tab = 3 },
                )
                tab == 1 -> ProductsScreen(vm, productSort, { productSort = it }, openProduct = { overlay = it })
                tab == 2 -> StocksScreen(vm, openProduct = { overlay = it }, openClusters = { overlay = -5L })
                else -> MoreScreen(vm, openAds = { overlay = -1L }, openSupport = { overlay = -3L }, openCheck = { overlay = -4L })
            }
        }
    }
}

@Composable
private fun Onboarding(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(heroBrush())
                .padding(horizontal = 24.dp, vertical = 36.dp)
        ) {
            Column {
                Text("Маржа", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Юнит-экономика вашего магазина на Ozon — в телефоне", color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp)
                Spacer(Modifier.height(18.dp))
                for (line in listOf(
                    "Чистая прибыль по каждому товару и по дням",
                    "Все удержания Ozon и реклама — из начислений",
                    "Остатки и деньги, вложенные в товар",
                    "Себестоимость, налог и свои расходы — ваши",
                )) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("•", color = LocalPalette.current.accent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(line, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Column(Modifier.padding(24.dp)) {
            Text(
                if (vm.profiles.size > 1) "Подключите кабинет «${vm.profile.name}»" else "Подключите кабинет",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            KeysForm(vm, onConnected = {}, askName = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.startDemo() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Посмотреть на демо-данных")
            }
            // Добавили кабинет, но передумали — вернуться к уже подключённому.
            val others = vm.profiles.filter { it.id != vm.profile.id }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.deleteProfile(vm.profile.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отмена — вернуться в «${others.first().name}»")
                }
            }
        }
    }
}
