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
