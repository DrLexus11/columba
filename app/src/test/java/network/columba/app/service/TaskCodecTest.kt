package network.columba.app.service

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCodecTest {
    private val vector = JSONObject(javaClass.getResource("/task_v1.json")!!.readText())
    private val task = TaskCodec.unhex(vector.getString("task"))
    private val authority = TaskCodec.unhex(vector.getString("authority_public_key"))
    private val recipient = vector.getString("recipient")
    private val now = vector.getLong("now")

    @Test
    fun `Python signature and canonical bytes verify in Kotlin`() {
        val decoded = TaskCodec.verify(task, authority, recipient, now)
        assertEquals("Go to café", decoded.instruction)
        assertEquals(411234567, decoded.latE7)
        assertArrayEquals(task.copyOfRange(0, task.size - 64), TaskCodec.body(decoded))
        val ack =
            TaskCodec.verify(
                TaskCodec.unhex(vector.getString("ack")),
                TaskCodec.unhex(vector.getString("phone_public_key")),
                vector.getString("ack_recipient"),
                now + 10,
            )
        assertEquals(TaskCodec.ACCEPTED, ack.status)
    }

    @Test
    fun `changing any byte invalidates the task`() {
        for (index in task.indices) {
            val changed = task.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertTrue("byte $index", runCatching { TaskCodec.verify(changed, authority, recipient, now) }.isFailure)
        }
    }

    @Test
    fun `wrong recipient key expiry and future time are rejected`() {
        assertTrue(runCatching { TaskCodec.verify(task, authority, "00".repeat(16), now) }.isFailure)
        assertTrue(runCatching { TaskCodec.verify(task, authority, recipient, now - 31) }.isFailure)
        assertTrue(runCatching { TaskCodec.verify(task, authority, recipient, now + 900) }.isFailure)
        assertTrue(runCatching { TaskCodec.verify(task, ByteArray(64), recipient, now) }.isFailure)
    }

    @Test
    fun `text limits count bytes and reject control characters`() {
        val message = TaskCodec.verify(task, authority, recipient, now)
        assertEquals(TaskCodec.MAX_PACKET - 64, TaskCodec.body(message.copy(instruction = "é".repeat(32))).size)
        for (text in listOf("é".repeat(33), "", "line\nbreak", "\uD800")) {
            assertTrue(runCatching { TaskCodec.body(message.copy(instruction = text)) }.isFailure)
        }
    }
}
