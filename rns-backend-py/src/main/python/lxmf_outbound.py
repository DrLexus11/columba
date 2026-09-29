"""A second outbound pass, for the message LXMF's first pass skips.

`LXMRouter.process_outbound` (LXMF 1.1.0) removes delivered messages from
`pending_outbound` while iterating over it. Removing the current element of a
Python list mid-loop skips the next one, so the message queued right behind a
just-delivered one is not looked at until the router's next job cycle, up to
four seconds later.

Measured on the A54, 2026-09-27: every TAK file part follows a just-delivered
request, and each sat ~3 s between handle_outbound and "Outbound processing"
-- "Delivery has occurred for <e842...>, removing" at 15:49:56.107, the next
message processed at 15:49:59.181; the same again at 15:50:00.541 / 03.202.
Chat sent shortly after another message pays the same.

Not ours to fix upstream. Here, each pass is followed by a second: it picks up
what the first skipped, and leaves alone anything already sending or waiting
for its next attempt, so it is safe to run twice. The router's own lock still
serialises every pass.
"""


def process_outbound_twice(router):
    """Make `router.process_outbound` run a second pass after each call."""
    if getattr(router, "_columba_second_pass", False):
        return
    first_pass = router.process_outbound

    def process_outbound(sender=None):
        first_pass(sender)
        first_pass(sender)

    router.process_outbound = process_outbound
    router._columba_second_pass = True
