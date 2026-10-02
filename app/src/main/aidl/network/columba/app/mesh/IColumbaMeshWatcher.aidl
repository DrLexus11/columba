// Columba's mesh interface, version 1 (see IColumbaMesh.aidl).
package network.columba.app.mesh;

interface IColumbaMeshWatcher {
    oneway void changed(String snapshot);
}
