package app.grosh

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.grosh.capture.SmsHistory
import app.grosh.core.CsvStatementParser
import app.grosh.core.EntrySource
import app.grosh.core.ParsedTxn
import app.grosh.core.PdfStatementParser
import app.grosh.core.StatementResult
import app.grosh.data.AppDb
import app.grosh.data.Entry
import app.grosh.data.Repo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Результат чтения файла выписки, который ждёт подтверждения импорта. */
data class ImportPreview(val result: StatementResult, val source: EntrySource)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("grosh", Context.MODE_PRIVATE)

    val entries: StateFlow<List<Entry>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Бюджет на месяц и доход за час — в рублях. */
    var budget by mutableLongStateOf(prefs.getLong("budget", 60_000))
        private set
    var hourlyRate by mutableLongStateOf(prefs.getLong("hourlyRate", 0))
        private set
    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", false))
        private set

    /** Что показать пользователю: превью импорта, итог или ошибку. */
    var importPreview by mutableStateOf<ImportPreview?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    fun updateBudget(value: Long) {
        budget = value
        prefs.edit().putLong("budget", value).apply()
    }

    fun updateHourlyRate(value: Long) {
        hourlyRate = value
        prefs.edit().putLong("hourlyRate", value).apply()
    }

    fun finishOnboarding(withDemo: Boolean) {
        onboarded = true
        prefs.edit().putBoolean("onboarded", true).apply()
        if (withDemo) addDemo()
    }

    fun save(entry: Entry) = viewModelScope.launch {
        if (entry.id == 0L) dao.insert(entry) else dao.update(entry)
    }

    fun delete(entry: Entry) = viewModelScope.launch { dao.delete(entry) }

    fun deleteAll() = viewModelScope.launch { dao.deleteAll() }

    fun addDemo() = viewModelScope.launch { Repo.insertDemo(context) }

    /** Сохраняет вставленное сообщение; возвращает `false`, если такая операция уже записана. */
    fun savePasted(txn: ParsedTxn, force: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val outcome = Repo.save(context, listOf(txn), EntrySource.PASTE, if (force) Repo.Dedupe.NONE else Repo.Dedupe.BY_DAY)
        onDone(outcome.added > 0)
    }

    // MARK: Выписки

    fun readStatement(uri: Uri) = viewModelScope.launch {
        busy = true
        try {
            val preview = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                val isPdf = bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF"
                if (isPdf) {
                    PDFBoxResourceLoader.init(context)
                    val text = PDDocument.load(bytes).use { document ->
                        PDFTextStripper().apply { sortByPosition = true }.getText(document)
                    }
                    ImportPreview(PdfStatementParser.parse(text), EntrySource.PDF)
                } else {
                    ImportPreview(CsvStatementParser.parse(CsvStatementParser.decode(bytes)), EntrySource.CSV)
                }
            }
            when {
                preview == null -> message = "Не удалось открыть файл."
                preview.result.txns.isEmpty() ->
                    message = "В файле не нашлось операций. Подходят выписки PDF и CSV, где есть дата и сумма каждой операции."
                else -> importPreview = preview
            }
        } catch (e: Exception) {
            message = "Не удалось прочитать файл: ${e.message ?: "неизвестная ошибка"}"
        } finally {
            busy = false
        }
    }

    fun confirmImport() {
        val preview = importPreview ?: return
        importPreview = null
        viewModelScope.launch {
            val outcome = Repo.save(context, preview.result.txns, preview.source, Repo.Dedupe.BY_DAY)
            message = summary(outcome)
        }
    }

    // MARK: История SMS

    fun importSmsHistory(months: Int = 6) = viewModelScope.launch {
        busy = true
        try {
            val since = System.currentTimeMillis() - months * 30L * 86_400_000L
            val txns = withContext(Dispatchers.IO) { SmsHistory.read(context.contentResolver, since) }
            message = if (txns.isEmpty()) {
                "В SMS за последние $months мес. не нашлось банковских операций."
            } else {
                summary(Repo.save(context, txns, EntrySource.SMS, Repo.Dedupe.NEAR_TIME))
            }
        } catch (e: SecurityException) {
            message = "Нет доступа к SMS. Разрешите его в настройках приложения."
        } catch (e: Exception) {
            message = "Не удалось прочитать SMS: ${e.message ?: "неизвестная ошибка"}"
        } finally {
            busy = false
        }
    }

    private fun summary(outcome: Repo.Outcome): String =
        "Добавлено операций: ${outcome.added}" + if (outcome.duplicates > 0) "\nУже были записаны: ${outcome.duplicates}" else ""

    fun exportTo(uri: Uri) = viewModelScope.launch {
        try {
            val csv = Repo.exportCsv(entries.value)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            }
            message = "Файл сохранён."
        } catch (e: Exception) {
            message = "Не удалось сохранить файл."
        }
    }
}
