package network.columba.app.service.tak

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What files this handset holds for TAK, and a way to let them go.
 *
 * Only the sender keeps a full file: no board and no propagation node holds
 * one, so a file here is either one this operator offered -- still fetchable
 * by teammates it went to -- or one they received. Files are kept until
 * deleted here; the page says so. Promoted to the Reticulum ATAK plugin in
 * PR F.
 */
@Singleton
class TakFileRetention
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val store = TakFileStore(File(context.noBackupFilesDir, "tak_files"))

        private val _held = MutableStateFlow<List<TakFileStore.Held>>(emptyList())
        val held: StateFlow<List<TakFileStore.Held>> = _held.asStateFlow()

        /** Read the store again. Cheap: a directory listing. */
        fun refresh() {
            _held.value = store.list()
        }

        fun delete(hash: String) {
            store.delete(hash)
            refresh()
        }

        fun deleteAll() {
            store.list().forEach { store.delete(it.hash) }
            refresh()
        }
    }
