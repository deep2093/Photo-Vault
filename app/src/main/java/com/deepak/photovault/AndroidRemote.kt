package com.deepak.photovault

import android.content.Intent
import android.net.Uri
import com.deepak.photovault.core.vpn.AutoConnectOutcome
import com.deepak.photovault.core.vpn.ImportResult
import com.deepak.photovault.core.vpn.VpnState
import com.deepak.photovault.core.vpn.WireGuardManager
import com.deepak.photovault.ui.shell.RemoteAccessState
import com.deepak.photovault.ui.shell.RemotePlatform
import com.deepak.photovault.ui.shell.RemoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Remote access on Android: WireGuard, kept in step with the settings screen (Phase 5B-1). */
class AndroidRemotePlatform(
    private val manager: WireGuardManager,
    private val scope: CoroutineScope,
    private val pickFile: () -> Unit,
    private val askVpnPermission: (Intent) -> Unit,
) : RemotePlatform {
    val state = RemoteAccessState(this, supported = true).also {
        it.autoConnect = manager.prefs.autoConnect
        it.appOnly = manager.prefs.appOnly
    }

    /** Follow the tunnel; call once from the activity. */
    fun start() {
        scope.launch {
            combine(manager.state, manager.holdActive) { s, held -> s to held }.collect { (s, held) ->
                state.held = held
                when (s) {
                    VpnState.NoConfig -> state.status = RemoteStatus.NO_CONFIG
                    VpnState.Disconnected -> { state.status = RemoteStatus.OFF; state.driveReachable = null }
                    VpnState.Connecting -> state.status = RemoteStatus.CONNECTING
                    is VpnState.Connected -> { state.status = RemoteStatus.CONNECTED; state.driveReachable = s.serverReachable }
                    VpnState.NeedsPermission -> state.status = RemoteStatus.NEEDS_PERMISSION
                    is VpnState.Error -> { state.status = RemoteStatus.ERROR; state.message = s.message }
                }
            }
        }
    }

    override fun importConfig() = pickFile()

    /** A WireGuard file that came inside an invite. */
    override fun importConfigText(text: String) {
        val file = java.io.File.createTempFile("invite-wg", ".conf")
        try {
            file.writeText(text)
            scope.launch {
                state.message = when (val r = manager.importConfig(Uri.fromFile(file))) {
                    is ImportResult.Success -> "VPN set up from the invite. Tap Connect to use it."
                    is ImportResult.Failure -> r.message
                }
                file.delete()
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            state.message = when (val r = manager.importConfig(uri)) {
                is ImportResult.Success -> "Imported. Tap Connect to try it."
                is ImportResult.Failure -> r.message
            }
        }
    }

    override fun connect() {
        state.message = null
        val consent = manager.consentIntent()
        if (consent != null) {
            askVpnPermission(consent)
            return
        }
        scope.launch { manager.connect(userInitiated = true) }
    }

    fun onPermissionAnswer(granted: Boolean) {
        if (granted) scope.launch { manager.connect(userInitiated = true) } else {
            manager.consentDenied()
            state.message = "Not allowed. Away-from-home access can't work without it."
        }
    }

    override fun disconnect() {
        scope.launch { manager.disconnect(force = true, userInitiated = true) }
    }

    override fun removeConfig() {
        scope.launch {
            manager.disconnect(force = true, userInitiated = true)
            manager.removeConfig()
            state.message = "VPN setup removed."
        }
    }

    override fun setAutoConnect(on: Boolean) {
        manager.prefs.autoConnect = on
        state.autoConnect = on
    }

    override fun setAppOnly(on: Boolean) {
        manager.prefs.appOnly = on
        state.appOnly = on
        state.message = "Applies the next time the VPN connects."
    }

    /** App came on screen: connect by itself only if switched on and the drive isn't reachable already. */
    fun onScreenOn() {
        manager.onAppForegrounded()
        manager.startMonitoring()
        if (manager.prefs.autoConnect) scope.launch {
            val outcome = manager.autoConnect(respectBackoff = false, showBusy = true)
            if (outcome == AutoConnectOutcome.NEEDS_PERMISSION) state.status = RemoteStatus.NEEDS_PERMISSION
        }
    }
}
