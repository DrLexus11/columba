package network.columba.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.columba.app.di.ApplicationScope
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val core: RnsCore,
        private val lxmf: RnsLxmf,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        data class State(
            val owner: String = "",
            val publicKey: String = "",
            val authority: String = "",
            val tasks: List<TaskStore.Row> = emptyList(),
            val message: String = "Waiting for the mesh",
            val now: Long = 0,
        )

        private val store = TaskStore(context)
        private val preferences = context.getSharedPreferences("tak_task_authorities", Context.MODE_PRIVATE)
        private val mutableState = MutableStateFlow(State())
        val state = mutableState.asStateFlow()
        private var job: Job? = null

        fun start() {
            job?.cancel()
            job =
                scope.launch(Dispatchers.IO) {
                    try {
                        runReceiver()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Log.e("TaskManager", "Task receiver stopped", error)
                        mutableState.value = mutableState.value.copy(message = "Task receiver unavailable; reconnect to retry")
                    }
                }
        }

        fun stop() {
            job?.cancel()
            job = null
        }

        fun setAuthority(key: String) {
            val current = state.value
            if (current.owner.isEmpty()) return
            val normalized = key.trim().lowercase()
            require(normalized.isEmpty() || TaskCodec.unhex(normalized).size == 64)
            check(preferences.edit().putString("authority.${current.owner}", normalized).commit())
            mutableState.value = current.copy(authority = normalized)
        }

        fun decide(
            row: TaskStore.Row,
            status: Int,
        ) {
            scope.launch(Dispatchers.IO) {
                val current = state.value
                if (row.owner != current.owner || row.publicKey != current.authority) return@launch
                store.decide(row.owner, row.message.issuer, row.message.taskId, status, System.currentTimeMillis() / 1000)
                refresh(row.owner)
            }
        }

        private fun refresh(owner: String) {
            if (state.value.owner != owner) return
            mutableState.value =
                state.value.copy(
                    tasks = store.rows(owner),
                    now = System.currentTimeMillis() / 1000,
                    authority = preferences.getString("authority.$owner", "").orEmpty(),
                )
        }

        private suspend fun runReceiver() =
            coroutineScope {
                val local = lxmf.getLxmfIdentity().getOrThrow()
                val destination =
                    core
                        .createDestination(
                            local,
                            Direction.IN,
                            DestinationType.SINGLE,
                            TaskCodec.APP,
                            TaskCodec.ASPECTS,
                        ).getOrThrow()
                val owner = destination.hexHash
                Log.i("TaskManager", "Receiver $owner public key ${TaskCodec.hex(local.publicKey)}")
                mutableState.value =
                    State(
                        owner = owner,
                        publicKey = TaskCodec.hex(local.publicKey),
                        authority = preferences.getString("authority.$owner", "").orEmpty(),
                        message = "Receiving is off until an authority is trusted",
                    )
                launch(start = CoroutineStart.UNDISPATCHED) {
                    core.observePackets().collect { packet ->
                        if (!packet.destination.hash.contentEquals(destination.hash)) return@collect
                        val key = preferences.getString("authority.$owner", "").orEmpty()
                        if (key.isEmpty()) return@collect
                        try {
                            val now = System.currentTimeMillis() / 1000
                            val task = TaskCodec.verify(packet.data, TaskCodec.unhex(key), owner, now)
                            require(task.kind == TaskCodec.GOTO)
                            if (store.receive(owner, task, packet.data, key, now)) notifyTask(task)
                            refresh(owner)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Log.w("TaskManager", "Rejected task: ${error.javaClass.simpleName}")
                        }
                    }
                }
                var lastAnnounce = 0L
                while (true) {
                    val now = System.currentTimeMillis() / 1000
                    val key = preferences.getString("authority.$owner", "").orEmpty()
                    if (key.isNotEmpty()) {
                        if (now - lastAnnounce >= 300) {
                            core.announceDestination(destination).getOrThrow()
                            lastAnnounce = now
                        }
                        for (row in store.rows(owner)) {
                            if (row.publicKey != key || row.message.expires <= now) continue
                            if (row.attempts < 3 && now - row.lastAttempt >= 60) {
                                sendStatus(local, row, now)
                            }
                        }
                        mutableState.value = state.value.copy(message = "Listening for verified tasks")
                    } else {
                        lastAnnounce = 0
                        mutableState.value = state.value.copy(message = "Receiving is off until an authority is trusted")
                    }
                    refresh(owner)
                    delay(1000)
                }
            }

        private suspend fun sendStatus(
            local: Identity,
            row: TaskStore.Row,
            now: Long,
        ) {
            if (!store.markAttempt(row, now)) return
            try {
                val key = TaskCodec.unhex(row.publicKey)
                val native =
                    network.reticulum.identity.Identity
                        .fromPublicKey(key)
                val remote = Identity(native.hash, key, null)
                val destination =
                    core
                        .createDestination(
                            remote,
                            Direction.OUT,
                            DestinationType.SINGLE,
                            TaskCodec.APP,
                            TaskCodec.ASPECTS,
                        ).getOrThrow()
                if (!core.hasPath(destination.hash)) {
                    core.requestPath(destination.hash)
                    return
                }
                val message =
                    TaskCodec.Message(
                        TaskCodec.STATUS,
                        TaskCodec.hex(local.hash),
                        destination.hexHash,
                        row.message.taskId,
                        maxOf(now, row.message.issued),
                        row.message.expires,
                        status = row.status,
                    )
                val body = TaskCodec.body(message)
                val signature = core.signWithIdentity(TaskCodec.DOMAIN + body) ?: error("No signing identity")
                // Detect a backend identity switch before any response leaves the phone.
                require(
                    network.reticulum.identity.Identity
                        .fromPublicKey(local.publicKey)
                        .validate(signature, TaskCodec.DOMAIN + body),
                )
                core.sendPacket(destination, body + signature).getOrThrow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("TaskManager", "Task acknowledgment attempt failed", error)
            }
        }

        private fun notifyTask(task: TaskCodec.Message) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                notifications.createNotificationChannel(NotificationChannel("tak_tasks", "TAK tasks", NotificationManager.IMPORTANCE_HIGH))
            }
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification =
                NotificationCompat
                    .Builder(context, "tak_tasks")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("New verified task")
                    .setContentText("Review in Settings → TAK tasks")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
            try {
                notifications.notify(task.taskId.hashCode(), notification)
            } catch (_: SecurityException) {
                Log.i("TaskManager", "Notification permission absent; task remains in inbox")
            }
        }
    }
