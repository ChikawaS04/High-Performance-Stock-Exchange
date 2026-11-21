package net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public access limits as pure logic on a test clock (P16-9): the three buckets and their order, the 1008
 * close and the public bucket that never closes, client keys and X-Forwarded-For, the exemption, connection
 * caps and their release, and the bounded idle-client map.
 */
class AccessControlTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final String PROXY = "10.0.0.1";
    private static final String BOT = "10.0.0.10";

    private final AtomicLong clock = new AtomicLong(42 * SECOND);

    private AccessControl access(AccessControl.Limits limits) {
        return new AccessControl(limits, clock::get);
    }

    private AccessControl defaults() {
        return access(AccessControl.Limits.defaults(List.of(PROXY), List.of(BOT)));
    }

    private static InetSocketAddress socket(String ip) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ClientKey.literal(ip)), 40_000);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** A direct (untrusted) public connection from {@code ip}. */
    private static ChannelAccess direct(AccessControl a, String ip) {
        ChannelAccess ch = new ChannelAccess();
        assertTrue(a.admit(ch, socket(ip), List.of()), "admitted");
        return ch;
    }

    /** A connection through the trusted proxy carrying the given X-Forwarded-For lines. */
    private static ChannelAccess viaProxy(AccessControl a, String... xff) {
        ChannelAccess ch = new ChannelAccess();
        assertTrue(a.admit(ch, socket(PROXY), List.of(xff)), "admitted");
        return ch;
    }

    private static int passed(AccessControl a, ChannelAccess ch, long now, int frames) {
        int passed = 0;
        for (int i = 0; i < frames; i++) {
            if (a.onFrame(ch, now) == AccessControl.PASS) passed++;
        }
        return passed;
    }

    // --- buckets ---

    @Test
    void aClientWithinTheLimitIsUnaffected() {
        AccessControl a = defaults();
        ChannelAccess ch = direct(a, "198.51.100.1");
        for (int i = 0; i < 600; i++) {   // 10 a second for a minute
            assertEquals(AccessControl.PASS, a.onFrame(ch, clock.get() + i * 100 * MS), "frame " + i);
        }
        assertEquals(0, a.droppedChannel() + a.droppedClient() + a.droppedPublic());
    }

    @Test
    void aBurstIsTrimmedToTheBucketAndChargedToTheChannelLayer() {
        AccessControl a = defaults();
        ChannelAccess ch = direct(a, "198.51.100.1");
        assertEquals(30, passed(a, ch, clock.get(), 100));
        assertEquals(70, a.droppedChannel());
        assertEquals(0, a.closed1008(), "one burst is not sustained excess");
    }

    @Test
    void aClientsChannelsShareItsBucket() {
        AccessControl a = defaults();
        ChannelAccess one = direct(a, "198.51.100.1");
        ChannelAccess two = direct(a, "198.51.100.1");
        assertEquals(30, passed(a, one, clock.get(), 30));
        assertEquals(0, passed(a, two, clock.get(), 10), "the client's bucket is spent");
        assertEquals(10, a.droppedClient());
    }

    @Test
    void aFrameRefusedByALaterLayerSpendsNothingFromTheEarlierOnes() {
        AccessControl a = access(new AccessControl.Limits(10, 30, 10, 30, 20, 5, 5_000, 200, 10, 64,
                List.of(), List.of()));
        ChannelAccess hog = direct(a, "198.51.100.1");
        assertEquals(5, passed(a, hog, clock.get(), 5));          // drains the public bucket (burst 5)
        ChannelAccess other = direct(a, "198.51.100.2");
        assertEquals(0, passed(a, other, clock.get(), 20));      // refused by the public layer only
        assertEquals(20, a.droppedPublic());
        clock.addAndGet(10 * SECOND);                             // the public bucket refills
        assertEquals(5, passed(a, other, clock.get(), 30), "the public burst again");
        assertEquals(0, a.droppedChannel() + a.droppedClient(), "the other client's buckets were never charged");
    }

    @Test
    void sustainedExcessClosesWith1008AfterTheIntervalWhileAnotherClientKeepsPassing() {
        AccessControl a = defaults();
        ChannelAccess flood = direct(a, "198.51.100.1");
        ChannelAccess calm = direct(a, "198.51.100.2");
        long start = clock.get();
        int verdict = AccessControl.PASS;
        long closedAt = -1;
        long firstDrop = -1;
        for (long t = 0; t <= 10 * SECOND && closedAt < 0; t += 10 * MS) {   // 100 frames a second
            verdict = a.onFrame(flood, start + t);
            if (verdict != AccessControl.PASS && firstDrop < 0) firstDrop = t;
            if (verdict == AccessControl.CLOSE) closedAt = t;
            if (t % (200 * MS) == 0) {
                assertEquals(AccessControl.PASS, a.onFrame(calm, start + t), "the calm client at " + t / MS + " ms");
            }
        }
        assertEquals(AccessControl.CLOSE, verdict);
        long over = closedAt - firstDrop;   // the burst passed first; the episode starts at the first drop
        assertTrue(over >= 5 * SECOND && over < 5 * SECOND + 20 * MS, "closed " + over / MS + " ms after the first drop");
        assertEquals(1, a.closed1008());
        assertEquals(AccessControl.DROP, a.onFrame(flood, start + closedAt + MS), "nothing passes once closing");
    }

    @Test
    void aGapOfASecondStartsANewEpisode() {
        AccessControl a = defaults();
        ChannelAccess ch = direct(a, "198.51.100.1");
        long t = clock.get();
        for (int round = 0; round < 10; round++) {   // 4 s of drops, then 1.5 s quiet, ten times over
            for (long s = 0; s < 4 * SECOND; s += 10 * MS) {
                assertNotEquals(AccessControl.CLOSE, a.onFrame(ch, t + s));
            }
            t += 4 * SECOND + 1_500 * MS;
        }
        assertEquals(0, a.closed1008());
    }

    @Test
    void thePublicBucketDropsAcrossManyAddressesAndNeverCloses() {
        AccessControl a = defaults();
        ChannelAccess[] clients = new ChannelAccess[50];
        for (int i = 0; i < clients.length; i++) clients[i] = direct(a, "198.51.100." + (i + 1));
        long start = clock.get();
        long passed = 0;
        for (long t = 0; t < 20 * SECOND; t += 500 * MS) {   // each client 2 a second: 100 a second in all
            for (ChannelAccess ch : clients) {
                int v = a.onFrame(ch, start + t);
                assertNotEquals(AccessControl.CLOSE, v);
                if (v == AccessControl.PASS) passed++;
            }
        }
        assertTrue(a.droppedPublic() > 1_000, "dropped " + a.droppedPublic());
        assertEquals(0, a.droppedChannel() + a.droppedClient());
        assertEquals(0, a.closed1008());
        assertTrue(passed <= 100 + 20 * 20 + 20, "the venue-wide rate holds: " + passed);
    }

    // --- addresses ---

    @Test
    void forwardedForIsIgnoredFromAnUntrustedSocket() {
        AccessControl a = defaults();
        ChannelAccess one = new ChannelAccess();
        ChannelAccess two = new ChannelAccess();
        assertTrue(a.admit(one, socket("198.51.100.7"), List.of("203.0.113.1")));
        assertTrue(a.admit(two, socket("198.51.100.7"), List.of("203.0.113.2")));
        assertSame(one.client, two.client, "keyed by the socket, not the header");
        assertEquals("198.51.100.7", one.client.key.toString());
    }

    @Test
    void forwardedForIsHonouredFromTheTrustedProxyRightmostEntryOnly() {
        AccessControl a = defaults();
        ChannelAccess plain = viaProxy(a, "203.0.113.1");
        ChannelAccess spoofed = viaProxy(a, "6.6.6.6, 203.0.113.1");
        ChannelAccess twoLines = viaProxy(a, "6.6.6.6", "7.7.7.7, 203.0.113.1");
        assertEquals("203.0.113.1", plain.client.key.toString());
        assertSame(plain.client, spoofed.client, "a spoofed left entry is ignored");
        assertSame(plain.client, twoLines.client, "the rightmost entry across header lines");
        assertEquals(0, a.xffFallback());
    }

    @Test
    void aMissingOrMalformedForwardedForFromTheProxyFallsBackToTheProxyKey() {
        AccessControl a = defaults();
        ChannelAccess none = viaProxy(a);
        ChannelAccess empty = viaProxy(a, "");
        ChannelAccess port = viaProxy(a, "203.0.113.1:5555");
        ChannelAccess name = viaProxy(a, "example.com");
        ChannelAccess bracket = viaProxy(a, "[2001:db8::1]");
        ChannelAccess trailing = viaProxy(a, "203.0.113.1,");
        for (ChannelAccess ch : List.of(none, empty, port, name, bracket, trailing)) {
            assertEquals(PROXY, ch.client.key.toString());
        }
        assertEquals(6, a.xffFallback());
    }

    @Test
    void anExemptSocketPassesTheBotsMeasuredPeakUntouched() {
        AccessControl a = defaults();
        ChannelAccess bot = new ChannelAccess();
        assertTrue(a.admit(bot, socket(BOT), List.of()));
        assertTrue(bot.exempt);
        long start = clock.get();
        for (int i = 0; i < 253; i++) {   // F8: the maker's peak, 253 frames in one second
            assertEquals(AccessControl.PASS, a.onFrame(bot, start + i * (SECOND / 253)));
        }
        assertEquals(1, a.exemptConnections());
        assertEquals(0, a.publicConnections());
    }

    @Test
    void anExemptAddressArrivingThroughTheTrustedProxyIsPublic() {
        AccessControl a = defaults();
        ChannelAccess ch = viaProxy(a, BOT);
        assertFalse(ch.exempt);
        assertEquals(BOT, ch.client.key.toString());
        assertEquals(30, passed(a, ch, clock.get(), 100), "limited like anyone");
    }

    @Test
    void ipv6IsKeyedByItsSlash64AndMappedIpv4AsIpv4() {
        AccessControl a = defaults();
        ChannelAccess x = viaProxy(a, "2001:db8:1:2::1");
        ChannelAccess y = viaProxy(a, "2001:db8:1:2:ffff::9");
        ChannelAccess z = viaProxy(a, "2001:db8:1:3::1");
        ChannelAccess mapped = viaProxy(a, "::ffff:203.0.113.5");
        ChannelAccess v4 = viaProxy(a, "203.0.113.5");
        assertSame(x.client, y.client);
        assertNotSame(x.client, z.client);
        assertEquals("2001:db8:1:2::/64", x.client.key.toString());
        assertSame(mapped.client, v4.client);
    }

    @Test
    void theExemptAndTrustedListsMayNotOverlap() {
        assertThrows(IllegalArgumentException.class,
                () -> access(AccessControl.Limits.defaults(List.of(PROXY), List.of(PROXY, BOT))));
    }

    // --- connections ---

    @Test
    void thePerClientCapRefusesAndAReleaseFreesASlotExactlyOnce() {
        AccessControl a = defaults();
        ChannelAccess[] open = new ChannelAccess[10];
        for (int i = 0; i < 10; i++) open[i] = viaProxy(a, "203.0.113.1");
        ChannelAccess eleventh = new ChannelAccess();
        assertFalse(a.admit(eleventh, socket(PROXY), List.of("203.0.113.1")));
        assertFalse(eleventh.admitted);
        assertEquals(1, a.refusedClient());
        assertEquals(10, a.publicConnections(), "a refusal counts nothing");
        assertTrue(a.admit(new ChannelAccess(), socket(PROXY), List.of("203.0.113.2")), "another client is fine");

        a.release(open[0]);
        a.release(open[0]);   // a second release changes nothing
        assertEquals(10, a.publicConnections());
        assertTrue(a.admit(eleventh, socket(PROXY), List.of("203.0.113.1")), "the freed slot");
        assertEquals(11, a.publicConnections());
    }

    @Test
    void theVenueWideCapRefuses() {
        AccessControl a = access(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 5_000, 5, 2, 64,
                List.of(PROXY), List.of(BOT)));
        for (int i = 1; i <= 5; i++) viaProxy(a, "203.0.113." + i);
        assertFalse(a.admit(new ChannelAccess(), socket(PROXY), List.of("203.0.113.99")));
        assertEquals(1, a.refusedVenue());
        ChannelAccess bot = new ChannelAccess();
        assertTrue(a.admit(bot, socket(BOT), List.of()), "the exempt address is never capped");
        a.release(bot);
        assertEquals(0, a.exemptConnections());
    }

    @Test
    void aReconnectingClientKeepsItsDrainedBucket() {
        AccessControl a = defaults();
        ChannelAccess first = viaProxy(a, "203.0.113.1");
        assertEquals(30, passed(a, first, clock.get(), 30));
        a.release(first);
        assertEquals(1, a.idleClients());
        ChannelAccess again = viaProxy(a, "203.0.113.1");
        assertEquals(0, passed(a, again, clock.get(), 10), "a new connection is not a new bucket");
        assertEquals(0, a.idleClients());
        assertEquals(1, a.activeClients());
    }

    @Test
    void idleClientsAreEvictedAtTheBoundLeastRecentlyClosedFirstAndActiveOnesNever() {
        AccessControl a = access(new AccessControl.Limits(10, 30, 10, 30, 20, 100, 5_000, 200, 10, 16,
                List.of(PROXY), List.of(BOT)));
        ChannelAccess keep = viaProxy(a, "198.51.100.1");   // stays open throughout
        for (int i = 1; i <= 40; i++) {
            a.release(viaProxy(a, "203.0.113." + i));
        }
        assertEquals(16, a.idleClients(), "bounded");
        assertEquals(24, a.idleEvicted());
        assertFalse(a.isIdle(ClientKey.of(ClientKey.literal("203.0.113.1"))), "the eldest went first");
        assertTrue(a.isIdle(ClientKey.of(ClientKey.literal("203.0.113.40"))));
        assertEquals(1, a.activeClients());
        assertEquals(30, passed(a, keep, clock.get(), 30), "the active client is untouched");
    }
}
