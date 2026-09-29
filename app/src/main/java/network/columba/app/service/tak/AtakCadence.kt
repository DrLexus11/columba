package network.columba.app.service.tak

/**
 * Whether the ATAK beside this endpoint is reporting its own position.
 *
 * Columba reports for this handset only while ATAK does not, so the question
 * is how long a silence has to last before it means "not reporting". A fixed
 * two minutes was wrong both ways round. ATAK's stationary rate is about three
 * minutes, so every three minutes Columba stepped in, ATAK reported a minute
 * later, and both went on the air -- measured on the Nexus 6P overnight
 * 2026-09-22, alternating all night. And a shorter fixed window cannot tell an
 * ATAK with no GPS fix, which never reports at all, from one between reports.
 *
 * So the window is learned: half again the gap ATAK last left between two of
 * its own reports, never shorter than [MIN_QUIET_MS] and never longer than
 * [MAX_QUIET_MS]. A moving ATAK reporting every few seconds keeps the minimum;
 * a stationary one earns room for its own cadence; one that has never reported
 * gets the minimum from the moment it connected.
 */
class AtakCadence(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        /** Two report floors: after this, a receiver would grey the track out. */
        const val MIN_QUIET_MS = 2 * CotPosition.DEFAULT_INTERVAL_MS

        /** However slow ATAK has been, this long silent is not reporting. */
        const val MAX_QUIET_MS = 10L * 60 * 1000

        private const val MINUTE_MS = 60_000L
    }

    @Volatile private var connectedAt = 0L

    @Volatile private var lastReport = 0L

    @Volatile private var lastGap = 0L

    /** ATAK connected; it gets the minimum window before it must report. */
    fun connected() {
        connectedAt = clock()
    }

    /** ATAK reported its own position. */
    fun reported() {
        val now = clock()
        if (lastReport > 0) lastGap = now - lastReport
        lastReport = now
    }

    /**
     * The interval to state in a report ATAK made, in whole minutes; 0 for the
     * receiver's default.
     *
     * ATAK with no GPS fix reports every several minutes, and a receiver draws
     * an unstated report as current for two minutes: NEXUS went grey on the
     * deck between every pair of its own reports (2026-09-27). Stating the gap
     * ATAK actually leaves keeps the track current for two of them, as a
     * Columba-made report already does. Unstated while ATAK reports at least
     * once a minute, and never more than [MAX_QUIET_MS], after which this
     * handset reports in ATAK's place anyway.
     */
    fun statedIntervalMinutes(): Int =
        if (lastGap <= CotPosition.DEFAULT_INTERVAL_MS) {
            0
        } else {
            ((minOf(lastGap, MAX_QUIET_MS) + MINUTE_MS - 1) / MINUTE_MS).toInt()
        }

    /** How long a silence may last now before it means ATAK is not reporting. */
    fun quietWindowMs(): Long = (lastGap * 3 / 2).coerceIn(MIN_QUIET_MS, MAX_QUIET_MS)

    /** Whether ATAK, while [connected], has been heard from within its window. */
    fun isReporting(connected: Boolean): Boolean =
        connected && clock() - maxOf(lastReport, connectedAt) < quietWindowMs()
}
