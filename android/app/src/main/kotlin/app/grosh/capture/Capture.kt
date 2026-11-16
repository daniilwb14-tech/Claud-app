package app.grosh.capture

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.grosh.core.BankMessageParser
import app.grosh.core.EntrySource
import app.grosh.core.ParsedTxn
import app.grosh.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Приложения, уведомления которых разбираются как банковские операции. */
object CaptureSources {
    /** Пакет → понятное название. */
    val banks: Map<String, String> = mapOf(
        "ru.sberbankmobile" to "Сбер",
        "com.idamob.tinkoff.android" to "Т-Банк",
        "ru.tinkoff.investing" to "Т-Банк",
        "ru.alfabank.mobile.android" to "Альфа-Банк",
        "ru.vtb24.mobilebanking.android" to "ВТБ",
        "ru.raiffeisennews" to "Райффайзен",
        "ru.gazprombank.android.mobilebank.app" to "Газпромбанк",
        "ru.rosbank.android" to "Росбанк",
        "com.openbank" to "Открытие",
        "ru.ozon.fintech.finance" to "Ozon Банк",
        "com.yandex.bank" to "Яндекс Банк",
        "ru.yoo.money" to "ЮMoney",
        "ru.sovcomcard.halva.v1" to "Совкомбанк",
        "ru.mtsbank.dbo" to "МТС Банк",
        "ru.psbank.online" to "ПСБ",
        "ru.pochta.bank" to "Почта Банк",
        "ru.rshb.dbo" to "Россельхозбанк",
        "ru.uralsib.mb" to "Уралсиб",
    )

    const val MIR_PAY = "ru.nspk.mirpay"

    /** Приложения для SMS: их уведомления — запасной путь, если доступ к SMS не выдан. */
    val messengers = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.miui.mms",
        "com.huawei.message",
        "com.hihonor.mms",
    )

    fun sourceFor(pkg: String): EntrySource? = when {
        pkg == MIR_PAY -> EntrySource.MIR_PAY
        pkg in banks -> EntrySource.PUSH
        pkg in messengers -> EntrySource.SMS
        else -> null
    }

    fun isListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Слушает уведомления банков и Mir Pay и записывает операции без участия пользователя. */
class BankNotificationListener : NotificationListenerService() {

    // Банки часто обновляют одно и то же уведомление — помним последние, чтобы не записать дважды.
    private val seen = LinkedHashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val source = CaptureSources.sourceFor(sbn.packageName) ?: return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (body.isEmpty() && title.isEmpty()) return

        val fingerprint = "${sbn.packageName}|$title|$body"
        synchronized(seen) {
            if (!seen.add(fingerprint)) return
            if (seen.size > 200) seen.remove(seen.first())
        }

        val txn = parseNotification(title, body, source, sbn.postTime) ?: return
        val context = applicationContext
        captureScope.launch {
            Repo.save(context, listOf(txn), source, Repo.Dedupe.NEAR_TIME)
        }
    }

    companion object {
        /**
         * У одних банков сумма в заголовке («Покупка 349 ₽»), у других — в тексте, поэтому пробуем оба варианта.
         * Реклама и коды отсекаются: нужна сумма и слово-операция (для Mir Pay достаточно суммы).
         */
        fun parseNotification(title: String, body: String, source: EntrySource, postTime: Long): ParsedTxn? {
            val combined = if (title.isEmpty()) body else "$title. $body"
            if (source != EntrySource.MIR_PAY && !BankMessageParser.looksLikeOperation(combined)) return null
            val parsed = BankMessageParser.parse(body, postTime)
                ?: BankMessageParser.parse(combined, postTime)
                ?: return null
            // Время берём из уведомления: оно точнее, чем дата в тексте.
            return parsed.copy(dateMillis = postTime)
        }
    }
}

/** Входящие SMS: разбираем те, что похожи на банковские операции. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Длинное SMS приходит частями — склеиваем по отправителю.
        val bySender = messages.groupBy { it.originatingAddress.orEmpty() }
        val txns = bySender.values.mapNotNull { parts ->
            val text = parts.joinToString("") { it.messageBody.orEmpty() }
            if (!BankMessageParser.looksLikeOperation(text)) return@mapNotNull null
            val time = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
            BankMessageParser.parse(text, time)?.copy(dateMillis = time)
        }
        if (txns.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        captureScope.launch {
            try {
                Repo.save(appContext, txns, EntrySource.SMS, Repo.Dedupe.NEAR_TIME)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Разовый импорт уже полученных SMS: история операций появляется сразу после установки. */
object SmsHistory {
    fun read(resolver: ContentResolver, sinceMillis: Long): List<ParsedTxn> {
        val out = mutableListOf<ParsedTxn>()
        val columns = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, columns, "${Telephony.Sms.DATE} >= ?", arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIndex) ?: continue
                val date = cursor.getLong(dateIndex)
                if (!BankMessageParser.looksLikeOperation(body)) continue
                BankMessageParser.parse(body, date)?.let { out += it.copy(dateMillis = date) }
            }
        }
        return out
    }
}
