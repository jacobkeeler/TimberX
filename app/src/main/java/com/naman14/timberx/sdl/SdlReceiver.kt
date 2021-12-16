package com.naman14.timberx.sdl

import android.content.Context
import android.content.Intent
import android.os.Build
import com.naman14.timberx.sdl.SdlService
import com.naman14.timberx.sdl.SdlRouterService
import com.smartdevicelink.transport.SdlBroadcastReceiver
import com.smartdevicelink.util.DebugTool


class SdlReceiver : SdlBroadcastReceiver() {
    override fun onSdlEnabled(context: Context, intent: Intent) {
        DebugTool.logInfo(TAG, "SDL Enabled")
        intent.setClass(context, SdlService::class.java)

        // SdlService needs to be foregrounded in Android O and above
        // This will prevent apps in the background from crashing when they try to start SdlService
        // Because Android O doesn't allow background apps to start background services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun defineLocalSdlRouterClass(): Class<out SdlRouterService?> {
        return SdlRouterService::class.java
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Required if overriding this method
    }

    companion object {
        private const val TAG = "SdlBroadcastReceiver"
    }
}