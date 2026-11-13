package com.kieronquinn.app.darq.work

import android.content.Context
import android.os.RemoteException
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kieronquinn.app.darq.components.settings.DarqSharedPreferences
import com.kieronquinn.app.darq.providers.DarqServiceConnectionProvider
import com.kieronquinn.app.darq.utils.TimeZoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.shredzone.commons.suncalc.SunTimes
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class DarqSunriseSunsetWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    companion object {
        private const val TAG_SUNRISE = "work_sunrise"
        private const val TAG_SUNSET = "work_sunset"
        private const val RESCHEDULE = "work_sun_reschedule"
        private const val KEY_NIGHT = "night_mode"

        fun reschedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RESCHEDULE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DarqSunriseSunsetWork>().build()
            )
        }
    }

    private val settings by inject<DarqSharedPreferences>()
    private val serviceProvider by inject<DarqServiceConnectionProvider>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(applicationContext)
        if (!settings.autoDarkTheme) {
            workManager.cancelAllWorkByTag(TAG_SUNRISE)
            workManager.cancelAllWorkByTag(TAG_SUNSET)
            return@withContext Result.success()
        }

        val isTransition = inputData.hasKeyWithValueOfType(KEY_NIGHT, Boolean::class.javaObjectType) ||
            TAG_SUNRISE in tags || TAG_SUNSET in tags
        val nightMode = inputData.getBoolean(KEY_NIGHT, TAG_SUNSET in tags)
        var retry = false
        val service = if (settings.useLocation || isTransition) {
            (serviceProvider.getService() as? DarqServiceConnectionProvider.ServiceResult.Success)?.service
        } else null
        if (isTransition) {
            retry = service == null
            try {
                service?.setNightMode(nightMode)
            } catch (_: RemoteException) {
                retry = true
            } catch (_: SecurityException) {
                retry = true
            }
        }
        if (retry && runAttemptCount < 3) return@withContext Result.retry()

        val deviceLocation = if (settings.useLocation) {
            try {
                service?.location
            } catch (_: RemoteException) {
                null
            }
        } else null
        val location = deviceLocation ?: TimeZoneUtils.getLatLngForTimezone(applicationContext, TimeZone.getDefault())
        if (location != null) {
            val now = Calendar.getInstance()
            val start = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val today = SunTimes.compute().on(start).at(location.latitude, location.longitude).execute()
            start.add(Calendar.DAY_OF_YEAR, 1)
            val tomorrow = SunTimes.compute().on(start).at(location.latitude, location.longitude).execute()
            fun upcoming(current: Date?, next: Date?) = current?.takeIf { it.after(now.time) } ?: next
            upcoming(today.rise, tomorrow.rise)?.let {
                schedule(workManager, TAG_SUNRISE, false, it.time - now.timeInMillis)
            }
            upcoming(today.set, tomorrow.set)?.let {
                schedule(workManager, TAG_SUNSET, true, it.time - now.timeInMillis)
            }
        }
        Result.success()
    }

    private fun schedule(workManager: WorkManager, tag: String, dark: Boolean, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<DarqSunriseSunsetWork>()
            .setInputData(workDataOf(KEY_NIGHT to dark))
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(tag)
            .build()
        val policy = if (tag in tags) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(tag, policy, request)
    }
}
