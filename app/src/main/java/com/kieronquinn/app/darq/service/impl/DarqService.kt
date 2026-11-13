package com.kieronquinn.app.darq.service.impl

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IProcessObserver
import android.app.IUiModeManager
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.kieronquinn.app.darq.BuildConfig
import com.kieronquinn.app.darq.IDarqService
import com.kieronquinn.app.darq.model.location.LatLng
import com.kieronquinn.app.darq.model.root.RootConstants
import com.kieronquinn.app.darq.model.settings.IPCSetting
import com.kieronquinn.app.darq.model.shizuku.ShizukuConstants
import com.kieronquinn.app.darq.providers.DarqServiceConnectionProvider
import com.kieronquinn.app.darq.utils.SystemProperties
import com.topjohnwu.superuser.internal.Utils
import rikka.shizuku.SystemServiceHelper
import kotlin.system.exitProcess


@SuppressLint("RestrictedApi")
class DarqService(private val serviceType: DarqServiceConnectionProvider.ServiceType = DarqServiceConnectionProvider.ServiceType.SHIZUKU) : IDarqService.Stub() {

    companion object {
        private const val TAG = "DarQService"
        private const val FORCE_DARK_PROP = "debug.hwui.force_dark"
        private const val OXYGEN_OS_WORLD_FORCE_DARK = "op_force_dark_entire_world"
        private const val OXYGEN_OS_AOSP_FORCE_DARK = "aosp_force_dark_mode"
        private const val EXPANDED_DARK_PACKAGES =
            "accessibility_force_invert_color_override_packages_to_enable"
        private const val SHIZUKU_SERVICE_ID = "${BuildConfig.APPLICATION_ID}:${ShizukuConstants.SERVICE_NAME}"
        private const val ROOT_SERVICE_ID = "${BuildConfig.APPLICATION_ID}:${RootConstants.SERVICE_NAME}"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val forceDarkState = ForceDarkState()
    private val pendingWhitelist = mutableSetOf<String>()
    private var observerRegistered = false
    private var lastForceDarkWrite: Boolean? = null
    private val startedProcesses = mutableMapOf<Int, String>()
    private val expandedDarkLock = Any()

    //This is the "current application" context, in this case the system context. It's only used to query PackageManager.
    private val context by lazy {
        Utils.getContext()
    }

    //This is the internal ActivityManager via SystemServiceHelper
    private val activityManager by lazy {
        val activityManagerProxy = SystemServiceHelper.getSystemService("activity")
        IActivityManager.Stub.asInterface(activityManagerProxy)
    }

    private val uiModeManager by lazy {
        val uiModeManagerProxy = SystemServiceHelper.getSystemService("uimode")
        IUiModeManager.Stub.asInterface(uiModeManagerProxy)
    }

    //Packages to ignore foreground changes to. These include the launcher, recents provider and android itself.
    private val ignoredPackages = arrayOf("android", "com.android.systemui", getLauncherPackage(), getQuickstepPackage()).distinct().filterNotNull()

    /**
     *  Called from DarqServiceConnectionProvider when the service is bound to
     */
    override fun onBind() {
        handler.post {
            if (!observerRegistered) {
                try {
                    activityManager.registerProcessObserver(processObserver)
                    observerRegistered = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "Process observation is unavailable on this ROM", e)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Could not register the process observer", e)
                }
            }
            if (forceDarkState.foregroundPackage == null) {
                forceDarkState.foregroundPackage = getRunningAppProcesses()
                    .firstNotNullOfOrNull {
                        if (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            it.darqPackageName()?.takeUnless(ignoredPackages::contains)
                        } else null
                    }
            }
            applyForceDark()
        }
    }


    /**
     *  Called for each whitelisted app when the service is started (Binder limit is 1Mb so it
     *  has to be split for users with huge app lists)
     */
    override fun setupWhitelist(isStart: Boolean, isEnd: Boolean, packageName: String?) {
        handler.post {
            if (isStart) pendingWhitelist.clear()
            if (!packageName.isNullOrBlank()) pendingWhitelist.add(packageName)
            if (isEnd) {
                forceDarkState.selectedPackages.clear()
                forceDarkState.selectedPackages.addAll(pendingWhitelist)
                pendingWhitelist.clear()
                applyForceDark()
            }
        }
    }

