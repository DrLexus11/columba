# Eridanus inside Columba

Eridanus -- RRC chatrooms on Reticulum, by Columba's original author -- merges
into this Columba so team rooms are usable here and from ATAK. Decided
2026-10-02; the reasons and the order of work across repositories are in the
firmware repository's `docs/TAKDeliveryPlan.md` ("Eridanus merges into
Columba"). Upstream will not do this (torlando-tech/columba #1083: Eridanus is
separate by design), so this is our divergence and stays one.

## The shape: co-located

Eridanus's code comes in nearly unchanged and runs **in its own process as a
client of Columba's own Reticulum**, through Columba's shared instance (TCP
37428), on Eridanus's Kotlin backend (reticulum-kt), **under Columba's
identity**. One APK, one Reticulum host, one identity.

Why not fold it into Columba's Reticulum process: that rewrites Eridanus's
plumbing onto Columba's internals, and every later Eridanus fix would then be
ported by hand. Co-located keeps its files and layout, so its fixes still
apply. The cost is a second, light Reticulum client in memory; folding in stays
possible later if that cost is ever measured to matter.

## How the code arrives, and how it is updated

`eridanus/` is a `git subtree` of `DrLexus11/eridanus` (our fork), squashed:
Columba's history stays readable and Eridanus's full history stays in the fork.

To take a later Eridanus fix -- only DrLexus11 remotes, ever:

1. Sync the fork from its original on GitHub (`gh repo sync DrLexus11/eridanus`).
2. `git subtree pull --prefix=eridanus --squash https://github.com/DrLexus11/eridanus main`
   on a branch, build, test, pull request.

Changes we need inside `eridanus/` are kept few and small, so those pulls stay
clean; adaptation lives in Columba-side modules that point at Eridanus's files.

## Steps

Each is its own pull request into Columba's `main`, tested on the bench phones.

1. **Import** -- `eridanus/` as a subtree. **Done** (this branch).
2. **Build Eridanus's modules inside Columba's build.** `settings.gradle.kts`
   includes `eridanus-rns-api` and `eridanus-rns-backend-kt` by `projectDir`.
   Version seams found on 2026-10-02: same Android Gradle plugin (9.1.0) and
   Kotlin (2.3.20); reticulum-kt v0.0.19 there, v0.0.22 here -- unify on
   Columba's; minSdk 26 there, 24 here -- try 24 with Columba's desugaring
   first, raise Columba to 26 only if that fails. Eridanus's own tests run in
   Columba's build.
3. **Eridanus's app code as a library** (`:eridanus-rrc`), pointing its
   sources at `eridanus/app/src/main` minus what only an application needs
   (`MainActivity`, `EridanusApp`, the launcher entry). Its service runs in
   process `:rrc`; Eridanus uses manual wiring, not Hilt, and keeps it.
4. **Wire it to Columba.** The Reticulum client points at Columba's shared
   instance, and Columba turns sharing on when rooms are in use. The identity
   is Columba's active identity, handed over inside the APK -- never exported
   to a file.
5. **Rooms in Columba's UI** -- Eridanus's screens as a destination in
   Columba's navigation.
6. **Rooms for ATAK** -- the mesh interface gains rooms (version 2 of
   reticulum-atak's `docs/ColumbaInterface.md`), and ATAK team chat moves onto
   hub rooms hosted by the boards, so a member out of range gets the backlog.
   The firmware's `RRCProtocol` and Eridanus's `RrcCodec` are pinned against
   each other by a shared fixture, as `tak_native_v1.json` pins TAK.

## Licences

Both are MPL-2.0, which applies file by file, so Eridanus's files keep their
notices and `eridanus/LICENSE` and `eridanus/NOTICE` stay where they are. The
Kotlin backend is used, so the merged build does not pull in Python RNS for
rooms; Columba's own Python backend, where built, already carries the
Reticulum License's terms.
