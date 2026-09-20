package network.columba.app.service.tak

import android.util.Log
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.RnsCore

/**
 * Announces this node's LXMF inbox when a peer has just appeared.
 *
 * A peer that has only now arrived holds a path to our TAK node and none to
 * our inbox, and those carry different things: markers and positions ride the
 * node destination, chat rides the inbox. So a team sees each other on the map
 * while the first messages go nowhere and then, once an announce finally
 * lands, messaging becomes reliable. Reported from the field 2026-09-20 as a
 * cold start.
 *
 * **The app's own auto-announce cannot cover this.** It runs on a one- to
 * twelve-hour interval, randomised, and can be switched off entirely --
 * sensible for a personal messenger reached over the internet, far too slow
 * for a node on a mesh whose peers come and go.
 */
class InboxAnnouncer(
    private val identityRepository: IdentityRepository,
    private val rnsCore: RnsCore,
    private val floorMs: Long = FLOOR_MS,
) {
    companion object {
        private const val TAG = "InboxAnnouncer"

        /**
         * The least time between two announces from this path.
         *
         * One a minute at worst, and bounded in practice by how often peers
         * actually announce. Cheap against the alternative, which is a team
         * that can see each other and cannot message each other.
         */
        const val FLOOR_MS = 60_000L
    }

    private var lastAnnounce = 0L

    /**
     * Announce if the floor has passed. Returns true if one went out.
     *
     * The display name is the identity's own, never the TAK callsign --
     * announcing under a different name would rename this operator in every
     * contact list on the network.
     */
    suspend fun announceIfDue(now: Long): Boolean {
        if (lastAnnounce != 0L && now - lastAnnounce < floorMs) return false
        val displayName = identityRepository.getActiveIdentitySync()?.displayName ?: return false
        lastAnnounce = now
        rnsCore.triggerAutoAnnounce(displayName)
            .onFailure { Log.w(TAG, "Inbox announce failed: ${it.message}") }
        return true
    }
}
