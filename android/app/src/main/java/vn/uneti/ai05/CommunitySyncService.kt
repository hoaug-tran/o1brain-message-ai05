package vn.uneti.ai05

import android.app.job.JobParameters
import android.app.job.JobService

class CommunitySyncService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CommunityClient.get(this).syncPending { success -> jobFinished(params, !success) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