    /**
     *  Setup with a given IPCSetting object. All the fields should be non-null here, except packageChange as that
     *  is handled separately.
     */
    override fun setupSettings(ipcSetting: IPCSetting) {
        handler.post {
            ipcSetting.enabled?.let { forceDarkState.enabled = it }
            ipcSetting.alwaysForceDark?.let { forceDarkState.alwaysForceDark = it }
            ipcSetting.sendAppCloses?.let { forceDarkState.sendAppCloses = it }
            ipcSetting.oxygenForceDark?.let { setOxygenOSEntireWorldEnabled(it) }
            ipcSetting.packageChange?.let {
                if (it.enabled) forceDarkState.selectedPackages.add(it.packageName)
                else forceDarkState.selectedPackages.remove(it.packageName)
            }
            applyForceDark()
        }
    }

    /**
     *  Settings change from the settings UI. This will only be called after the service is set up and the app is running
     *  Only one of the fields should be non-null
     */
    override fun notifySettingsChange(ipcSetting: IPCSetting) {
        setupSettings(ipcSetting)
    }

    /**
     *  Sets Force Dark to a given value using the reflected SystemProperties rather than a shell
     *  command (for speed)
     */
    private fun applyForceDark() {
        val enabled = forceDarkState.shouldForceDark
        if (lastForceDarkWrite == enabled) return
        if (SystemProperties.getBoolean(FORCE_DARK_PROP, false) == enabled) {
            lastForceDarkWrite = enabled
            return
        }
        SystemProperties[FORCE_DARK_PROP] = enabled.toString()
        if (SystemProperties.getBoolean(FORCE_DARK_PROP, false) == enabled) {
            lastForceDarkWrite = enabled
        } else {
            Log.w(TAG, "The ROM rejected a Force Dark property update")
        }
    }

    /**
     *  Sets the Oxygen OS "entire world" force dark Settings.Secure setting
     *  This only seems to be used in Oxygen OS 10 and does nothing in 11
     */
    private fun setOxygenOSEntireWorldEnabled(enabled: Boolean){
        val enabledString = if(enabled) "1" else "0"
        val commands = arrayOf(OXYGEN_OS_WORLD_FORCE_DARK, OXYGEN_OS_AOSP_FORCE_DARK).map { "settings put secure $it $enabledString" }
        runCommands(*commands.toTypedArray())
    }

    /**
     *  Registers a Process Observer via IActivityManager - to the system Activity Manager service.
     *  The magic here is Shizuku's SystemServiceHelper, which uses reflection to get the system
     *  service, which we can then use registerProcessObserver() on and note the callbacks to
     *  onForegroundActivitiesChanged(), where foregroundActivities = true when an app is opened.
     */
    private val processObserver = object : IProcessObserver.Stub() {
        override fun onProcessStarted(
            pid: Int, processUid: Int, packageUid: Int, packageName: String?, processName: String?
        ) {
            if (!packageName.isNullOrEmpty()) handler.post { startedProcesses[pid] = packageName }
        }

        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foregroundActivities: Boolean) {
            handler.post {
                val packageName = startedProcesses[pid] ?: getRunningAppProcesses()
                    .firstOrNull { it.pid == pid }?.darqPackageName() ?: return@post
                if (packageName in ignoredPackages) return@post
                forceDarkState.updateForeground(packageName, foregroundActivities)
                applyForceDark()
            }
        }

        override fun onForegroundServicesChanged(pid: Int, uid: Int, serviceTypes: Int) {
            //Don't care
        }

