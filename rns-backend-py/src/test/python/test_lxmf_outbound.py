"""The message behind a just-delivered one is not left for the next job cycle."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

from lxmf_outbound import process_outbound_twice  # noqa: E402


class Message:
    def __init__(self, name, delivered=False):
        self.name, self.delivered, self.sent = name, delivered, False


class SkippingRouter:
    """LXMF 1.1.0's pass, reduced to the part that skips."""

    def __init__(self, *messages):
        self.pending_outbound = list(messages)

    def process_outbound(self, sender=None):
        for message in self.pending_outbound:
            if message.delivered:
                self.pending_outbound.remove(message)      # skips the next one
            else:
                message.sent = True


class SecondPassTests(unittest.TestCase):
    def test_the_bug_is_what_the_logs_showed(self):
        request, part = Message("request", delivered=True), Message("part")
        SkippingRouter(request, part).process_outbound()
        self.assertFalse(part.sent, "skipped: waits for the next job cycle")

    def test_a_second_pass_sends_the_skipped_message(self):
        request, part = Message("request", delivered=True), Message("part")
        router = SkippingRouter(request, part)
        process_outbound_twice(router)
        router.process_outbound()
        self.assertTrue(part.sent)
        self.assertEqual(router.pending_outbound, [part])

    def test_installing_twice_does_not_make_four_passes(self):
        router = SkippingRouter()
        calls = []
        router.process_outbound = lambda sender=None: calls.append(sender)
        process_outbound_twice(router)
        process_outbound_twice(router)
        router.process_outbound()
        self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
