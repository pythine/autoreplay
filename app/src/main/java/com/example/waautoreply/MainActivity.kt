package com.example.waautoreply

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: TextInputEditText
    private lateinit var etPrompt: TextInputEditText
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val logListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderLog() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        etPrompt = findViewById(R.id.etPrompt)
        switchEnabled = findViewById(R.id.switchEnabled)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        etApiKey.setText(Prefs.getApiKey(this))
        etPrompt.setText(Prefs.getPrompt(this))
        switchEnabled.isChecked = Prefs.isEnabled(this)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener { openNotificationAccess() }

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                saveSettings()
                if (Prefs.getApiKey(this).isBlank()) {
                    Toast.makeText(this, "Isi Groq API Key terlebih dahulu", Toast.LENGTH_LONG).show()
                    switchEnabled.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (!hasNotificationAccess()) {
                    Toast.makeText(this, "Berikan Akses Notifikasi untuk aplikasi ini", Toast.LENGTH_LONG).show()
                    openNotificationAccess()
                }
            }
            Prefs.setEnabled(this, checked)
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.plain(this).registerOnSharedPreferenceChangeListener(logListener)
        updateStatus()
        renderLog()
    }

    override fun onPause() {
        Prefs.plain(this).unregisterOnSharedPreferenceChangeListener(logListener)
        super.onPause()
    }

    private fun saveSettings() {
        Prefs.setApiKey(this, etApiKey.text?.toString().orEmpty())
        Prefs.setPrompt(this, etPrompt.text?.toString().orEmpty())
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun updateStatus() {
        val access = if (hasNotificationAccess()) "✅ Akses notifikasi: diberikan" else "❌ Akses notifikasi: BELUM diberikan"
        val bot = if (Prefs.isEnabled(this)) "🟢 Auto-reply: AKTIF" else "⚪ Auto-reply: nonaktif"
        tvStatus.text = "$access\n$bot"
    }

    private fun renderLog() {
        val logs = Prefs.getLog(this)
        if (logs.isEmpty()) {
            tvLog.text = "Belum ada riwayat."
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale("id", "ID"))
        tvLog.text = logs.joinToString("\n\n") {
            val icon = if (it.isError) "⚠️" else "🤖"
            "[${fmt.format(Date(it.time))}] ${it.sender}\n💬 ${it.incoming}\n$icon ${it.reply}"
        }
    }
}