        override fun onProcessDied(pid: Int, uid: Int) {
            handler.post {
                val packageName = startedProcesses.remove(pid) ?: return@post
                forceDarkState.updateForeground(packageName, foreground = false)
                applyForceDark()
            }
        }
    }

    /**
     *  Called by Shizuku - NOT by libsu though. Clean up what we can nicely.
     *  libsu will simply kill the process as part of the restart
     */
    override fun destroy() {
        handler.post {
            if (observerRegistered) runCatching { activityManager.unregisterProcessObserver(processObserver) }
            forceDarkState.enabled = false
            applyForceDark()
            exitProcess(0)
        }
    }

    /**
     *  Finds a given PID in the running apps and returns its process name, which seems to be the
     *  package name.
     */
    private fun ActivityManager.RunningAppProcessInfo.darqPackageName(): String? {
        return pkgList?.singleOrNull() ?: processName?.substringBefore(':')
    }

    private fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> {
        return try {
            activityManager.runningAppProcesses.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RemoteException) {
            emptyList()
        }
    }

    /**
     *  Kills all other processes of either the :root or the :service IPCs.
     *  This is done by reading the whole list of processes, printing the PID and names,
     *  filtering the list by those that are the :root or :service processes, and killing the
     *  given pid using `kill`. If no other processes are found, nothing is killed.
     */
    override fun killOtherInstances(){
        val myPid = android.os.Process.myPid()
        val processesByName = runCommand("ps -A -o PID,NAME")
        //Find just my processes & retrieve the PIDs that don't match our own
        val otherPids = processesByName.map { it.trim() }
            .filter { it.endsWith(SHIZUKU_SERVICE_ID) || it.endsWith(ROOT_SERVICE_ID) }
            .mapNotNull {
                val split = it.split(" ")
                val pid = split[0]
                val name = split[1]
                if (name == SHIZUKU_SERVICE_ID || name == ROOT_SERVICE_ID) pid.toIntOrNull() else null
            }.filterNot { it == myPid }
        if (otherPids.isEmpty()) {
            return
        }
        val killCommands = otherPids.map { "kill $it" }
        runCommands(*killCommands.toTypedArray())
    }

    /**
     *  Gets the device's last location, this is ONLY used with user consent.
     */
    @SuppressLint("MissingPermission")
    override fun getLocation(): LatLng? {
        // Location is read by the authorized root/shell service when the user enables it.
        val token = Binder.clearCallingIdentity()
        try {
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            val location = manager.getProviders(true).mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.maxByOrNull { it.elapsedRealtimeNanos } ?: return null
            return LatLng(location.latitude, location.longitude)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     *  Sets the night mode using UiModeManager
     */
    override fun setNightMode(nightMode: Boolean) {
        val token = Binder.clearCallingIdentity()
        try {
            uiModeManager.setNightMode(if (nightMode) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getExpandedDarkPackages(): String? {
        if (Build.VERSION.SDK_INT < 37) return null
        val user = UserHandle.getUserHandleForUid(Binder.getCallingUid()).hashCode().toString()
        return synchronized(expandedDarkLock) { readExpandedDarkPackages(user) }
    }

    override fun setExpandedDarkPackages(expected: String, updated: String): Boolean {
        if (Build.VERSION.SDK_INT < 37 || !ExpandedDarkPackages.isPackageList(updated)) return false
        val user = UserHandle.getUserHandleForUid(Binder.getCallingUid()).hashCode().toString()
        return synchronized(expandedDarkLock) {
            if (readExpandedDarkPackages(user) != expected) return@synchronized false
            runSettingsCommand(user, "put", updated) != null && readExpandedDarkPackages(user) == updated
        }
    }

    private fun readExpandedDarkPackages(user: String): String? =
        runSettingsCommand(user, "get")?.trim()?.let { if (it == "null") "" else it }

    private fun runSettingsCommand(user: String, operation: String, value: String? = null): String? {
        val args = listOf("settings", "--user", user, operation, "system", EXPANDED_DARK_PACKAGES)
        return runCatching {
            val process = ProcessBuilder(args + listOfNotNull(value)).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.takeIf { process.waitFor() == 0 }
        }.onFailure { Log.w(TAG, "Expanded dark settings are unavailable on this ROM", it) }.getOrNull()
    }

    /**
     *  Runs a given command *without root*, wrapping it in `sh` and returning the output lines
     */
    private fun runCommand(command: String): List<String> {
        ProcessBuilder().command("sh", "-c", command).start().run {
            val lines = inputStream.bufferedReader().use { it.readLines() }
            destroy()
            return lines
        }
    }

    /**
     *  Runs a set of commands, wrapping each in `sh`, ignoring output
     */
    private fun runCommands(vararg commands: String) {
        ProcessBuilder().run {
            commands.forEach {
                command("sh", "-c", it).start().waitFor()
            }
        }
    }

    /**
     *  Gets the default "Home" app if set, or null if not. We don't want to apply force dark to
     *  the home app, and don't want to disable/enable it when Home is briefly visible for speed.
     */
    private fun getLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo: ResolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        return resolveInfo.activityInfo.packageName
    }

    /**
     *  Gets the "Quickstep" (recents) package the device is using, so we don't apply changes to
     *  force dark when the recents is opened and closed, again for speed.
     *  If the user hasn't set a custom launcher, this will most likely be the same as the launcher
     *  package, and will be filtered out with distinct()
     */
    private fun getQuickstepPackage(): String? {
        val systemResources = Resources.getSystem()
        val recentsComponentNameId = systemResources.getIdentifier("config_recentsComponentName", "string", "android")
        if(recentsComponentNameId == 0) return null
        val recentsComponentName = systemResources.getString(recentsComponentNameId)
        return if(recentsComponentName != null) {
            ComponentName.unflattenFromString(recentsComponentName)?.packageName
        }else null
    }

    override fun ping() {
        //Hello!
    }

    override fun getServiceType(): String {
        return this.serviceType.toString()
    }

}
