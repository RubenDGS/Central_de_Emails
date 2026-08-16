package pt.caixa6

import android.app.Service
import android.content.Intent
import android.os.IBinder

/*
 * Mantido apenas para não deixar referências antigas no projeto.
 * Não é declarado no AndroidManifest e não é iniciado.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
