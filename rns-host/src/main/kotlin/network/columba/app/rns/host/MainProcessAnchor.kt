package network.columba.app.rns.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Lends this process's foreground priority to Columba's main process.
 *
 * Columba's only foreground service is [ReticulumService], here in `:reticulum`.
 * The main process -- the TAK endpoint ATAK connects to, the mesh interface the
 * ATAK plugin binds, and the rooms client -- has none of its own, and a client
 * does not inherit its service's priority. Measured on a Galaxy A54 on
 * 2026-10-02: the main process was at the foreground-service level anyway, but
 * by accident -- `:reticulum` binds Room's MultiInstanceInvalidationService,
 * which lives in the main process, to keep its database copy in sync. A change
 * to how the database is opened would remove that protection silently, and
 * then Android could reclaim the main process while ATAK is in front. This
 * makes the protection deliberate.
 *
 * The other direction works: a process serving a bound client is ranked at
 * least as high as that client. So this process binds a do-nothing service in
 * the main process, with BIND_IMPORTANT to carry the foreground level and
 * BIND_AUTO_CREATE so that a main process reclaimed anyway is started again --
 * and with it the TAK endpoint. One foreground service, one notification, both
 * processes protected.
 *
 * Bound by action within this package: rns-host does not depend on the app
 * module that declares the service.
 */
internal class MainProcessAnchor(private val context: Context) {
    companion object {
        private const val TAG = "MainProcessAnchor"
        const val ACTION = "network.columba.app.ANCHOR_MAIN_PROCESS"
    }

    private var bound = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "Main process anchored to :reticulum's foreground service")
            }

            // The binding stays: BIND_AUTO_CREATE has the system start the main
            // process again, and onServiceConnected follows when it is back.
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Main process went away; the binding brings it back")
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "Anchor binding died; rebinding")
                release()
                hold()
            }
        }

    fun hold() {
        if (bound) return
        val intent = Intent(ACTION).setPackage(context.packageName)
        bound =
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            } catch (e: SecurityException) {
                Log.e(TAG, "Anchor bind refused", e)
                false
            }
        if (!bound) {
            // Loud, in one line: the main process is unprotected and nothing
            // else will say so.
            Log.e(TAG, "Anchor service not found: the main process has no foreground protection")
            runCatching { context.unbindService(connection) }
        }
    }

    fun release() {
        if (!bound) return
        bound = false
        runCatching { context.unbindService(connection) }
    }
}
