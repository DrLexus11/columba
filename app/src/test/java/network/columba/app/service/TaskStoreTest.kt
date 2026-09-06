package network.columba.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val task =
        TaskCodec.Message(
            TaskCodec.GOTO,
            "11".repeat(16),
            "22".repeat(16),
            "33".repeat(16),
            1000,
            1900,
            1,
            2,
            "Go here",
        )
    private val owner = task.recipient
    private lateinit var store: TaskStore

    @Before
    fun setup() {
        context.deleteDatabase("tak_tasks.db")
        store = TaskStore(context)
    }

    @After
    fun close() {
        store.close()
        context.deleteDatabase("tak_tasks.db")
    }

    @Test
    fun `replay after restart cannot create a task or reset a decision`() {
        assertTrue(store.receive(owner, task, byteArrayOf(1), "key", 1000))
        assertTrue(store.decide(owner, task.issuer, task.taskId, TaskCodec.ACCEPTED, 1010))
        assertFalse(store.decide(owner, task.issuer, task.taskId, TaskCodec.DECLINED, 1011))
        store.close()
        store = TaskStore(context)
        assertFalse(store.receive(owner, task, byteArrayOf(1), "key", 1020))
        assertEquals(1, store.rows(owner).size)
        assertEquals(TaskCodec.ACCEPTED, store.rows(owner).single().status)
        assertTrue(runCatching { store.receive(owner, task, byteArrayOf(2), "key", 1020) }.isFailure)
    }

    @Test
    fun `identity isolation expiry and ack retry persistence`() {
        store.receive(owner, task, byteArrayOf(1), "key", 1000)
        assertTrue(store.rows("other").isEmpty())
        assertFalse(store.decide("other", task.issuer, task.taskId, TaskCodec.ACCEPTED, 1010))
        val row = store.rows(owner).single()
        assertTrue(store.markAttempt(row, 1010))
        assertFalse(store.markAttempt(row, 1010))
        store.close()
        store = TaskStore(context)
        assertEquals(1, store.rows(owner).single().attempts)
        assertFalse(store.decide(owner, task.issuer, task.taskId, TaskCodec.ACCEPTED, 1900))
    }
}
