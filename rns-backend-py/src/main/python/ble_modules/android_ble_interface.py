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

_PACKET_TYPE_MASK = 0x03
_ANNOUNCE = 0x01
_HEADER_2 = 0x40
_DESTINATION_BYTES = 16


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

        # Call parent constructor - it will use our driver_class
        super().__init__(owner, config)

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
        if self.online and not self._any_peer_online():
            if self._held_announces.hold(data):
                RNS.log(f"{self} no BLE peer yet; holding an announce until one connects",
                        RNS.LOG_DEBUG)
        super().process_outgoing(data)

    def _any_peer_online(self):
        with self.peer_lock:
            return any(peer.online for peer in self.spawned_interfaces.values())

    def _spawn_peer_interface(self, *args, **kwargs):
        """Bring a peer up as the parent does, then tell it what it missed.

        Flushed after the parent returns, so the peer lock is not held across
        the sends -- the parent documents the deadlock that would cause.
        """
        peer_if = super()._spawn_peer_interface(*args, **kwargs)
        held = self._held_announces.release()
        if held:
            RNS.log(f"{self} peer up; sending {len(held)} announce(s) held while none was",
                    RNS.LOG_INFO)
            for data in held:
                peer_if.process_outgoing(data)
        return peer_if

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

