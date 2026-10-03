// Columba's mesh interface, version 1, with the interfaces capability: the contract with the ATAK plugin.
// Defined in reticulum-atak's docs/ColumbaInterface.md; this file and the
// plugin's copy must stay identical, and a change to either is a version bump.
package network.columba.app.mesh;

import network.columba.app.mesh.IColumbaMeshWatcher;

interface IColumbaMesh {
    /** 1; -1 for a refused caller. The plugin refuses a version it does not know. */
    int version();

    /** The mesh as this phone sees it, as version 1 snapshot JSON. Empty for a refused caller. */
    String snapshot();

    /** Called with a fresh snapshot when it changes, at most once a second. */
    void watch(IColumbaMeshWatcher watcher);
    void unwatch(IColumbaMeshWatcher watcher);

    /** Commands, each returning a result code (ColumbaInterface.md). */
    int announce();

    // ---- appended (ColumbaInterface.md, "Interfaces"): the version 1 methods'
    // ---- transaction codes are unchanged, so an older client keeps working.

    /** Bitmask of what this service offers: 1 = interfaces. 0 from a Columba that predates it. */
    int capabilities();

    /** Switch one configured interface; Columba's guard refuses isolating switches. */
    int setInterfaceEnabled(long id, boolean enabled);

    /** Apply staged switches: restarts Columba's Reticulum where it cannot switch live. */
    int applyInterfaces();
}
