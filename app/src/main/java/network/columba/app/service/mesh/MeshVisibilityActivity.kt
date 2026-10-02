package network.columba.app.service.mesh

import android.app.Activity
import android.os.Bundle

/**
 * Makes Columba visible to ATAK, so ATAK can bind [MeshService]. Does nothing.
 *
 * Since Android 11 an app sees only the packages its manifest asks about, and
 * ATAK's asks about three kinds: plugins, text-to-speech engines, and apps that
 * open a `content://` file of any type. Columba was none of them, so a bind
 * from ATAK found no such package. This activity opens one private type --
 * `application/vnd.network.columba.mesh` -- which no other app produces, so it
 * never appears in an "open with" list for anybody's files, and puts Columba
 * in ATAK's view. Opened anyway, it closes at once.
 */
class MeshVisibilityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
