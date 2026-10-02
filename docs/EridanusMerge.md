# Eridanus inside Columba

Eridanus -- RRC chatrooms on Reticulum, by Columba's original author -- merges
into this Columba so team rooms are usable here and from ATAK. Decided
2026-10-02; the reasons and the order of work across repositories are in the
firmware repository's `docs/TAKDeliveryPlan.md` ("Eridanus merges into
Columba"). Upstream will not do this (torlando-tech/columba #1083: Eridanus is
separate by design), so this is our divergence and stays one.

## The shape: co-located

Eridanus's code comes in nearly unchanged and runs **as a client of Columba's
own Reticulum**, through Columba's shared instance (TCP 37428), on Eridanus's
Kotlin backend (reticulum-kt), **under Columba's identity**. One APK, one
Reticulum host, one identity. The client lives in Columba's app process, where
Eridanus's screens are: Eridanus has no service of its own -- its backend lives
wherever its view model runs -- while Columba's Reticulum stays in
`:reticulum`. (A separate `:rrc` process, as first written, would have started
a second copy of Columba's whole Application in it.)

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

**Patches inside `eridanus/`** -- carry each through every subtree pull:

1. `RnsBackendHost` (step 3): a three-line interface for "the Application that
   owns the Reticulum backend". `EridanusApp` implements it and the view model
   casts to it, so Columba's Application can host the backend too. Without it
   the view model casts to `EridanusApp` and crashes inside Columba.

## Steps

Each is its own pull request into Columba's `main`, tested on the bench phones.

1. **Import** -- `eridanus/` as a subtree. **Done** (this branch).
2. **Build Eridanus's modules inside Columba's build.** **Done.**
   `settings.gradle.kts` includes `eridanus-rns-api` and
   `eridanus-rns-backend-kt` by `projectDir`. Same Android Gradle plugin and
   Kotlin; reticulum-kt already v0.0.22 on both sides after syncing the fork.
   Their minSdk 26 is lowered to 24 from the root build (`finalizeDsl`, after
   their own scripts), and lint's NewApi check passes at 24. Columba's ktlint
   and detekt gates skip `eridanus-*` modules: imported code, its author's
   conventions.
3. **Eridanus's app code as a library** (`:eridanus-rrc`). **Done.** The
   module compiles `eridanus/app/src/main` and the Kotlin-backend provider in
   Eridanus's own namespace, with Columba's versions of Compose, Room,
   coroutines and the rest (one of each in the APK); its manifest declares no
   application and no launcher. `MainActivity` and `EridanusApp` compile but
   are not declared. The Kotlin flavor's resources are left out (they only
   rename the app, and collide with main's `app_name` in one source set).
   Eridanus's own unit tests -- 91 -- run and pass in Columba's build, with
   its `isReturnDefaultValues` test option. Eridanus keeps its manual wiring,
   not Hilt.
4. **Wire it to Columba.** `ColumbaApplication` implements `RnsBackendHost`.
   The Reticulum client points at Columba's shared instance, and Columba turns
   sharing on when rooms are in use. The identity is Columba's active identity,
   handed over inside the APK -- never exported to a file; Eridanus's own
   `IdentityStore` is the seam. **Open:** the Kotlin backend runs rns-android's
   own `ReticulumService`, a foreground service with its own notification --
   inside Columba that would be a second persistent notification beside
   Columba's. Decide whether Columba's notification can carry it, or the
   client can run without that service, when this step is built.
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
