package com.v2ray.ang.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only provider exposing the VPN state and the current network state,
 * so they can be queried from `adb shell content call` or from other apps.
 *
 * Runs inside the core process, otherwise [CoreServiceManager.isRunning] would
 * always report false.
 */
class StatusProvider : ContentProvider() {

    companion object {
        const val METHOD_STATUS = "status"
        const val METHOD_NETWORK = "network"
        const val METHOD_ALL = "all"

        /** Pass as the `call` argument to actively probe the network instead of only reading its state. */
        const val ARG_PROBE = "probe"

        private const val PROBE_TIMEOUT_MILLIS = 5000
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return try {
            when (method) {
                METHOD_STATUS -> vpnStatus()
                METHOD_NETWORK -> networkStatus(probe = arg == ARG_PROBE)
                METHOD_ALL -> Bundle().apply {
                    putAll(vpnStatus())
                    putAll(networkStatus(probe = arg == ARG_PROBE))
                }

                else -> null
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StatusProvider: call($method) failed", e)
            Bundle().apply { putString("error", e.message ?: e.javaClass.simpleName) }
        }
    }

    /**
     * Reports whether the core is running and which profile is selected.
     */
    private fun vpnStatus(): Bundle {
        val running = CoreServiceManager.isRunning()
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        val selectedRemarks = selectedGuid.takeIf { it.isNotEmpty() }
            ?.let { MmkvManager.decodeServerConfig(it)?.remarks }
            .orEmpty()

        return Bundle().apply {
            putBoolean("running", running)
            putString("state", if (running) "CONNECTED" else "DISCONNECTED")
            putString("selected_guid", selectedGuid)
            putString("selected_remarks", selectedRemarks)
            putString("running_remarks", CoreServiceManager.getRunningServerName())
            putString("mode", if (SettingsManager.isVpnMode()) "VPN" else "PROXY")
        }
    }

    /**
     * Reports the state of the active network, optionally confirming it with a real request.
     *
     * `validated` is the system's own verdict on whether the network actually reaches the
     * internet; `probe_ok` is our own check and is only present when probing was requested.
     */
    private fun networkStatus(probe: Boolean): Bundle {
        val bundle = Bundle()
        val context = context ?: return bundle.apply { putString("error", "no context") }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return bundle.apply { putString("error", "no ConnectivityManager") }

        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }

        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        bundle.putBoolean("connected", capabilities != null)
        bundle.putBoolean("has_internet", hasInternet)
        bundle.putBoolean("validated", validated)
        bundle.putBoolean("vpn_active", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        bundle.putBoolean("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
        bundle.putString("transport", transportName(capabilities))

        if (probe) {
            val url = SettingsManager.getDelayTestUrl()
            bundle.putString("probe_url", url)

            // The app excludes itself from its own VPN, so this request always leaves
            // the device outside the tunnel: it tells us the device has internet at all.
            val start = System.currentTimeMillis()
            val code = probeUrl(url)
            bundle.putInt("direct_code", code)
            bundle.putLong("direct_latency_ms", System.currentTimeMillis() - start)
            bundle.putBoolean("direct_ok", code in 200..399)

            // Routing the same request through the running core is what actually proves
            // the proxy works.
            if (CoreServiceManager.isRunning()) {
                var (delay, error) = CoreServiceManager.measureDelay(url)
                if (delay < 0) {
                    // The primary URL may be blocked; retry once like the in-app test does.
                    val (retryDelay, retryError) =
                        CoreServiceManager.measureDelay(SettingsManager.getDelayTestUrl(true))
                    if (retryDelay >= 0) {
                        delay = retryDelay
                        error = ""
                    } else if (retryError.isNotEmpty()) {
                        error = retryError
                    }
                }
                bundle.putLong("tunnel_delay_ms", delay)
                bundle.putBoolean("tunnel_ok", delay >= 0)
                bundle.putString("tunnel_error", error)
                bundle.putInt("socks_port", SettingsManager.getSocksPort())
                bundle.putInt("http_port", SettingsManager.getHttpPort())
                if (delay >= 0) {
                    bundle.putString("tunnel_ip", SpeedtestManager.getRemoteIPInfo().orEmpty())
                }
            } else {
                bundle.putBoolean("tunnel_ok", false)
                bundle.putLong("tunnel_delay_ms", -1)
                bundle.putString("tunnel_error", "core not running")
            }
        }

        return bundle
    }

    private fun transportName(capabilities: NetworkCapabilities?): String {
        capabilities ?: return "NONE"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }

    /**
     * @return The HTTP status code, or -1 when the request could not be completed.
     */
    private fun probeUrl(url: String): Int {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PROBE_TIMEOUT_MILLIS
                readTimeout = PROBE_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                setRequestProperty("Connection", "close")
            }
            connection.responseCode
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StatusProvider: probe failed - ${e.message}")
            -1
        } finally {
            connection?.disconnect()
        }
    }

    // ---------- Unused ContentProvider surface ----------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val bundle = when (uri.lastPathSegment) {
            METHOD_NETWORK -> networkStatus(probe = false)
            METHOD_ALL -> Bundle().apply {
                putAll(vpnStatus())
                putAll(networkStatus(probe = false))
            }

            else -> vpnStatus()
        }
        val keys = bundle.keySet().toList()
        return MatrixCursor(keys.toTypedArray()).apply {
            addRow(keys.map { bundle.get(it)?.toString() })
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
