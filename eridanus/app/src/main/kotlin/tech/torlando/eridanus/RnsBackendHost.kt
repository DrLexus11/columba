// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus

import tech.torlando.eridanus.rns.RnsBackend

/**
 * The Application that owns the Reticulum backend. EridanusApp is one; an app
 * that hosts Eridanus's screens (Columba) is another. The view model reaches the
 * backend through this rather than through EridanusApp itself.
 */
interface RnsBackendHost {
    val rnsBackend: RnsBackend
}
