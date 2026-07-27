package com.v2ray.ang.ui.shortcut

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point that lets other apps and `adb shell am start` control the VPN service.
 *
 * The action can be given either as the intent action
 * (`${applicationId}.action.vpn.start` and friends) or as a string extra named `action`
 * with the value `start`, `stop`, `restart` or `switch`.
 */
class VpnControlActivity : BaseComponentActivity() {

    companion object {
        const val ACTION_START = "${AppConfig.ANG_PACKAGE}.action.vpn.start"
        const val ACTION_STOP = "${AppConfig.ANG_PACKAGE}.action.vpn.stop"
        const val ACTION_RESTART = "${AppConfig.ANG_PACKAGE}.action.vpn.restart"
        const val ACTION_SWITCH = "${AppConfig.ANG_PACKAGE}.action.vpn.switch"

        private const val EXTRA_ACTION = "action"
        private const val RESTART_DELAY_MILLIS = 500L
    }

    private enum class Command { START, STOP, RESTART, SWITCH }

    private var pendingCommand: Command? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val command = pendingCommand
            pendingCommand = null
            if (result.resultCode == RESULT_OK && command != null) {
                execute(command)
            } else {
                LogUtil.w(AppConfig.TAG, "VpnControl: VPN permission denied")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val command = resolveCommand()
        if (command == null) {
            LogUtil.w(AppConfig.TAG, "VpnControl: unknown command in $intent")
            finish()
            return
        }

        // Stopping never needs the VPN consent dialog.
        val needsPermission = command != Command.STOP &&
            !(command == Command.SWITCH && CoreServiceManager.isRunning()) &&
            SettingsManager.isVpnMode()
        val prepareIntent = if (needsPermission) VpnService.prepare(this) else null

        if (prepareIntent != null) {
            // Keep the activity visible so the system consent dialog can be shown.
            pendingCommand = command
            requestVpnPermission.launch(prepareIntent)
        } else {
            moveTaskToBack(true)
            execute(command)
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun resolveCommand(): Command? = when (intent?.action) {
        ACTION_START -> Command.START
        ACTION_STOP -> Command.STOP
        ACTION_RESTART -> Command.RESTART
        ACTION_SWITCH -> Command.SWITCH
        else -> when (intent?.getStringExtra(EXTRA_ACTION)?.lowercase()) {
            "start" -> Command.START
            "stop" -> Command.STOP
            "restart" -> Command.RESTART
            "switch", "toggle" -> Command.SWITCH
            else -> null
        }
    }

    private fun execute(command: Command) {
        LogUtil.i(AppConfig.TAG, "VpnControl: $command")
        when (command) {
            Command.START -> {
                if (!CoreServiceManager.isRunning()) {
                    CoreServiceManager.startVServiceFromToggle(this)
                }
                finish()
            }

            Command.STOP -> {
                if (CoreServiceManager.isRunning()) {
                    CoreServiceManager.stopVService(this)
                }
                finish()
            }

            Command.SWITCH -> {
                if (CoreServiceManager.isRunning()) {
                    CoreServiceManager.stopVService(this)
                } else {
                    CoreServiceManager.startVServiceFromToggle(this)
                }
                finish()
            }

            Command.RESTART -> {
                lifecycleScope.launch {
                    if (CoreServiceManager.isRunning()) {
                        CoreServiceManager.stopVService(this@VpnControlActivity)
                        delay(RESTART_DELAY_MILLIS)
                    }
                    CoreServiceManager.startVServiceFromToggle(this@VpnControlActivity)
                    finish()
                }
            }
        }
    }
}
