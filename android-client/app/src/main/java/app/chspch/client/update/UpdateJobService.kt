package app.chspch.client.update

import android.app.DownloadManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class UpdateJobService : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(Dispatchers.IO).launch {
            var retry = false
            val updater = AppUpdater(this@UpdateJobService)
            val downloadCheck = params.jobId == AppUpdater.DOWNLOAD_JOB_ID
            try {
                if (downloadCheck) {
                    updater.pending()?.let { update ->
                        when (updater.downloadStatus()) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                updater.verifiedApk(update)
                                updater.notifyUpdate(update, ready = true)
                            }
                            DownloadManager.STATUS_FAILED -> updater.notifyUpdate(update, failed = true)
                            else -> retry = true
                        }
                    }
                } else updater.check()?.let { updater.notifyUpdate(it) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                retry = !downloadCheck
                if (downloadCheck) updater.pending()?.let { updater.notifyUpdate(it, failed = true) }
            }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return true }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val updater = AppUpdater(context)
        if (!updater.isOurDownload(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updater.pending()?.let { update -> updater.verifiedApk(update); updater.notifyUpdate(update, ready = true) }
            } catch (_: Exception) { /* The foreground dialog reports failures and permits a retry. */ }
            finally { result.finish() }
        }
    }
}
