"""Announces sent while no BLE peer is connected are not lost.

Measured on the bench 2026-09-21: after a Columba restart the node's announces
went out "to 0 peer(s)" -- BLE had not reconnected yet -- and were silently
lost, and nothing re-sent them when the board came back. The handset stayed
unknown to its team until its next scheduled announce.
"""

import importlib.util
import sys
import threading
import types
import unittest
from pathlib import Path

INTERFACE_PATH = (
    Path(__file__).resolve().parents[2]
    / "main/python/ble_modules/android_ble_interface.py"
)


class FakePeer:
    def __init__(self, online=True):
        self.online = online
        self.sent = []

    def process_outgoing(self, data):
        self.sent.append(data)


class FakeBLEInterface:
    """Just enough of the pip BLEInterface for the subclass to run against."""

    def __init__(self, owner, config=None):
        self.online = True
        self.peer_lock = threading.Lock()
        self.spawned_interfaces = {}
        self.max_peers = 7
        self.name = "BLE"
        self.driver = None
        self.handed_off = []
        self.next_peer = FakePeer()
        self.identity_of = {}
        self.identity_to_address = {}
        self.cleaned = []
        self._pending_detach_grace_period = 2.0
        self._identity_cache_ttl = 60
        self._pending_detach = {}
        self.address_to_identity = {}

    def process_outgoing(self, data):
        self.handed_off.append(data)

    def _spawn_peer_interface(self, address, name, peer_identity, **kwargs):
        identity_hash = self._compute_identity_hash(peer_identity)
        with self.peer_lock:
            self.address_to_identity[address] = peer_identity
            existing = self.spawned_interfaces.get(identity_hash)
            if existing is not None:
                existing.online = True      # the parent reuses and revives it
                self._pending_detach.pop(identity_hash, None)
                return existing
            self.spawned_interfaces[identity_hash] = self.next_peer
        return self.next_peer

    def _device_disconnected_callback(self, address):
        # The parent schedules the detach, keyed by identity; the interface stays.
        self._pending_detach[self.identity_of[address]] = 0.0
        self.address_to_identity.pop(address, None)

    def _device_connected_callback(self, address, peer_identity):
        # The parent cancels the detach and records the address -- and does
        # not set the kept interface online.
        self._pending_detach.pop(self._compute_identity_hash(peer_identity), None)
        self.address_to_identity[address] = peer_identity

    def _mtu_negotiated_callback(self, address, mtu):
        pass                                # an existing interface: address only

    def _process_pending_detaches(self):
        # The parent's other cancel: an address came back during the grace.
        connected = {self._compute_identity_hash(i) for i in self.address_to_identity.values()}
        for identity_hash in list(self._pending_detach):
            if identity_hash in connected:
                del self._pending_detach[identity_hash]

    # --- enough of the duplicate-identity machinery for the tie-break ---

    def _compute_identity_hash(self, identity):
        return identity if isinstance(identity, str) else bytes(identity).hex()

    def _check_duplicate_identity(self, address, peer_identity):
        existing = self.identity_to_address.get(self._compute_identity_hash(peer_identity))
        return existing is not None and existing != address   # "alive and healthy"

    def _cleanup_stale_address(self, identity_hash, old_address):
        self.cleaned.append(old_address)
        self.identity_to_address.pop(identity_hash, None)


