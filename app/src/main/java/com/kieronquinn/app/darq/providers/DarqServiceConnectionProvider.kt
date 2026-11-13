package com.kieronquinn.app.darq.providers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Build
import android.os.RemoteException
import com.kieronquinn.app.darq.BuildConfig
import com.kieronquinn.app.darq.IDarqService
import com.kieronquinn.app.darq.components.settings.DarqSharedPreferences
import com.kieronquinn.app.darq.model.shizuku.ShizukuConstants
import com.kieronquinn.app.darq.service.impl.DarqService
import com.kieronquinn.app.darq.service.impl.ExpandedDarkPackages
import com.kieronquinn.app.darq.service.root.DarqRootService
import com.kieronquinn.app.darq.utils.extensions.isShizukuInstalled
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlin.coroutines.resume

class DarqServiceConnectionProvider(private val context: Context, private val settings: DarqSharedPreferences) {

    companion object {
        private const val SERVICE_TIMEOUT = 25000L
    }

    private val rootServiceIntent = Intent(context, DarqRootService::class.java)
    private val mainHandler = Handler(context.mainLooper)
    private var rootService: IDarqService? = null
    private val serviceLock = Mutex()
    private val whitelistLock = Any()
    private val expandedDarkLock = Any()

    private val darqProcessArgs = UserServiceArgs(ComponentName(context, DarqService::class.java)).apply {
        processNameSuffix(ShizukuConstants.SERVICE_NAME)
        debuggable(BuildConfig.DEBUG)
        version(BuildConfig.VERSION_CODE)
    }

    sealed class ServiceResult {
        data class Success(val service: IDarqService, val serviceType: ServiceType): ServiceResult()
        data class Failed(val reason: ServiceFailureReason): ServiceResult()
    }

    enum class ServiceFailureReason {
        TIMEOUT, SHIZUKU_PERMISSION_REQUIRED, SHIZUKU_NOT_STARTED, SHIZUKU_NOT_INSTALLED
    }

    enum class ServiceType {
        SHIZUKU, ROOT, UNKNOWN
    }

    private var serviceType: ServiceType = ServiceType.UNKNOWN

    suspend fun getService(): ServiceResult {
        return serviceLock.withLock {
            withContext(Dispatchers.Main) {
                getServiceLocked() ?: ServiceResult.Failed(ServiceFailureReason.TIMEOUT)
            }
        }
    }

    private suspend fun getServiceLocked(): ServiceResult? {
        rootService?.let { service ->
            try {
                service.ping()
                return ServiceResult.Success(service, serviceType)
            } catch (_: DeadObjectException) {
                rootService = null
            }
        }

        val useRoot = withContext(Dispatchers.IO) { Shell.rootAccess() }
        if (!useRoot) {
            if (!context.isShizukuInstalled() && !Shizuku.pingBinder()) {
                return ServiceResult.Failed(ServiceFailureReason.SHIZUKU_NOT_INSTALLED)
            }
            if (!Shizuku.pingBinder()) return ServiceResult.Failed(ServiceFailureReason.SHIZUKU_NOT_STARTED)
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return ServiceResult.Failed(ServiceFailureReason.SHIZUKU_PERMISSION_REQUIRED)
                }
            } catch (_: IllegalStateException) {
                return ServiceResult.Failed(ServiceFailureReason.SHIZUKU_NOT_STARTED)
            }
        }

        return withTimeoutOrNull(SERVICE_TIMEOUT) {
            val (service, connection) = bindService(useRoot) ?: return@withTimeoutOrNull null
            var connected = false
            try {
                val type = withContext(Dispatchers.IO) {
                    service.setupSettings(settings.toIPCSetting())
                    syncEnabledApps(service)
                    syncExpandedDark(service)
                    service.onBind()
                    ServiceType.valueOf(service.serviceType)
                }
                serviceConnection = connection
                rootService = service
                serviceType = type
                connected = true
                ServiceResult.Success(service, type)
            } catch (_: RemoteException) {
                ServiceResult.Failed(ServiceFailureReason.TIMEOUT)
            } finally {
                if (!connected) unbindService(useRoot, connection)
            }
        }
    }

    private var serviceConnection: ServiceConnection? = null

    private suspend fun bindService(useRoot: Boolean) =
        suspendCancellableCoroutine<Pair<IDarqService, ServiceConnection>?> { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!continuation.isActive) return
                    val service = IDarqService.Stub.asInterface(binder)
                    continuation.resume(service?.let { it to this })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (serviceConnection === this) rootService = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                mainHandler.post { unbindService(useRoot, connection) }
            }
            try {
                if (useRoot) {
                    RootService.stop(rootServiceIntent)
                    RootService.bind(rootServiceIntent, connection)
                } else {
                    Shizuku.bindUserService(darqProcessArgs, connection)
                }
            } catch (_: IllegalStateException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun unbindService(useRoot: Boolean, connection: ServiceConnection) {
        runCatching {
            if (useRoot) RootService.unbind(connection)
            else Shizuku.unbindUserService(darqProcessArgs, connection, false)
        }
    }

    fun syncEnabledApps(service: IDarqService) = synchronized(whitelistLock) {
        val apps = settings.enabledApps
        if (apps.isEmpty()) {
            service.setupWhitelist(true, true, null)
        } else {
            apps.forEachIndexed { index, app ->
                service.setupWhitelist(index == 0, index == apps.lastIndex, app)
            }
        }
    }

    fun syncExpandedDark(service: IDarqService): Boolean = synchronized(expandedDarkLock) {
        if (Build.VERSION.SDK_INT < 37) return@synchronized true
        val prefs = context.getSharedPreferences("expanded_dark_state", Context.MODE_PRIVATE)
        val owned = prefs.getStringSet("owned_packages", emptySet()).orEmpty().toSet()
        val desired = if (settings.enabled && settings.expandedDark) settings.enabledApps.toSet() else emptySet()
        if (owned.isEmpty() && desired.isEmpty()) return@synchronized true

        repeat(2) {
            val existing = service.expandedDarkPackages ?: return@synchronized false
            if (!ExpandedDarkPackages.isPackageList(existing)) return@synchronized false
            val plan = ExpandedDarkPackages.merge(existing, owned, desired - BuildConfig.APPLICATION_ID)
            // Record new ownership before writing the persistent Android setting.
            if (!prefs.edit().putStringSet("owned_packages", owned + plan.owned).commit()) {
                return@synchronized false
            }
            if (plan.csv == existing || service.setExpandedDarkPackages(existing, plan.csv)) {
                return@synchronized prefs.edit().putStringSet("owned_packages", plan.owned).commit()
            }
            prefs.edit().putStringSet("owned_packages", owned).commit()
        }
        false
    }

}
