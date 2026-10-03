package network.columba.app.service.mesh

/**
 * Whether switching an interface from ATAK would cut this phone off -- decided
 * here, in Columba, so no caller can bypass it (reticulum-atak OpenDecisions 1,
 * ColumbaInterface.md v2). Pure, so it is tested without Android.
 *
 * Two rules, on the resulting set: never switch off the interface that carries
 * the path to the command post, and never leave no interface online.
 */
object InterfaceGuard {
    data class Iface(
        val id: Long,
        val enabled: Boolean,
        val online: Boolean,
        val carriesCommandPost: Boolean,
    )

    enum class Verdict { OK, WOULD_ISOLATE, UNKNOWN_INTERFACE }

    /** One switch, as asked: [id] on or off. */
    fun check(
        ifaces: List<Iface>,
        id: Long,
        enable: Boolean,
    ): Verdict {
        val target = ifaces.find { it.id == id }
        // What would still carry traffic: enabled and actually online now. An
        // interface enabled but not yet up does not count -- it may never come up.
        val remaining = ifaces.count { it.id != id && it.enabled && it.online }
        return when {
            target == null -> Verdict.UNKNOWN_INTERFACE
            enable -> Verdict.OK
            target.carriesCommandPost || remaining == 0 -> Verdict.WOULD_ISOLATE
            else -> Verdict.OK
        }
    }

    /**
     * Applying staged switches (Python backend): the staged set is [Iface.enabled].
     * Refused if it switches off an interface carrying the command post's path,
     * or leaves no enabled interface that is online now.
     */
    fun checkApply(ifaces: List<Iface>): Verdict {
        if (ifaces.any { !it.enabled && it.carriesCommandPost }) return Verdict.WOULD_ISOLATE
        return if (ifaces.none { it.enabled && it.online }) Verdict.WOULD_ISOLATE else Verdict.OK
    }
}