def load():
    rns = types.ModuleType("RNS")
    rns.log = lambda *args, **kwargs: None
    rns.LOG_DEBUG = rns.LOG_INFO = rns.LOG_WARNING = 0
    rns.Transport = types.SimpleNamespace(destinations_map={})
    base = types.ModuleType("BLEInterface")
    base.BLEInterface = FakeBLEInterface
    drivers = types.ModuleType("drivers")
    driver = types.ModuleType("drivers.android_ble_driver")
    driver.AndroidBLEDriver = object
    sys.modules.update({"RNS": rns, "BLEInterface": base, "drivers": drivers,
                        "drivers.android_ble_driver": driver})
    spec = importlib.util.spec_from_file_location("android_ble_interface", INTERFACE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MODULE = load()


def announce(destination, header_2=False, body=b"announce-body"):
    flags = 0x01 | (0x40 if header_2 else 0)
    transport = bytes([0xEE] * 16) if header_2 else b""
    return bytes([flags, 0]) + transport + destination + body


def data_packet(destination):
    return bytes([0x00, 0]) + destination + b"payload"


class ParseTests(unittest.TestCase):
    def test_an_announce_names_its_destination(self):
        self.assertEqual(MODULE.announce_destination(announce(b"\x11" * 16)), b"\x11" * 16)

    def test_a_transported_announce_names_its_destination_after_the_transport_id(self):
        self.assertEqual(MODULE.announce_destination(announce(b"\x22" * 16, header_2=True)),
                         b"\x22" * 16)

    def test_anything_else_is_not_an_announce(self):
        self.assertIsNone(MODULE.announce_destination(data_packet(b"\x11" * 16)))
        self.assertIsNone(MODULE.announce_destination(b"\x01"))
        self.assertIsNone(MODULE.announce_destination(b""))


class HeldAnnouncesTests(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.held = MODULE.HeldAnnounces(hold_seconds=120, max_held=3, clock=lambda: self.now[0])

    def test_the_latest_announce_per_destination_is_kept(self):
        self.held.hold(announce(b"\x11" * 16, body=b"old"))
        self.held.hold(announce(b"\x11" * 16, body=b"new"))
        released = self.held.release()
        self.assertEqual(len(released), 1)
        self.assertTrue(released[0].endswith(b"new"))

    def test_an_announce_held_too_long_is_not_sent(self):
        self.held.hold(announce(b"\x11" * 16))
        self.now[0] = 121
        self.assertEqual(self.held.release(), [])

    def test_release_empties_the_hold(self):
        self.held.hold(announce(b"\x11" * 16))
        self.held.release()
        self.assertEqual(self.held.release(), [])

    def test_the_hold_is_bounded(self):
        for index in range(10):
            self.held.hold(announce(bytes([index]) * 16))
        self.assertEqual(len(self.held.release()), 3)

    def test_only_announces_are_held(self):
        self.assertFalse(self.held.hold(data_packet(b"\x11" * 16)))


class InterfaceTests(unittest.TestCase):
    def setUp(self):
        self.interface = MODULE.AndroidBLEInterface(owner=None)

    def test_an_announce_with_no_peer_reaches_the_first_peer_to_connect(self):
        packet = announce(b"\x11" * 16)
        self.interface.process_outgoing(packet)
        peer = self.interface._spawn_peer_interface("80:B5", "RNode 1114", b"\x99" * 16)

        self.assertEqual(peer.sent, [packet])

    def test_an_announce_with_a_peer_online_is_not_held_for_later(self):
        self.interface.spawned_interfaces["existing"] = FakePeer(online=True)
        self.interface.process_outgoing(announce(b"\x11" * 16))
        peer = self.interface._spawn_peer_interface("80:B5", "RNode 1114", b"\x99" * 16)

        self.assertEqual(peer.sent, [])

    def test_ordinary_traffic_with_no_peer_is_not_replayed(self):
        """Only announces: a data packet is somebody's message, and a late
        copy of it is not the retry the carrier already provides."""
        self.interface.process_outgoing(data_packet(b"\x11" * 16))
        peer = self.interface._spawn_peer_interface("80:B5", "RNode 1114", b"\x99" * 16)

        self.assertEqual(peer.sent, [])

    def test_the_parent_still_sees_every_packet(self):
        packet = announce(b"\x11" * 16)
        self.interface.process_outgoing(packet)
        self.assertEqual(self.interface.handed_off, [packet])



class PeerGraceTests(unittest.TestCase):
    """A peer's interface, and so its paths, outlives a reconnect.

    Measured phone to phone 2026-09-24: the link dropped at the radio, the
    interface went two seconds later, and the peer came back 39 s after the
    drop to a new interface with no paths.
    """

    def setUp(self):
        self.interface = MODULE.AndroidBLEInterface(owner=None)

    def test_the_grace_covers_a_reconnect(self):
        self.assertGreaterEqual(self.interface._pending_detach_grace_period, 60)
        self.assertGreaterEqual(self.interface._identity_cache_ttl,
                                self.interface._pending_detach_grace_period)

    def test_a_dropped_peer_is_offline_but_kept(self):
        peer = self.interface._spawn_peer_interface("AA", "phone", "ident")
        self.interface.identity_of["AA"] = "ident"
        self.interface._device_disconnected_callback("AA")
        self.assertIs(self.interface.spawned_interfaces["ident"], peer)
        self.assertFalse(peer.online)

    def test_an_announce_while_the_peer_is_away_reaches_it_when_it_is_back(self):
        peer = self.interface._spawn_peer_interface("AA", "phone", "ident")
        self.interface.identity_of["AA"] = "ident"
        self.interface._device_disconnected_callback("AA")
        self.interface.process_outgoing(announce(b"\x11" * 16))
        back = self.interface._spawn_peer_interface("BB", "phone", "ident")
        self.assertIs(back, peer, "the same interface, with its paths")
        self.assertTrue(back.online)
        self.assertEqual(len(back.sent), 1)

    # Measured 2026-09-26: Rev 1 dropped for under a second, reconnected as
    # central, and its kept interface stayed offline -- no route to the deck.

    def away(self):
        peer = self.interface._spawn_peer_interface("AA", "board", "ident")
        self.interface.identity_of["AA"] = "ident"
        self.interface._device_disconnected_callback("AA")
        self.assertFalse(peer.online)
        return peer

    def test_a_board_back_as_central_is_online_again(self):
        peer = self.away()
        self.interface.process_outgoing(announce(b"\x11" * 16))
        self.interface._device_connected_callback("AA", "ident")
        self.assertTrue(peer.online)
        self.assertEqual(len(peer.sent), 1, "the announce held while it was away")

    def test_a_peer_back_while_another_stayed_up_is_announced_to_afresh(self):
        # The hold only fills while no peer is online, so a peer away while
        # another stayed up would otherwise miss what was announced meanwhile.
        announced = []
        destination = types.SimpleNamespace(
            announce=lambda app_data=None, attached_interface=None: announced.append(attached_interface))
        MODULE.RNS.Transport.destinations_map[b"\x11" * 16] = destination
        self.addCleanup(MODULE.RNS.Transport.destinations_map.clear)
        self.interface.next_peer = FakePeer()
        other = self.interface._spawn_peer_interface("ZZ", "phone", "other")
        self.interface.next_peer = FakePeer()
        peer = self.away()
        self.assertTrue(other.online)
        self.interface.process_outgoing(announce(b"\x11" * 16))
        self.assertEqual(peer.sent, [], "not held: another peer was online")
        self.interface._announced_to.clear()      # met long enough ago
        self.interface._device_connected_callback("AA", "ident")
        self.assertTrue(peer.online)
        self.assertIn(peer, announced)

    def test_a_board_back_at_the_grace_tick_is_online_again(self):
        peer = self.away()
        self.interface.address_to_identity["BB"] = "ident"
        self.interface._process_pending_detaches()
        self.assertTrue(peer.online)

    def test_the_peer_heartbeat_names_each_peer_and_its_state(self):
        lines = []
        MODULE.RNS.log = lambda text, *args, **kwargs: lines.append(text)
        self.away()
        self.interface._process_pending_detaches()
        self.assertTrue(any("peers: ident[?]=offline detach-in=" in line for line in lines), lines)

    def test_a_peer_still_away_stays_offline(self):
        peer = self.away()
        self.interface._device_connected_callback("CC", "other")
        self.interface._mtu_negotiated_callback("CC", 509)
        self.assertFalse(peer.online)



class FakeDriver:
    def __init__(self, local, roles):
        self._transport_identity = local
        self.roles = roles
        self.disconnected = []

    def get_peer_role(self, address):
        return self.roles.get(address)

    def disconnect(self, address):
        self.disconnected.append(address)


class LinkTieBreakTests(unittest.TestCase):
    """Two phones, two links to each other: both keep the same one.

    Measured 2026-09-26: each phone kept whichever link came first and closed
    the other, the two disagreed, and they reconnected every few seconds with
    no TAK traffic crossing.
    """

    LOW = bytes([0x11] * 16)
    HIGH = bytes([0x99] * 16)

    def phone(self, local, peer, existing_role):
        interface = MODULE.AndroidBLEInterface(owner=None)
        interface.driver = FakeDriver(local, {"OLD": existing_role})
        interface.identity_to_address[peer.hex()] = "OLD"
        return interface

    def test_the_lower_identity_keeps_its_central_link(self):
        low = self.phone(self.LOW, self.HIGH, "central")     # low is central on OLD
        self.assertTrue(low._check_duplicate_identity("NEW", self.HIGH))
        self.assertEqual(low.driver.disconnected, [])

    def test_the_higher_identity_keeps_the_same_link(self):
        high = self.phone(self.HIGH, self.LOW, "peripheral")  # the same link, seen from high
        self.assertTrue(high._check_duplicate_identity("NEW", self.LOW))
        self.assertEqual(high.driver.disconnected, [])

    def test_the_wrong_way_round_link_gives_way_to_the_new_one(self):
        low = self.phone(self.LOW, self.HIGH, "peripheral")   # low is peripheral on OLD
        self.assertFalse(low._check_duplicate_identity("NEW", self.HIGH))
        self.assertEqual(low.driver.disconnected, ["OLD"])
        high = self.phone(self.HIGH, self.LOW, "central")    # and high agrees
        self.assertFalse(high._check_duplicate_identity("NEW", self.LOW))
        self.assertEqual(high.driver.disconnected, ["OLD"])

    def test_without_a_known_role_the_parent_decides(self):
        low = self.phone(self.LOW, self.HIGH, None)
        self.assertTrue(low._check_duplicate_identity("NEW", self.HIGH))
        self.assertEqual(low.driver.disconnected, [])


if __name__ == "__main__":
    unittest.main()


def real_announce(destination, app_data, header_2=False, ratchet=False):
    """An announce shaped as Reticulum packs one, context byte and all."""
    flags = 0x01 | (0x40 if header_2 else 0) | (0x20 if ratchet else 0)
    transport = bytes([0xEE] * 16) if header_2 else b""
    body = bytes(64 + 10 + 10) + (bytes(32) if ratchet else b"") + bytes(64)
    return bytes([flags, 0]) + transport + destination + b"\x00" + body + app_data


class FakeDestination:
    def __init__(self):
        self.announced = []

    def announce(self, app_data=None, attached_interface=None):
        self.announced.append((app_data, attached_interface))


class AnnounceOnMeetingTests(unittest.TestCase):
    """Two phones meeting over BLE learn each other at once, nobody pressing
    Announce. Operator, 2026-09-26: a message went through only after both
    sides had announced by hand."""

    INBOX = b"\x42" * 16

    def setUp(self):
        self.interface = MODULE.AndroidBLEInterface(owner=None)
        self.inbox = FakeDestination()
        MODULE.RNS.Transport.destinations_map = {self.INBOX: self.inbox}
        # The app announced its inbox earlier, while no peer was up.
        self.interface.process_outgoing(real_announce(self.INBOX, b"LEXUS"))
        self.interface._held_announces.release()

    def test_the_app_data_is_read_whatever_the_header(self):
        for header_2 in (False, True):
            for ratchet in (False, True):
                frame = real_announce(self.INBOX, b"LEXUS", header_2, ratchet)
                self.assertEqual(MODULE.announce_app_data(frame), b"LEXUS", (header_2, ratchet))
        self.assertIsNone(MODULE.announce_app_data(data_packet(self.INBOX)))

    def test_a_newly_met_peer_is_sent_a_fresh_announce_on_its_link_alone(self):
        peer = self.interface._spawn_peer_interface("AA", "phone", "ident")
        self.assertEqual(self.inbox.announced, [(b"LEXUS", peer)])

    def test_a_peer_back_within_the_grace_is_not_announced_to_again(self):
        self.interface._spawn_peer_interface("AA", "phone", "ident")
        self.interface._spawn_peer_interface("BB", "phone", "ident")     # reused
        self.assertEqual(len(self.inbox.announced), 1)

    def test_a_peer_met_again_within_a_minute_is_not_announced_to_again(self):
        self.interface._spawn_peer_interface("AA", "phone", "ident")
        del self.interface.spawned_interfaces["ident"]                    # detached
        self.interface.next_peer = FakePeer()
        self.interface._spawn_peer_interface("BB", "phone", "ident")
        self.assertEqual(len(self.inbox.announced), 1)

    def test_a_destination_no_longer_registered_is_not_revived(self):
        MODULE.RNS.Transport.destinations_map = {}
        self.interface._spawn_peer_interface("AA", "phone", "ident")
        self.assertEqual(self.inbox.announced, [])
