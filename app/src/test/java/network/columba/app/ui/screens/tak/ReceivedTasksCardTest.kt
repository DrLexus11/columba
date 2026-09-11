package network.columba.app.ui.screens.tak

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.service.TaskCodec
import network.columba.app.service.TaskManager
import network.columba.app.service.TaskStore
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The task list must not grow the page without bound.
 *
 * Every task used to render in full, so a handful of them pushed everything
 * below off the screen. These assert the shape that replaced it: a row per
 * task, opened on demand, with anything awaiting a decision open already.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReceivedTasksCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val authority = "ab".repeat(64)
    private val now = 1_700_000_000L

    private fun task(
        id: String,
        status: Int,
        instruction: String = "Proceed to the rally point and hold",
        expires: Long = now + 3600,
    ) = TaskStore.Row(
        owner = "owner",
        message =
            TaskCodec.Message(
                kind = 1,
                issuer = "command",
                recipient = "owner",
                taskId = id,
                issued = now - 60,
                expires = expires,
                latE7 = 409_549_000,
                lonE7 = 290_934_000,
                instruction = instruction,
                status = status,
            ),
        publicKey = authority,
        status = status,
        attempts = 0,
        lastAttempt = 0,
    )

    private fun state(vararg tasks: TaskStore.Row) =
        TaskManager.State(
            owner = "owner",
            publicKey = "cd".repeat(64),
            authority = authority,
            tasks = tasks.toList(),
            message = "Ready",
            now = now,
        )

    private fun show(state: TaskManager.State) {
        composeRule.setContent {
            ReceivedTasksCard(
                isExpanded = true,
                onExpandedChange = {},
                state = state,
                onDecide = { _, _ -> },
            )
        }
    }

    @Test
    fun `a decided task shows as a row without its detail`() {
        // The collapsed row carries the status and the instruction. The
        // coordinates, the expiry and the buttons are what made the old card
        // unbounded, and they stay hidden until asked for.
        show(state(task("t1", TaskCodec.ACCEPTED)))

        composeRule.onNodeWithText("Accepted").assertIsDisplayed()
        composeRule.onNodeWithText("Proceed to the rally point and hold").assertIsDisplayed()
        composeRule.onAllNodesWithText("Open point in map").assertCountEquals(0)
    }

    @Test
    fun `opening a row reveals the detail and the actions`() {
        show(state(task("t1", TaskCodec.ACCEPTED)))

        composeRule.onNodeWithText("Accepted").performClick()

        composeRule.onNodeWithText("Open point in map").assertIsDisplayed()
        composeRule.onNodeWithText("40.9549, 29.0934").assertIsDisplayed()
    }

    @Test
    fun `a task awaiting a decision is open already`() {
        // The only reason to reach this screen in a hurry is a task waiting on
        // you, so it must not take a tap to see what it says.
        show(state(task("t1", TaskCodec.RECEIVED)))

        composeRule.onNodeWithText("Awaiting your decision").assertIsDisplayed()
        composeRule.onNodeWithText("Accept").assertIsDisplayed()
    }

    @Test
    fun `an expired task is not open and cannot be accepted`() {
        show(state(task("t1", TaskCodec.RECEIVED, expires = now - 1)))

        composeRule.onNodeWithText("Expired").assertIsDisplayed()
        composeRule.onAllNodesWithText("Accept").assertCountEquals(0)
    }

    @Test
    fun `many decided tasks stay collapsed`() {
        // The regression this guards: five tasks used to render five full task
        // blocks. Five rows is five rows.
        val many = (1..5).map { task("t$it", TaskCodec.ACCEPTED, "Instruction $it") }
        show(state(*many.toTypedArray()))

        composeRule.onAllNodesWithText("Accepted").assertCountEquals(5)
        composeRule.onAllNodesWithText("Open point in map").assertCountEquals(0)
    }

    @Test
    fun `an empty list says so rather than showing nothing`() {
        show(state())

        composeRule.onNodeWithText("No verified tasks received.").assertIsDisplayed()
    }

    @Test
    fun `a task from an untrusted authority says so when opened`() {
        val foreign = task("t1", TaskCodec.RECEIVED).copy(publicKey = "ff".repeat(64))
        show(state(foreign))

        composeRule.onNodeWithText("This task's authority is no longer trusted.")
            .assertIsDisplayed()
    }
}
