package pt.caixa6

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MailRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val app = applicationContext as Caixa6App
        val latch = CountDownLatch(1)

        app.refreshSapoFromWorker {
            latch.countDown()
        }

        latch.await(75, TimeUnit.SECONDS)
        return Result.success()
    }
}
