package net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import event.ExecutionEvent;
import event.ExecutionHistory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Serves RESEND requests (SRS §3.6, D16): {@code {"type":"RESEND","fromSeq":N}} replays every
 * recorded execution event from {@code N} to the requesting channel only, as frames identical to
 * the originals (the live serializer, seq included), then
 * {@code {"type":"RESEND_COMPLETE","fromSeq":N,"toSeq":M,"count":C}}, M being the last seq read.
 *
 * Threading. Requests arrive on the Netty worker and are only queued there. One dedicated daemon
 * thread, {@code resend}, serves them one at a time, never on an event loop, so at most one Archive
 * replay runs for resends and a slow reader can delay other requests by at most
 * {@link #WRITABLE_TIMEOUT_MS}. Queue and wake-up are a {@link ConcurrentLinkedQueue} with
 * park/unpark.
 *
 * Limits: one request per channel at a time, at most {@link #MAX_PENDING} venue-wide (the one
 * being served included). Otherwise the request is answered with
 * {@code {"type":"RESEND_REJECTED","fromSeq":N,"reason":"IN_PROGRESS"|"BUSY"}}; a resend that
 * fails part-way on a live channel ends with reason {@code FAILED}, and the client retries from
 * its highest contiguous seq.
 *
 * Public limits (P16-9). A channel that {@link AccessControl} admitted as public (not exempt) is limited
 * further, because every resend reads the outbound recording from its start and so costs a read of the
 * whole retained window, and any public client could ask from seq 1 (P16-7 finding 4):
 * <ul>
 *   <li>public requests have a pool of their own ({@link Limits#publicMaxPending}), so they can never take the
 *       {@link #MAX_PENDING} slots of exempt clients (the bots' catch-up after every venue restart);</li>
 *   <li>queued exempt requests are always served before queued public ones;</li>
 *   <li>one public request per client address per {@link Limits#publicIntervalNanos};</li>
 *   <li>a public {@code fromSeq} more than {@link Limits#publicMaxEvents} events before the newest is raised to
 *       that floor (as a request below the retained window is, P16-7 R5), with a rate-limited WARN, and
 *       {@code RESEND_COMPLETE} keeps the client's own {@code fromSeq}.</li>
 * </ul>
 * A request refused by the public pool or interval gets {@code BUSY}, so the wire protocol is unchanged. A
 * channel with no access state (a server built without access control) counts as exempt, as before.
 *
 * Interleaving. Live EXEC frames (publisher thread) and resent frames (this thread) reach the
 * channel's event loop with no order between them, so a client can see a seq twice, in either
 * order. Frames with the same seq are identical: a client dedupes by seq and applies in seq order.
 * Every seq up to the request's {@link ExecutionHistory#publishedSeq()} is resent, so an event
 * broadcast before the client joined is never missed, even if it was not yet recorded.
 *
 * Out of range (SRS §3.6): a fromSeq below 1 resends from seq 1; one beyond the last seq sends no
 * EXEC; each is logged. A request exactly one past the last seq (a client that is already caught up)
 * is logged at INFO. A fromSeq below the earliest seq journal retention has left (P16-7, R5) resends
 * from that earliest seq, with a rate-limited WARN ({@link ResendRangeLog}); RESEND_COMPLETE is
 * unchanged. A channel that closes mid-resend stops the replay. Resend failures are
 * logged and never touch matching or the recorder.
 */
public final class ResendService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResendService.class);

    /** Requests queued or being served, venue-wide. */
    static final int MAX_PENDING = 4;
    /** How long a resend waits for a channel that is not writable before giving up. */
    static final long WRITABLE_TIMEOUT_MS = 10_000L;
    private static final long CLOSE_TIMEOUT_MS = 5_000L;
    private static final int FLUSH_EVERY = 64;

    /**
     * The public RESEND limits (P16-9).
     *
     * @param publicMaxPending    public requests queued or being served at once; 0 refuses every public request
     * @param publicIntervalNanos at most one public request per client address in this time
     * @param publicMaxEvents     a public request is served from at most this many events before the newest
     */
    public record Limits(int publicMaxPending, long publicIntervalNanos, long publicMaxEvents) {
        /** The ExchangeConfig defaults: one pending, one a minute per client, the last 10,000 events. */
        public static final Limits DEFAULT = new Limits(1, 60_000_000_000L, 10_000L);

        public Limits {
            if (publicMaxPending < 0 || publicIntervalNanos < 0 || publicMaxEvents < 1) {
                throw new IllegalArgumentException("bad public resend limits");
            }
        }
    }

    private final ExecutionHistory history;
    private final Function<ExecutionEvent, String> frames;
    private final Limits limits;
    private final LongSupplier nanos;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentLinkedQueue<Request> exemptQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Request> publicQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger publicPending = new AtomicInteger();
    private final Set<ChannelId> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong refusedInProgress = new AtomicLong();
    private final AtomicLong refusedBusy = new AtomicLong();
    private final AtomicLong refusedPublicPool = new AtomicLong();
    private final AtomicLong refusedPublicInterval = new AtomicLong();
    private final Thread thread;
    private final ResendRangeLog rangeLog = new ResendRangeLog();
    /** The public depth floor's WARN; resend thread only. */
    private final MinuteLog floorLog = new MinuteLog();
    private volatile boolean running = true;

    /**
     * @param servedFrom where the replay starts: {@code fromSeq}, or the public depth floor above it
     */
    private record Request(Channel channel, long fromSeq, long throughSeq, boolean isPublic, long servedFrom) { }

    /**
     * @param history the recorded execution history
     * @param frames  the live EXEC serializer ({@code publisher.ExecutionFrames::toJson})
     */
    public ResendService(ExecutionHistory history, Function<ExecutionEvent, String> frames) {
        this(history, frames, Limits.DEFAULT);
    }

    /** With the public RESEND limits (P16-9); Main's form. */
    public ResendService(ExecutionHistory history, Function<ExecutionEvent, String> frames, Limits limits) {
        this(history, frames, limits, System::nanoTime);
    }

    ResendService(ExecutionHistory history, Function<ExecutionEvent, String> frames, Limits limits,
                  LongSupplier nanos) {
        this.history = history;
        this.frames = frames;
        this.limits = limits;
        this.nanos = nanos;
        this.thread = new Thread(this::run, "resend");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Queues a resend for {@code channel}. Called on the Netty worker; never blocks. The target
     * seq is taken now, after the channel joined the broadcast group.
     */
    public void request(Channel channel, long fromSeq) {
        if (!running) {
            log.warn("RESEND from {} ignored: the server is stopping", channel.remoteAddress());
            return;
        }
        if (!inFlight.add(channel.id())) {
            refusedInProgress.incrementAndGet();
            log.warn("RESEND fromSeq {} from {} rejected: a resend for this channel is in progress",
                    fromSeq, channel.remoteAddress());
            reject(channel, fromSeq, "IN_PROGRESS");
            return;
        }
        ChannelAccess access = channel.attr(ChannelAccess.KEY).get();
        if (access != null && access.isPublic()) {
            requestPublic(channel, fromSeq, access.client);
            return;
        }
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            inFlight.remove(channel.id());
            refusedBusy.incrementAndGet();
            log.warn("RESEND fromSeq {} from {} rejected: {} resends already pending", fromSeq,
                    channel.remoteAddress(), MAX_PENDING);
            reject(channel, fromSeq, "BUSY");
            return;
        }
        exemptQueue.add(new Request(channel, fromSeq, history.publishedSeq(), false, fromSeq));
        LockSupport.unpark(thread);
    }

    /**
     * A public client's request (P16-9), on the worker thread: the client's interval first, then the public pool.
     * Both refusals answer BUSY; neither is logged per request (the counters show them on {@code /health}).
     */
    private void requestPublic(Channel channel, long fromSeq, ChannelAccess.ClientState client) {
        long now = nanos.getAsLong();
        if (client.resent && now - client.lastResendNanos < limits.publicIntervalNanos()) {
            inFlight.remove(channel.id());
            refusedPublicInterval.incrementAndGet();
            reject(channel, fromSeq, "BUSY");
            return;
        }
        if (publicPending.incrementAndGet() > limits.publicMaxPending()) {
            publicPending.decrementAndGet();
            inFlight.remove(channel.id());
            refusedPublicPool.incrementAndGet();
            reject(channel, fromSeq, "BUSY");
            return;
        }
        client.resent = true;
        client.lastResendNanos = now;
        long through = history.publishedSeq();
        long floor = Math.max(1L, through - limits.publicMaxEvents() + 1L);
        publicQueue.add(new Request(channel, fromSeq, through, true, Math.max(fromSeq, floor)));
        LockSupport.unpark(thread);
    }

    /** Requests served or abandoned so far; a reader sees every write of those requests. */
    public long finishedRequests() {
        return finished.get();
    }

    /** Refused because the channel had a resend pending (IN_PROGRESS). */
    public long refusedInProgress() {
        return refusedInProgress.get();
    }

    /** Exempt requests refused because {@link #MAX_PENDING} were pending (BUSY). */
    public long refusedBusy() {
        return refusedBusy.get();
    }

    /** Public requests refused because the public pool was full (BUSY, P16-9). */
    public long refusedPublicPool() {
        return refusedPublicPool.get();
    }

    /** Public requests refused by the per-client interval (BUSY, P16-9). */
    public long refusedPublicInterval() {
        return refusedPublicInterval.get();
    }

    /** Stops the resend thread, abandoning any request in progress. Bounded. */
    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Resend thread did not stop within {} ms", CLOSE_TIMEOUT_MS);
        }
    }

    private void run() {
        while (running) {
            // Exempt first (P16-9): a bot's catch-up never waits behind a queued public request.
            Request request = exemptQueue.poll();
            if (request == null) {
                request = publicQueue.poll();
            }
            if (request == null) {
                LockSupport.parkNanos(this, 100_000_000L);
                continue;
            }
            try {
                serve(request);
            } catch (RuntimeException e) {
                log.warn("RESEND fromSeq {} for {} failed", request.fromSeq(), request.channel().remoteAddress(), e);
            } finally {
                inFlight.remove(request.channel().id());
                (request.isPublic() ? publicPending : pending).decrementAndGet();
                finished.incrementAndGet();
            }
        }
    }

    private void serve(Request request) {
        final Channel channel = request.channel();
        if (!channel.isActive()) {
            log.info("RESEND fromSeq {} dropped: {} disconnected before it was served", request.fromSeq(),
                    channel.remoteAddress());
            return;
        }
        long from = request.servedFrom();
        if (from > request.fromSeq()) {
            long held = floorLog.occurrence(System.nanoTime());
            if (held >= 0) {
                log.warn("Public RESEND fromSeq {} from {} served from seq {}: public resends reach back at most {} "
                        + "events{}", request.fromSeq(), channel.remoteAddress(), from, limits.publicMaxEvents(),
                        held > 0 ? " (" + held + " more since the last warning)" : "");
            }
        }
        if (from < 1) {
            log.warn("RESEND fromSeq {} from {} is before the first recorded event; resending from seq 1",
                    from, channel.remoteAddress());
            from = 1;
        }
        long startNanos = System.nanoTime();
        ChannelVisitor visitor = new ChannelVisitor(channel);
        ExecutionHistory.Result result;
        try {
            result = history.replay(from, request.throughSeq(), visitor);
        } catch (RuntimeException e) {
            channel.flush();
            log.warn("RESEND fromSeq {} for {} failed after {} frames", request.fromSeq(), channel.remoteAddress(),
                    visitor.sent, e);
            reject(channel, request.fromSeq(), "FAILED");
            return;
        }
        channel.flush();
        if (result.stopped()) {
            if (!channel.isActive()) {
                log.info("RESEND fromSeq {}: {} disconnected after {} frames", request.fromSeq(),
                        channel.remoteAddress(), result.count());
            } else if (!running) {
                log.info("RESEND fromSeq {} for {} abandoned at shutdown after {} frames", request.fromSeq(),
                        channel.remoteAddress(), result.count());
            } else {
                log.warn("RESEND fromSeq {} for {} abandoned after {} frames: the client did not read for {} ms",
                        request.fromSeq(), channel.remoteAddress(), result.count(), WRITABLE_TIMEOUT_MS);
                reject(channel, request.fromSeq(), "FAILED");
            }
            return;
        }
        if (result.firstSeq() > from) {
            String line = rangeLog.onBelowEarliest(System.currentTimeMillis(), request.fromSeq(), result.firstSeq(),
                    channel.remoteAddress());
            if (line != null) log.warn(line);
        }
        if (request.fromSeq() == result.lastSeq() + 1) {
            log.info("RESEND fromSeq {} from {}: already caught up at seq {}; nothing to resend",
                    request.fromSeq(), channel.remoteAddress(), result.lastSeq());
        } else if (request.fromSeq() > result.lastSeq()) {
            log.warn("RESEND fromSeq {} from {} is beyond the last recorded seq {}; nothing to resend",
                    request.fromSeq(), channel.remoteAddress(), result.lastSeq());
        }
        ObjectNode done = mapper.createObjectNode();
        done.put("type", "RESEND_COMPLETE");
        done.put("fromSeq", request.fromSeq());
        done.put("toSeq", result.lastSeq());
        done.put("count", result.count());
        channel.writeAndFlush(new TextWebSocketFrame(done.toString()));
        log.info(ResendLog.complete(request.fromSeq(), result, channel.remoteAddress(),
                (System.nanoTime() - startNanos) / 1_000_000L));
    }

    private void reject(Channel channel, long fromSeq, String reason) {
        if (!channel.isActive()) return;
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "RESEND_REJECTED");
        frame.put("fromSeq", fromSeq);
        frame.put("reason", reason);
        channel.writeAndFlush(new TextWebSocketFrame(frame.toString()));
    }

    /** Writes each event to one channel, waiting (bounded) while the channel is not writable. */
    private final class ChannelVisitor implements ExecutionHistory.Visitor {
        private final Channel channel;
        private long sent;
        private int unflushed;

        ChannelVisitor(Channel channel) {
            this.channel = channel;
        }

        @Override
        public boolean onEvent(ExecutionEvent event) {
            if (!awaitWritable()) return false;
            channel.write(new TextWebSocketFrame(frames.apply(event)));
            sent++;
            if (++unflushed >= FLUSH_EVERY) {
                channel.flush();
                unflushed = 0;
            }
            return true;
        }

        @Override
        public boolean keepWaiting() {
            return running && channel.isActive();
        }

        private boolean awaitWritable() {
            if (!running || !channel.isActive()) return false;
            if (channel.isWritable()) return true;
            channel.flush();
            unflushed = 0;
            long deadline = System.currentTimeMillis() + WRITABLE_TIMEOUT_MS;
            while (!channel.isWritable()) {
                if (!running || !channel.isActive() || System.currentTimeMillis() > deadline) return false;
                LockSupport.parkNanos(1_000_000L);
            }
            return true;
        }
    }
}
