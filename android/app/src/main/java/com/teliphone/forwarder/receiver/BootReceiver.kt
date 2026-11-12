package com.teliphone.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.teliphone.forwarder.data.AppPreferences
import com.teliphone.forwarder.service.ForwarderForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = AppPreferences(context)
            if (prefs.isConfigured) {
                Log.d("BootReceiver", "Автозапуск фоновой службы после перезагрузки...")
                ForwarderForegroundService.start(context)
            }
        }
    }
}
