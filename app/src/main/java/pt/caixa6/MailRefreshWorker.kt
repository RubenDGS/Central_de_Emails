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
        val app =
            applicationContext as Caixa6App

        /*
         * SAPO: até ~70 s para percorrer as cinco contas.
         */
        val sapoLatch =
            CountDownLatch(1)

        app.refreshAllSapoForWorker {
            sapoLatch.countDown()
        }

        sapoLatch.await(
            75,
            TimeUnit.SECONDS
        )

        /*
         * Gmail: autorização silenciosa + leitura do contador INBOX.
         */
        val gmailLatch =
            CountDownLatch(1)

        app.refreshGmailForWorker {
            gmailLatch.countDown()
        }

        gmailLatch.await(
            25,
            TimeUnit.SECONDS
        )

        return Result.success()
    }
}
