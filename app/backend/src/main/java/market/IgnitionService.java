package market;

import config.ExchangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The ignition price behind the venue (P16-3, D9 as amended): the session symbol's official market open,
 * fetched from Alpaca's snapshot on this service's own daemon thread and cached for
 * {@code GET /market/ignition}, so no browser ever holds the keys and the venue calls Alpaca about once
 * a day however many terminals open.
 *
 * <p><b>Threads.</b> One thread, {@value #THREAD_NAME}, makes every upstream call with Java's
 * {@link HttpClient}; it is never a Netty event loop or the engine thread. It publishes an immutable
 * value (the response body, built once) through one volatile field, which the event loop reads without
 * blocking. When nothing is cached the route answers 503 and calls {@link #requestFetch()}, which only
 * unparks the thread: {@link IgnitionSchedule} still decides whether a call may start, so there is at
 * most one upstream call a minute.
 *
 * <p><b>Cache.</b> Keyed by the daily bar's own New York date: until a bar dated today is seen, the
 * thread refetches every 15 minutes; then it holds until the next New York date. A failed refetch keeps
 * serving the previous value. A failure is retried after 60 s.
 *
 * <p><b>Nothing upstream is echoed.</b> The body served is built here from two parsed numbers. Logs carry
 * only an HTTP status or an exception's class name, rate-limited by {@link IgnitionFailureLog}. Neither
 * key is ever logged.
 */
public final class IgnitionService implements IgnitionSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IgnitionService.class);

    public static final String THREAD_NAME = "ignition-fetch";
    /** The route the router serves this on. */
    public static final String PATH = "/market/ignition";

    static final String FEED = "iex";
    static final int MAX_BODY_BYTES = 64 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Longest park between schedule checks, so a wall-clock jump is noticed. */
    private static final long MAX_PARK_MS = 60_000L;
    private static final long CLOSE_TIMEOUT_MS = 2_000L;

    /** The cached value: the bar's New York date and the body served for it. Immutable. */
    private record Cached(int date, byte[] body) { }

    private final String symbol;
    private final String keyId;
    private final String secretKey;
    private final URI snapshotUri;
    private final Clock clock;
    private final HttpClient http;
    private final Thread thread;
    private final IgnitionFailureLog failures = new IgnitionFailureLog();

    private volatile Cached cached;
    /** When the next upstream call may start, epoch ms; read by the event loop for Retry-After. */
    private volatile long nextAttemptMs = IgnitionSchedule.NEVER;
    private volatile boolean running = true;

    // Fetch thread only.
    private long lastAttemptMs = IgnitionSchedule.NEVER;
    private boolean lastFailed;

    // Observability for tests.
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private volatile Thread lastFetchThread;

    /**
     * @param baseUrl Alpaca's market data base URL, without a trailing slash
     * @param clock   the wall clock the schedule and the log rate limit use
     */
    IgnitionService(String symbol, String keyId, String secretKey, String baseUrl, Clock clock) {
        this.symbol = symbol;
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.snapshotUri = URI.create(baseUrl + "/v2/stocks/" + symbol + "/snapshot?feed=" + FEED);
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
    }

    /**
     * The venue's service, not yet started, or null when the Alpaca keys are unset: the router then has
     * no {@code /market/ignition} route and answers 404, as a venue without the route would.
     */
    public static IgnitionService forVenue(ExchangeConfig config) {
        if (!config.ignitionEnabled()) {
            log.info("Ignition price off: {} and {} are not set, so {} answers 404",
                    ExchangeConfig.ALPACA_KEY_ID, ExchangeConfig.ALPACA_SECRET_KEY, PATH);
            return null;
        }
        return new IgnitionService(config.sessionSymbol(), config.alpacaKeyId(), config.alpacaSecretKey(),
                config.alpacaDataUrl(), Clock.systemUTC());
    }

    /** Starts the fetch thread, which makes its first call at once. */
    public void start() {
        thread.start();
    }

    @Override
    public byte[] body() {
        Cached c = cached;
        return c == null ? null : c.body();
    }

    @Override
    public int retryAfterSeconds() {
        long next = nextAttemptMs;
        if (next == IgnitionSchedule.NEVER) return 1;
        long waitMs = next - clock.millis();
        long seconds = (waitMs + 999L) / 1_000L;
        return (int) Math.max(1L, Math.min(IgnitionSchedule.MIN_GAP_MS / 1_000L, seconds));
    }

    @Override
    public void requestFetch() {
        LockSupport.unpark(thread);
    }

    /** Stops the thread (an upstream call in progress is interrupted), bounded, then the HTTP client. */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("{} did not stop within {} ms; it is a daemon and ends with the JVM", THREAD_NAME, CLOSE_TIMEOUT_MS);
        }
        http.shutdownNow();
    }

    private void run() {
        while (running) {
            long now = clock.millis();
            Cached c = cached;
            long due = IgnitionSchedule.nextAttemptAt(now, lastAttemptMs, lastFailed,
                    c == null ? IgnitionSchedule.NO_DATE : c.date());
            if (now >= due) {
                lastAttemptMs = now;
                nextAttemptMs = now + IgnitionSchedule.MIN_GAP_MS;
                lastFailed = !fetchOnce();
                continue;   // re-evaluate with the outcome
            }
            nextAttemptMs = due;
            evaluations.incrementAndGet();
            LockSupport.parkNanos(this, Math.min(due - now, MAX_PARK_MS) * 1_000_000L);
        }
    }

    /** One upstream call; true when a value was published. */
    private boolean fetchOnce() {
        lastFetchThread = Thread.currentThread();
        attempts.incrementAndGet();
        HttpRequest request = HttpRequest.newBuilder(snapshotUri)
                .timeout(REQUEST_TIMEOUT)
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    return failed("HTTP " + response.statusCode());
                }
                bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (bytes.length > MAX_BODY_BYTES) {
                return failed("snapshot larger than " + MAX_BODY_BYTES + " bytes");
            }
            IgnitionParser.Bar bar = IgnitionParser.parse(bytes);
            if (bar == null) {
                return failed("snapshot without a usable daily bar");
            }
            publish(bar);
            return true;
        } catch (IOException e) {
            return failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            if (running) {
                Thread.interrupted();   // not a stop: treat as a failed call
                return failed(e.getClass().getSimpleName());
            }
            return false;
        }
    }

    private void publish(IgnitionParser.Bar bar) {
        // The symbol is ExchangeConfig-validated [A-Z0-9.]{1,8}: nothing to escape.
        byte[] body = ("{\"symbol\":\"" + symbol + "\",\"openPx\":" + bar.openPx() + ",\"date\":" + bar.date() + "}")
                .getBytes(StandardCharsets.UTF_8);
        Cached previous = cached;
        cached = new Cached(bar.date(), body);
        String recovered = failures.onSuccess();
        if (recovered != null) {
            log.info(recovered);
        }
        if (previous == null || previous.date() != bar.date()) {
            log.info("Ignition price for {}: open {} units of $0.0001, bar date {}", symbol, bar.openPx(), bar.date());
        }
    }

    private boolean failed(String reason) {
        String line = failures.onFailure(clock.millis(), reason);
        if (line != null) {
            log.warn(line);
        }
        return false;
    }

    // --- test observability ---

    /** Upstream calls started so far. */
    long attempts() { return attempts.get(); }

    /** Schedule checks that ended in a park, so far: a test waits for one to know a wake was considered. */
    long evaluations() { return evaluations.get(); }

    /** The thread of the last upstream call. */
    Thread lastFetchThread() { return lastFetchThread; }
}
