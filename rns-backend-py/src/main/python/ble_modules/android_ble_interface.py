#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
AndroidBLEInterface - Reticulum Interface for Android BLE
=========================================================

This module provides a Reticulum interface for Bluetooth Low Energy (BLE) on
Android devices. It leverages the `ble-reticulum` driver-based architecture
to reuse the core `BLEInterface` logic while plugging in an Android-specific
driver (`AndroidBLEDriver`).

The `AndroidBLEDriver` acts as a bridge to the `KotlinBLEBridge` via Chaquopy,
which in turn manages all native Android BLE operations.

This interface is automatically discovered and loaded by Reticulum if placed
in the `~/.reticulum/interfaces/` directory and configured in `config`.

This file is a thin wrapper that configures and initializes the generic
`BLEInterface` with the `AndroidBLEDriver`.

Author: Columba Project
License: MIT
"""

import RNS
import sys
import os

# When Reticulum loads this interface with exec(), we need to ensure the interfaces
# directory is in sys.path so imports work. The interfaces are in the app storage.
_storage_base = os.environ.get("HOME", "/data/user/0/com.lxmf.messenger/files")
_interfaces_dir = os.path.join(_storage_base, "reticulum", "interfaces")
if os.path.exists(_interfaces_dir) and _interfaces_dir not in sys.path:
    sys.path.insert(0, _interfaces_dir)

# Import the generic BLEInterface and the Android-specific driver
# Note: BLEInterface is deployed to the same interfaces directory as this file
from BLEInterface import BLEInterface
from drivers.android_ble_driver import AndroidBLEDriver

import threading
import time


# How long an announce sent with no BLE peer connected is held for one to come
# up, and how many are held. An announce is a node saying where it is; one that
# waits two minutes is still true, one that waits ten may no longer be.
HELD_ANNOUNCE_SECONDS = 120
MAX_HELD_ANNOUNCES = 16

# How long a peer's interface outlives its BLE link.
#
# The interface is what Reticulum's paths hang on: tear it down and every path
# learned through that peer goes with it, and nothing is known again until the
# peer announces. The parent keeps it for two seconds. Measured phone to phone
# on the bench 2026-09-24: the link dropped at the radio (HCI reason 0x08), the
# interface went two seconds later, and the peer was back under a new address
# 39 s after the drop -- to a new interface with no paths, so a file offer to
# its inbox found no path and exhausted its tries. Android rotates BLE
# addresses; a reconnect is a scan, a GATT connect and an identity handshake.
# Two minutes covers that with retries, and a peer that is really gone costs
# only an offline interface for that long.
PEER_GRACE_SECONDS = 120

# A peer met over BLE is told who this node is, on that link alone, at most
# this often. Nothing else announced when two phones met: each learned the
# other only at its next scheduled announce -- up to half an hour -- or when
# both operators pressed Announce (operator, 2026-09-26: a message went
# through only once *both* sides had announced by hand).
PEER_ANNOUNCE_MIN_SECONDS = 60
# Only destinations this node announced within this long are said again: one
# the app stopped announcing is not revived by a BLE connection.
OWN_ANNOUNCE_MAX_AGE_SECONDS = 6 * 60 * 60

_PACKET_TYPE_MASK = 0x03
_ANNOUNCE = 0x01
_HEADER_2 = 0x40
_CONTEXT_FLAG = 0x20
_DESTINATION_BYTES = 16
# An announce's data: public key, name hash, random blob, a ratchet when the
# context flag is set, the signature -- then the application's own data.
_ANNOUNCE_FIXED_BYTES = 64 + 10 + 10 + 64
_RATCHET_BYTES = 32


def announce_destination(data):
    """The destination an announce is for, or None if `data` is not one.

    Read straight off the Reticulum header: the packet type is the low two
    bits of the flags byte, and the destination hash follows the hops byte --
    after a transport id as well, for a HEADER_2 packet.
    """
    if not data or len(data) < 2 + _DESTINATION_BYTES:
        return None
    flags = data[0]
    if flags & _PACKET_TYPE_MASK != _ANNOUNCE:
        return None
    start = 2 + _DESTINATION_BYTES if flags & _HEADER_2 else 2
    if len(data) < start + _DESTINATION_BYTES:
        return None
    return bytes(data[start:start + _DESTINATION_BYTES])


def announce_app_data(data):
    """The application data an announce carries, or None if `data` is not one."""
    if announce_destination(data) is None:
        return None
    flags = data[0]
    start = 2 + (_DESTINATION_BYTES if flags & _HEADER_2 else 0) + _DESTINATION_BYTES + 1
    start += _ANNOUNCE_FIXED_BYTES + (_RATCHET_BYTES if flags & _CONTEXT_FLAG else 0)
    if len(data) < start:
        return None
    return bytes(data[start:])


class OwnAnnounces:
    """What this node last announced for each destination, and when.

    Kept so a newly met BLE peer can be sent a *fresh* announce with the same
    application data. Replaying the old packet would not do: a peer that heard
    it before over LoRa drops it as a duplicate and keeps the LoRa path.
    """

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._latest = {}   # destination -> (when, app data)

    def note(self, data):
        destination = announce_destination(data)
        if destination is None:
            return
        app_data = announce_app_data(data)
        with self._lock:
            self._latest[destination] = (self.clock(), app_data)

    def current(self, max_age=OWN_ANNOUNCE_MAX_AGE_SECONDS):
        now = self.clock()
        with self._lock:
            return [(destination, app_data) for destination, (when, app_data) in self._latest.items()
                    if now - when <= max_age]


class HeldAnnounces:
    """Announces handed to the interface while no peer could receive them.

    Measured on the bench 2026-09-21: after a Columba restart the node's
    announces went out "to 0 peer(s)" -- BLE had not reconnected yet -- and
    were silently lost, and nothing re-sent them once the board came back. A
    handset whose only way onto the mesh is one BLE board stayed unknown to
    its team until its next scheduled announce, up to half an hour away.

    Only the latest announce per destination is kept: a newer one supersedes
    the older, and replaying both would say the same thing twice.
    """

    def __init__(self, hold_seconds=HELD_ANNOUNCE_SECONDS,
                 max_held=MAX_HELD_ANNOUNCES, clock=time.monotonic):
        self.hold_seconds = hold_seconds
        self.max_held = max_held
        self.clock = clock
        self._lock = threading.Lock()
        self._held = {}   # destination -> (when, data)

    def hold(self, data):
        """Keep `data` if it is an announce. True if it was kept."""
        destination = announce_destination(data)
        if destination is None:
            return False
        with self._lock:
            self._held.pop(destination, None)
            self._held[destination] = (self.clock(), bytes(data))
            while len(self._held) > self.max_held:
                del self._held[next(iter(self._held))]
        return True

    def release(self):
        """Everything still worth sending, oldest first; the hold is emptied."""
        now = self.clock()
        with self._lock:
            held, self._held = self._held, {}
        return [data for when, data in held.values() if now - when <= self.hold_seconds]


class AndroidBLEInterface(BLEInterface):
    """
    Reticulum interface for Android BLE.

    This class inherits from the generic `BLEInterface` and uses the
    `AndroidBLEDriver` to provide Android-specific BLE functionality.
    All the complex logic for peer management, connection handling, and
    fragmentation is handled by the parent `BLEInterface`.
    """

    # Override driver class to use Android implementation
    driver_class = AndroidBLEDriver

    def __init__(self, owner, config=None):
        """
        Initialize the Android BLE interface.

        Args:
            owner: The Reticulum Transport instance that owns this interface.
            config: A dictionary containing configuration options.
        """
        # Before the parent constructor: it can start the driver, and anything
        # sent from then on may need holding.
        self._held_announces = HeldAnnounces()
        self._own_announces = OwnAnnounces()
        self._announced_to = {}     # peer identity hash -> when last told who we are

        # Call parent constructor - it will use our driver_class
        super().__init__(owner, config)

        # Keep a peer's interface, and so its paths, across a reconnect. The
        # identity cache must last at least as long, or a peer returning late
        # in the grace is not recognised as the one that left.
        self._pending_detach_grace_period = PEER_GRACE_SECONDS
        self._identity_cache_ttl = max(getattr(self, "_identity_cache_ttl", 0), PEER_GRACE_SECONDS)

        # Configure BLE power settings from config.
        # Safe to call after super().__init__(): the bridge (and its scanner/advertiser)
        # is created in KotlinBLEBridge's constructor, so the objects already exist
        # even though start() hasn't been called yet.
        if config and hasattr(self, 'driver') and self.driver is not None:
            power_preset = config.get("ble_power_preset", "balanced")
            self.driver.configure_power(
                preset=power_preset,
                discovery_interval_ms=int(config.get("ble_discovery_interval_ms", 5000)),
                discovery_interval_idle_ms=int(config.get("ble_discovery_interval_idle_ms", 30000)),
                scan_duration_ms=int(config.get("ble_scan_duration_ms", 10000)),
                advertising_refresh_interval_ms=int(config.get("ble_advertising_refresh_interval_ms", 60000)),
            )

        RNS.log(f"Android BLE Interface '{self.name}' initialized", RNS.LOG_INFO)

        # Log configuration details if attributes are available
        if hasattr(self, 'mode_str'):
            RNS.log(f"  Mode: {self.mode_str}", RNS.LOG_INFO)
        if hasattr(self, 'enable_central'):
            RNS.log(f"  Central: {'Enabled' if self.enable_central else 'Disabled'}", RNS.LOG_INFO)
        if hasattr(self, 'enable_peripheral'):
            RNS.log(f"  Peripheral: {'Enabled' if self.enable_peripheral else 'Disabled'}", RNS.LOG_INFO)
        RNS.log(f"  Max Peers: {self.max_peers}", RNS.LOG_INFO)

    def process_outgoing(self, data):
        """Send as the parent does, and hold an announce nobody could receive.

        With no peer connected the parent hands the packet to nobody and it is
        gone. An announce is kept so the first peer to connect still hears it.
        """
        self._own_announces.note(data)
        if self.online and not self._any_peer_online():
            if self._held_announces.hold(data):
                RNS.log(f"{self} no BLE peer yet; holding an announce until one connects",
                        RNS.LOG_DEBUG)
        super().process_outgoing(data)

    def _device_disconnected_callback(self, address):
        """As the parent does, then mark a peer awaiting detach offline.

        While the grace runs the interface stays -- that is the point -- but
        nothing can be delivered through it. Offline, an announce sent in the
        meantime is held and replayed when the peer is back, instead of being
        handed to a link that is not there.
        """
        super()._device_disconnected_callback(address)
        with self.peer_lock:
            for identity_hash in list(getattr(self, "_pending_detach", {})):
                peer_if = self.spawned_interfaces.get(identity_hash)
                if peer_if is not None:
                    peer_if.online = False

    def _device_connected_callback(self, address, peer_identity):
        super()._device_connected_callback(address, peer_identity)
        self._revive_reconnected()

    def _mtu_negotiated_callback(self, address, mtu):
        super()._mtu_negotiated_callback(address, mtu)
        self._revive_reconnected()

    def _process_pending_detaches(self):
        super()._process_pending_detaches()
        self._revive_reconnected()
        self._log_peers()

    def _log_peers(self):
        """Every kept peer and whether it carries traffic, once per cleanup tick.

        The only other view of this is the Interfaces screen, and only while it
        is open. tools/ble_link_soak.py in the firmware repo reads these lines
        to measure how much of the time each peer was actually usable.
        """
        now = time.time()
        with self.peer_lock:
            parts = []
            for identity_hash, peer_if in self.spawned_interfaces.items():
                state = "online" if peer_if.online else "offline"
                if identity_hash in self._pending_detach:
                    left = PEER_GRACE_SECONDS - (now - self._pending_detach[identity_hash])
                    state += f" detach-in={max(0, int(left))}s"
                parts.append(f"{identity_hash[:8]}[{getattr(peer_if, 'peer_name', '?')}]={state}")
        RNS.log(f"{self} peers: {', '.join(parts) or 'none'}", RNS.LOG_INFO)

    def _revive_reconnected(self):
        """Bring back online a peer that returned within the grace.

        The parent cancels a pending detach in several places, and only its
        reuse in _spawn_peer_interface sets the interface online again. A board
        that reconnects as central takes another path: the detach is cancelled,
        the interface is kept -- and stays offline, so Transport routes nothing
        through it while its packets still arrive. Measured 2026-09-26: Rev 1
        dropped for under a second and the A54 carried nothing to the deck for
        the next seven minutes.

        So: a kept interface with a connected address and no detach pending is
        online, whichever path brought it back.
        """
        revived = []
        with self.peer_lock:
            connected = {self._compute_identity_hash(identity)
                         for identity in self.address_to_identity.values() if identity}
            for identity_hash, peer_if in self.spawned_interfaces.items():
                if (not peer_if.online and identity_hash in connected
                        and identity_hash not in self._pending_detach):
                    peer_if.online = True
                    revived.append((identity_hash, peer_if))
        for identity_hash, peer_if in revived:
            RNS.log(f"{self} {peer_if} is back within the grace; online again", RNS.LOG_INFO)
            for data in self._held_announces.release():
                peer_if.process_outgoing(data)
            # The hold only fills while no peer at all is online, and the first
            # peer back takes all of it. A peer away while another stayed up, or
            # back second, missed what went out meanwhile: tell it afresh.
            self._announce_to(peer_if, identity_hash)

    def _check_duplicate_identity(self, address, peer_identity):
        """Decide a second link to the same peer the way the peer decides it.

        Two phones each run central and peripheral, so each connects to the
        other while accepting the other's connection: two links, one peer. The
        parent keeps whichever was first and refuses the second -- and the two
        phones do not agree which was first. Each closed a different link, each
        saw its survivor dropped by the other, and they reconnected for ever.
        Measured 2026-09-26: handshakes every few seconds, and no TAK traffic
        crossing at all -- markers failed, each phone showed the other offline.

        So the link kept is the one where the phone with the lower identity is
        the central. Both phones compute that from the two identities they have
        just exchanged, so both keep the same link. The parent's own checks run
        first and still decide a dead, departing or zombie old link.
        """
        duplicate = super()._check_duplicate_identity(address, peer_identity)
        if not duplicate:
            return False
        local = getattr(getattr(self, "driver", None), "_transport_identity", None)
        identity_hash = self._compute_identity_hash(peer_identity)
        existing = self.identity_to_address.get(identity_hash)
        existing_role = self.driver.get_peer_role(existing) if existing else None
        if not local or len(local) != 16 or existing_role not in ("central", "peripheral"):
            return duplicate
        wanted = "central" if bytes(local) < bytes(peer_identity) else "peripheral"
        if existing_role == wanted:
            return True            # the right link is already up; refuse this one
        RNS.log(f"{self} keeping the link from {address} for {identity_hash[:8]} and closing "
                f"{existing}: the lower identity is the central on both phones", RNS.LOG_INFO)
        self._cleanup_stale_address(identity_hash, existing)
        try:
            self.driver.disconnect(existing)
        except Exception as error:          # the old link may already be gone
            RNS.log(f"{self} could not close {existing}: {error}", RNS.LOG_DEBUG)
        return False

    def _any_peer_online(self):
        with self.peer_lock:
            return any(peer.online for peer in self.spawned_interfaces.values())

    def _spawn_peer_interface(self, *args, **kwargs):
        """Bring a peer up as the parent does, then tell it what it missed.

        Flushed after the parent returns, so the peer lock is not held across
        the sends -- the parent documents the deadlock that would cause.
        """
        peer_identity = args[2] if len(args) > 2 else kwargs.get("peer_identity")
        identity_hash = self._compute_identity_hash(peer_identity) if peer_identity else None
        with self.peer_lock:
            met = identity_hash is not None and identity_hash not in self.spawned_interfaces
        peer_if = super()._spawn_peer_interface(*args, **kwargs)
        held = self._held_announces.release()
        if held:
            RNS.log(f"{self} peer up; sending {len(held)} announce(s) held while none was",
                    RNS.LOG_INFO)
            for data in held:
                peer_if.process_outgoing(data)
        if met:
            self._announce_to(peer_if, identity_hash)
        return peer_if

    def _announce_to(self, peer_if, identity_hash):
        """Tell a newly met peer who this node is -- on its link and nowhere else.

        A fresh announce of each destination this node has been announcing,
        with the same application data, sent only on the peer's interface: no
        airtime anywhere else. Being newer than anything the peer holds, it
        replaces a path the peer learned another way -- over LoRa, or through
        a board -- which a replayed old announce would not.

        For a peer interface just created, and for one kept through the grace
        and back: that one still has its paths, but the hold replays what it
        missed only if no other peer was online meanwhile and it is the first
        back. Rate-limited per peer, so a flapping link is not flooded.
        """
        now = time.monotonic()
        last = self._announced_to.get(identity_hash)
        if last is not None and now - last < PEER_ANNOUNCE_MIN_SECONDS:
            return
        self._announced_to[identity_hash] = now
        sent = 0
        for destination_hash, app_data in self._own_announces.current():
            destination = RNS.Transport.destinations_map.get(destination_hash)
            if destination is None:
                continue
            try:
                destination.announce(app_data=app_data, attached_interface=peer_if)
                sent += 1
            except Exception as error:      # one destination must not stop the rest
                RNS.log(f"{self} could not announce {destination_hash.hex()[:8]} to {peer_if}: {error}",
                        RNS.LOG_WARNING)
        if sent:
            RNS.log(f"{self} met {peer_if}; announced {sent} destination(s) to it alone", RNS.LOG_INFO)

    def get_rssi(self):
        """Get the RSSI of the most recently received message.

        This method is called by signal_quality.py at message delivery time
        to extract signal strength metrics.

        Returns:
            RSSI in dBm (negative integer), or None if unavailable

        Note:
            Android BLE does not provide per-packet RSSI like RNode hardware.
            This returns the last known connection RSSI from the Kotlin bridge,
            which is updated from the scanner cache during the connection.
        """
        if hasattr(self, 'driver') and self.driver is not None:
            return self.driver.get_last_receive_rssi()
        return None


# Register this class as the interface entry point for Reticulum
interface_class = AndroidBLEInterface

