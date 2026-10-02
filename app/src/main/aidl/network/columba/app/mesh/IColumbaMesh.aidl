// Columba's mesh interface, version 1: the contract with the ATAK plugin.
// Defined in reticulum-atak's docs/ColumbaInterface.md; this file and the
// plugin's copy must stay identical, and a change to either is a version bump.
package network.columba.app.mesh;

import network.columba.app.mesh.IColumbaMeshWatcher;

interface IColumbaMesh {
    /** 1 for this version. The plugin refuses a version it does not know. */
    int version();

    /** The mesh as this phone sees it, as version 1 snapshot JSON. Empty for a refused caller. */
    String snapshot();

    /** Called with a fresh snapshot when it changes, at most once a second. */
    void watch(IColumbaMeshWatcher watcher);
    void unwatch(IColumbaMeshWatcher watcher);

    /** Commands, each returning a result code (ColumbaInterface.md). */
    int announce();
}
