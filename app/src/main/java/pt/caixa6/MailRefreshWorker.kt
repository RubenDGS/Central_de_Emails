package pt.caixa6

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MailRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val app = applicationContext as Caixa6App
        val latch = CountDownLatch(1)

        app.refreshAllSapoForWorker {
            latch.countDown()
        }

        latch.await(70, TimeUnit.SECONDS)
        return Result.success()
    }
}
