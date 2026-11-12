package com.teliphone.forwarder.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.teliphone.forwarder.data.AppPreferences
import com.teliphone.forwarder.data.MessagePayload
import com.teliphone.forwarder.data.SupabaseRepository
import com.teliphone.forwarder.databinding.ActivityMainBinding
import com.teliphone.forwarder.service.ForwarderForegroundService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var repository: SupabaseRepository

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val smsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
            if (smsGranted) {
                Toast.makeText(this, "Разрешения SMS получены!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Разрешение SMS отклонено!", Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        repository = SupabaseRepository(this)

        initViews()
        setupListeners()
        updateStatus()

        // Запуск фоновой службы
        if (prefs.isConfigured) {
            ForwarderForegroundService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun initViews() {
        binding.etSupabaseUrl.setText(prefs.supabaseUrl)
        binding.etAnonKey.setText(prefs.supabaseAnonKey)
        binding.etDeviceName.setText(prefs.deviceName)
        binding.switchSms.isChecked = prefs.isSmsForwardingEnabled
        binding.switchNotifications.isChecked = prefs.isNotificationForwardingEnabled
        updateSelectedAppsText()
    }

    private fun updateSelectedAppsText() {
        val count = prefs.selectedPackages.size
        binding.tvSelectedApps.text = if (prefs.forwardAllApps) {
            "Режим: пересылать от ВСЕХ приложений"
        } else {
            "Выбрано приложений для пересылки: $count"
        }
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            prefs.supabaseUrl = binding.etSupabaseUrl.text.toString()
            prefs.supabaseAnonKey = binding.etAnonKey.text.toString()
            prefs.deviceName = binding.etDeviceName.text.toString()

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            if (prefs.isConfigured) {
                ForwarderForegroundService.start(this)
            }
            updateStatus()
        }

        binding.switchSms.setOnCheckedChangeListener { _, isChecked ->
            prefs.isSmsForwardingEnabled = isChecked
        }

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.isNotificationForwardingEnabled = isChecked
            if (isChecked && !isNotificationServiceEnabled()) {
                openNotificationAccessSettings()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            showAppSelectionDialog()
        }

        binding.btnRequestSmsPermission.setOnClickListener {
            val permissions = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }

        binding.btnOpenNotifSettings.setOnClickListener {
            openNotificationAccessSettings()
        }

        binding.btnDisableBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.btnSendTest.setOnClickListener {
            sendTestMessage()
        }
    }

    private fun showAppSelectionDialog() {
        val pm = packageManager
        // Получаем все установленные несистемные приложения
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // Показываем пользовательские приложения либо популярные мессенджеры/банки
                !isSystem || isUpdatedSystem || AppPreferences.defaultPackages.contains(app.packageName)
            }
            .sortedBy { app -> pm.getApplicationLabel(app).toString().lowercase() }

        val appLabels = installedApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val appPackages = installedApps.map { it.packageName }
        val checkedItems = BooleanArray(installedApps.size) { i ->
            prefs.selectedPackages.contains(appPackages[i])
        }

        AlertDialog.Builder(this)
            .setTitle("Выберите приложения")
            .setMultiChoiceItems(appLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Сохранить") { _, _ ->
                val newSelected = mutableSetOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        newSelected.add(appPackages[i])
                    }
                }
                prefs.selectedPackages = newSelected
                prefs.forwardAllApps = false
                updateSelectedAppsText()
                Toast.makeText(this, "Выбрано приложений: ${newSelected.size}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Все приложения") { _, _ ->
                prefs.forwardAllApps = true
                updateSelectedAppsText()
                Toast.makeText(this, "Включена пересылка от всех приложений", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateStatus() {
        val hasSmsPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotifAccess = isNotificationServiceEnabled()

        val sb = StringBuilder()
        sb.append(prefs.lastLog).append("\n\n")
        sb.append("SMS пересылка: ").append(if (hasSmsPerm) "✓ Активна" else "✗ Нет прав").append("\n")
        sb.append("Доступ к уведомлениям: ").append(if (hasNotifAccess) "✓ Активен" else "✗ Выключен").append("\n")
        sb.append("Фильтр приложений: ").append(if (prefs.forwardAllApps) "Все" else "${prefs.selectedPackages.size} выбрано")

        binding.tvStatus.text = sb.toString()
        updateSelectedAppsText()
    }

    private fun sendTestMessage() {
        if (!prefs.isConfigured) {
            Toast.makeText(this, "Сначала укажите URL и Anon Key!", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSendTest.isEnabled = false
        binding.tvStatus.text = "Отправка тестового сообщения в Supabase..."

        lifecycleScope.launch {
            val payload = MessagePayload(
                sender = "+7 (999) 000-00-00 (Тест)",
                body = "Привет с Android! Это проверочное сообщение от Teliphone для iOS.",
                source = "test",
                packageName = packageName,
                deviceId = prefs.deviceName
            )

            val result = repository.sendMessage(payload)
            binding.btnSendTest.isEnabled = true
            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, "Тестовое сообщение успешно отправлено!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = "${packageName}/${com.teliphone.forwarder.service.NotificationForwarderService::class.java.name}"
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn)
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть настройки уведомлений", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Оптимизация батареи уже отключена!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
