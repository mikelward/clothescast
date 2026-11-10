package app.clothescast.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.getSystemService
import app.clothescast.alarm.ScheduleRefreshReceiver
import app.clothescast.diag.DiagLog

/**
 * Auto Backup with one addition: after a restore, arm the delivery alarms.
 *
 * Android restores the data at install time, before the app has ever run, and
 * nothing else would start it until the user opens it or reboots — so a phone
 * set up from a backup would skip its morning forecast. `fullBackupOnly` keeps
 * the backup itself the plain Auto Backup that `data_extraction_rules.xml`
 * describes; this agent only adds [onRestoreFinished].
 *
 * The restore runs in a restricted process (the base `Application`, not
 * ClothesCastApplication), which Android kills once it's done. So rather than
 * arming here, it sets a one-shot alarm that delivers [ScheduleRefreshReceiver]'s
 * re-arm in a normal process shortly after.
 */
class ClothesCastBackupAgent : BackupAgent() {
    override fun onRestoreFinished() {
        try {
            scheduleRearmAfterRestore(this)
        } catch (t: Throwable) {
            DiagLog.e(TAG, t, "Couldn't schedule the post-restore re-arm; it waits for the next app start or boot")
        }
    }

    // Key/value backup is unused: fullBackupOnly routes everything through Auto Backup.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    companion object {
        private const val TAG = "BackupAgent"
        private const val REQUEST_CODE = 0x8E57
        private const val REARM_DELAY_MS = 10_000L

        /** Arms a one-shot, inexact alarm that runs [ScheduleRefreshReceiver] with [ACTION_RESTORED]. */
        internal fun scheduleRearmAfterRestore(context: Context) {
            val alarmManager: AlarmManager = context.getSystemService() ?: error("No AlarmManager")
            val intent = Intent(context, ScheduleRefreshReceiver::class.java).setAction(ScheduleRefreshReceiver.ACTION_RESTORED)
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + REARM_DELAY_MS, pending)
            DiagLog.i(TAG, "Restore finished; delivery alarms re-arm in %d s", REARM_DELAY_MS / 1000)
        }
    }
}
