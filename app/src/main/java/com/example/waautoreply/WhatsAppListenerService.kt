package com.example.waautoreply

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class WhatsAppListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WaAutoReply"
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        private const val DUPLICATE_WINDOW_MS = 30_000L   // pesan identik dalam 30 dtk dianggap duplikat
        private const val MIN_REPLY_INTERVAL_MS = 5_000L  // jeda minimum antar balasan ke kontak yang sama
        private const val MAX_REPLY_CHARS = 600

        // Notifikasi ringkasan: "2 new messages", "3 pesan baru", dst.
        private val SUMMARY_REGEX =
            Regex("""^\d+\s+(new\s+)?(messages?|pesan)(\s+baru)?.*""", RegexOption.IGNORE_CASE)

        // Notifikasi panggilan
        private val CALL_REGEX = Regex(
            "(incoming (voice|video) call|missed (voice|video) call|ongoing call|calling…|calling\\.\\.\\.|panggilan (suara|video)|panggilan tak terjawab|memanggil)",
            RegexOption.IGNORE_CASE
        )

        // Pesan media tanpa teks, mis. "📷 Photo", "🎤 Voice message"
        private val MEDIA_REGEX = Regex(
            "^\\W*(photo|video|voice message|audio|sticker|gif|document|contact|location|foto|pesan suara|stiker|dokumen|kontak|lokasi)(\\s*\\(\\d+\\))?\\W*$",
            RegexOption.IGNORE_CASE
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()          // kontak yang sedang diproses
    private val seenAt = ConcurrentHashMap<String, Long>()                 // sig pesan -> waktu
    private val lastReplyAt = ConcurrentHashMap<String, Long>()            // kontak -> waktu balasan terakhir
    private val ownReplies = LinkedHashSet<String>()                       // balasan yang pernah kita kirim

    // ------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memproses notifikasi", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Minta sistem menyambungkan ulang listener bila terputus
        try {
            requestRebind(ComponentName(this, WhatsAppListenerService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun handle(sbn: StatusBarNotification) {
        if (!Prefs.isEnabled(this)) return
        if (sbn.packageName !in WA_PACKAGES) return

        val n = sbn.notification ?: return

        // 1) Abaikan ringkasan & panggilan
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_CALL) return

        val extras = n.extras ?: return

        // 2) Abaikan grup
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        if (!extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()) return

        // 3) Ambil pengirim & teks (pesan terakhir)
        var sender: String? = null
        var text: String? = null

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        if (last != null) {
            val person = last.person
            // Pesan yang dikirim oleh diri sendiri (person null / sama dengan user)
            if (person == null || person.name?.toString() == style.user.name?.toString()) return
            sender = person.name?.toString()
            text = last.text?.toString()
        }
        if (sender.isNullOrBlank()) sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (text.isNullOrBlank()) text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        sender = sender?.trim()
        text = text?.trim()
        if (sender.isNullOrBlank() || text.isNullOrBlank()) return
        if (sender.equals("WhatsApp", true) || sender.equals("WhatsApp Business", true)) return
        if (SUMMARY_REGEX.matches(text)) return
        if (CALL_REGEX.containsMatchIn(text)) return
        if (MEDIA_REGEX.matches(text)) return

        // 4) Anti-loop
        val now = SystemClock.elapsedRealtime()
        synchronized(ownReplies) {
            if (ownReplies.contains(text)) return   // teks ini adalah balasan kita sendiri
        }
        val contactKey = "${sbn.packageName}|$sender"
        val sig = "$contactKey|$text"
        seenAt.entries.removeIf { now - it.value > 5 * 60_000L }
        val prev = seenAt[sig]
        if (prev != null && now - prev < DUPLICATE_WINDOW_MS) return
        seenAt[sig] = now
        val lastReply = lastReplyAt[contactKey]
        if (lastReply != null && now - lastReply < MIN_REPLY_INTERVAL_MS) return
        if (!inFlight.add(contactKey)) return      // masih memproses pesan kontak ini

        // 5) Cari aksi Direct Reply
        val action = findReplyAction(n)
        if (action == null) {
            inFlight.remove(contactKey)
            Log.w(TAG, "Tidak ada aksi balas (RemoteInput) pada notifikasi $sender")
            return
        }

        val incoming = text
        val senderName = sender
        scope.launch {
            try {
                process(contactKey, senderName, incoming, action)
            } catch (e: Exception) {
                Log.e(TAG, "Error saat membalas", e)
            } finally {
                inFlight.remove(contactKey)
            }
        }
    }

    private suspend fun process(
        contactKey: String,
        sender: String,
        incoming: String,
        action: NotificationCompat.Action
    ) {
        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) {
            log(sender, incoming, "API Key belum diisi.", true)
            return
        }

        // Jeda 2–3 detik agar terlihat natural
        delay(Random.nextLong(2000, 3001))
        if (!Prefs.isEnabled(this)) return

        when (val res = GroqClient.chat(apiKey, Prefs.getPrompt(this), incoming)) {
            is GroqResult.Failure -> log(sender, incoming, res.message, true)
            is GroqResult.Success -> {
                val reply = res.text.take(MAX_REPLY_CHARS)
                synchronized(ownReplies) {
                    ownReplies.add(reply)
                    while (ownReplies.size > 20) ownReplies.remove(ownReplies.first())
                }
                if (sendReply(action, reply)) {
                    lastReplyAt[contactKey] = SystemClock.elapsedRealtime()
                    log(sender, incoming, reply, false)
                } else {
                    log(sender, incoming, "Gagal mengirim balasan (notifikasi sudah tidak aktif).", true)
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** Cari action yang punya RemoteInput, baik di action biasa maupun Wearable extender. */
    private fun findReplyAction(n: Notification): NotificationCompat.Action? {
        val candidates = mutableListOf<NotificationCompat.Action>()
        for (i in 0 until NotificationCompat.getActionCount(n)) {
            NotificationCompat.getAction(n, i)?.let { candidates.add(it) }
        }
        candidates.addAll(NotificationCompat.WearableExtender(n).actions)
        return candidates.firstOrNull { !it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }
    }

    private fun sendReply(action: NotificationCompat.Action, reply: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, reply) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent dibatalkan", e)
            false
        }
    }

    private fun log(sender: String, incoming: String, reply: String, isError: Boolean) {
        Prefs.addLog(this, LogEntry(System.currentTimeMillis(), sender, incoming, reply, isError))
    }
}
