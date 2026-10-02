package network.columba.app.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * The main process's end of rns-host's MainProcessAnchor. It does nothing: being
 * bound by `:reticulum`'s foreground service, with BIND_IMPORTANT, is what keeps
 * this process -- the TAK endpoint, the mesh interface, the rooms client -- at
 * that service's priority instead of reclaimable.
 */
class MainProcessAnchorService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}
