package app.grosh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grosh.core.EntrySource
import app.grosh.core.ParsedTxn
import app.grosh.data.Entry
import app.grosh.ui.AnalyticsScreen
import app.grosh.ui.BarsIcon
import app.grosh.ui.EditEntryScreen
import app.grosh.ui.EntryRow
import app.grosh.ui.GroshTheme
import app.grosh.ui.GuideScreen
import app.grosh.ui.HistoryScreen
import app.grosh.ui.HomeScreen
import app.grosh.ui.MarketsScreen
import app.grosh.ui.MoreScreen
import app.grosh.ui.OnboardingScreen
import app.grosh.ui.PasteScreen
import app.grosh.ui.SubscriptionsScreen

/** Экран поверх вкладок. */
sealed interface Overlay {
    data class Edit(val entry: Entry? = null, val prefill: ParsedTxn? = null, val source: EntrySource = EntrySource.MANUAL) : Overlay
    data object Paste : Overlay
    data object Guide : Overlay
    data object Subscriptions : Overlay
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GroshTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val open: (Overlay) -> Unit = { overlay = it }
    val close = { overlay = null }

    if (!vm.onboarded) {
        OnboardingScreen(vm)
        return
    }

    val current = overlay
    if (current != null) {
        BackHandler(onBack = close)
        when (current) {
            is Overlay.Edit -> EditEntryScreen(vm, current, close)
            Overlay.Paste -> PasteScreen(vm, close)
            Overlay.Guide -> GuideScreen(close)
            Overlay.Subscriptions -> SubscriptionsScreen(entries, close)
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Главная") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("История") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(BarsIcon, null) }, label = { Text("Аналитика") })
                    NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.ShoppingCart, null) }, label = { Text("Маркеты") })
                    NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Filled.Menu, null) }, label = { Text("Ещё") })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> HomeScreen(vm, entries, open, goToMore = { tab = 4 })
                    1 -> HistoryScreen(entries, open)
                    2 -> AnalyticsScreen(vm, entries)
                    3 -> MarketsScreen(entries)
                    else -> MoreScreen(vm, entries, open)
                }
            }
        }
    }

    // Диалоги общие для всех экранов: превью импорта, итог, индикатор работы.
    vm.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.importPreview = null },
            title = { Text("Найдено операций: ${preview.result.txns.size}") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (preview.result.skippedRows > 0) {
                        Text("Пропущено строк: ${preview.result.skippedRows}", style = MaterialTheme.typography.bodySmall)
                    }
                    preview.result.txns.take(4).forEach { EntryRow(Entry.from(it, preview.source)) }
                    if (preview.result.txns.size > 4) {
                        Text("…и ещё ${preview.result.txns.size - 4}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport() }) { Text("Импортировать") } },
            dismissButton = { TextButton(onClick = { vm.importPreview = null }) { Text("Отмена") } },
        )
    }
    vm.message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Понятно") } },
        )
    }
    if (vm.busy) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
