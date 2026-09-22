# Backlog

Review findings that are real but too large to fix inside the pull request that
surfaced them. Each entry says what is wrong, what it costs today, and what a
fix would have to touch — so picking one up does not mean re-deriving it.

---

## Endpoint-owned destinations are never released

**Raised:** 2026-09-11, review of `feature/tak-native-endpoint`
**Severity:** leak with a security edge — retired fleet keys stay loaded
**Files:** `app/src/main/java/network/columba/app/service/tak/CotEndpointManager.kt`,
`rns-api/.../RnsCore.kt`, `rns-api/.../util/AppDestinationRegistry.kt`,
both backends, `rns-ipc` AIDL

### What happens

`CotEndpointManager.start()` collects the team and fleet-secret settings with
`collectLatest`, so changing either cancels `runEndpoint`. Cancellation closes
the listener and every ATAK client — but the IN and OUT GROUP destinations that
`serve()` created are never released. They stay registered, and their symmetric
keys stay in `AppDestinationRegistry.groupKeys`.

Two consequences, both invisible from the UI:

1. **The old team is still joined.** The IN destination remains registered with
   its packet callback attached, so the phone keeps receiving and decrypting
   traffic for a team the operator has left. The card shows only the new team.
2. **Every historical group comes back on restart.** `restoreAppDestinations`
   replays `appDestinations.registrations()` and re-derives each group with
   `groupKeyFor`. A phone that has been switched between four teams rejoins all
   four the next time the backend restarts, holding four fleet keys in memory.

Switching teams is the ordinary way an exercise is re-tasked, so this is not a
rare path. It is also the one case where the key material of a team the operator
has deliberately left is the thing being retained.

### Why it was not fixed in that PR

There is no way to release an app destination. `RnsCore` has
`createDestination` / `createGroupDestination` and no inverse, and
`AppDestinationRegistry` has `remember` with no `forget`. The plumbing exists
only inside the backends: `Transport.deregisterDestination` on the native side,
`Transport.deregister_destination` via Chaquopy on the Python side — both
currently reachable only from telephony's own teardown.

A fix therefore adds API surface across every layer, which is a change of a
different size and risk from the rest of that review.

### What a fix has to cover

- A `RnsCore` method to release a destination — name it for intent
  (`releaseDestination`) rather than for the transport call it makes.
- `AppDestinationRegistry.forget(destination)`, clearing the `groupKeys` entry
  as well as the registration, so a restart does not resurrect it. **Zero the
  key bytes** rather than only dropping the reference.
- Both backends: deregister from Transport and detach the packet callback, so a
  cancelled endpoint's callback cannot fire against a dead scope.
- The IPC seam: AIDL method, `ClientRnsCore`, `ServerRnsCore`, `BoundRnsCore`.
- `CotEndpointManager.serve()`: release `inbound`, `outbound` and `node` in the
  `finally` that already runs under `NonCancellable`, next to
  `closeAllClients()`.
- Tests: a team switch leaves exactly one group registered; a backend restart
  after several switches restores only the current team.

### Worth deciding at the same time

Whether `releaseDestination` should be idempotent — the endpoint's `finally`
can run after a backend restart has already discarded the registration, and
throwing there would surface as a spurious teardown failure.

---

## Fragments from a not-yet-announced sender are refused, not held

**Raised:** 2026-09-22, review of `feature/tak-tier3-fragments`
**Severity:** lost events during cold start — a reliability cost taken for a security fix
**Files:** `app/src/main/java/network/columba/app/service/tak/TakLxmfCarriage.kt`,
`PendingAttribution.kt`, `CotReassembler.kt`

### What happens

A fragment arriving over LXMF is now refused unless its proven signer is a
current team member. That closed an injection path: a fragment carries no sender
id of its own, and the event it reassembles into was drawn through the tier-2
fallback with no check at all, so any LXMF identity able to address the inbox
could inject a whole event.

The cost is a cold start. Membership is learned from announces, so for a while
after a peer joins -- or after this node restarts -- the peer is proved by LXMF
but not yet on the team table. A drawing or nine-line MEDEVAC sent in that window
is refused fragment by fragment, and **LXMF does not resend it**: the transport
already delivered it, so from LXMF's side nothing failed. The event is gone.

Chat and markers do not have this problem. They go to `PendingAttribution`, are
held with the proven signer, and are released to that exact node once it
announces. Fragments bypass that path.

### Why it was not fixed in that PR

Holding a fragment transfer is not the same shape as holding a single frame.
`PendingAttribution` holds complete frames keyed by the sender id each one
claims; a fragment has no sender id, and a *transfer* is only meaningful once
whole. Doing it properly means deciding where unverified partial state lives and
how it is bounded -- the review was about closing the injection, and improvising
the holding design inside that fix risked reopening it.

### What a fix has to cover

- Where an unverified transfer is held: inside `CotReassembler` behind a
  "provisional" flag, or reassembled first and the *whole* event held in
  `PendingAttribution` with its proven signer. The second reuses the release
  rule that already exists and is probably the smaller change.
- Bounds. An unverified sender must not be able to occupy the reassembler's
  sixteen transfer slots and starve real members -- the provisional pool needs
  its own, smaller cap.
- Release must stay keyed on the **whole** proven signer hash, never on anything
  the payload claims -- the same rule `PendingAttribution.releasable` applies.
- Tests: a transfer from a peer that announces mid-transfer is drawn; one from a
  peer that never announces is dropped when the hold expires; an unverified
  sender cannot exhaust the verified pool.
