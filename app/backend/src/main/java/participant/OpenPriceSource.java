package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The bots' anchor: the day's open, in long units of $0.0001, read from the venue's own
 * {@code GET /market/ignition} (P16-3, D9), the same number the terminal shows. The bots never
 * hold Alpaca keys and never read {@code .env.local} (P16-5, amended D16).
 *
 * <p><b>At start</b> ({@link #resolve}):
 * <ul>
 *   <li>200: the body's {@code openPx}, which must be a positive price on the tick.</li>
 *   <li>404: the venue has no Alpaca keys, so the route does not exist. Only then is
 *       {@code BOTS_OPEN_PX} used; with it unset, {@link NoOpenPriceException} (the process exits 2).</li>
 *   <li>503: the venue has not cached a value yet. Wait the {@code Retry-After} (clamped to 1 to
 *       60 s) and ask again, with no total limit.</li>
 *   <li>Anything else (connection refused, another status, a bad body): retried the same way,
 *       every {@link #RETRY_MILLIS}, with no total limit, because {@code /health} already gates the
 *       connection.</li>
 * </ul>
 * One WARN at the first failure, then at most one per {@link #WARN_INTERVAL_MILLIS} with the count
 * held back. Nothing is sent to the venue until an open is known.
 *
 * <p><b>At a session open</b> ({@link #readOnce}): one request, the open on a 200, otherwise
 * {@link Prices#NA}; the caller keeps its old anchor.
 *
 * <p>{@code BOTS_OPEN_PX} is dollars as exact decimal text ({@link #parseDollars}), never a double
 * and never rounded.
 */
public final class OpenPriceSource {

    private static final Logger log = LoggerFactory.getLogger(OpenPriceSource.class);

    /** Wait between attempts after any failure other than a 503 with Retry-After. */
    static final long RETRY_MILLIS = 5_000;
    static final long WARN_INTERVAL_MILLIS = 600_000;
    static final long MIN_RETRY_AFTER_SECONDS = 1;
    static final long MAX_RETRY_AFTER_SECONDS = 60;

    private OpenPriceSource() { }

    /** One answer from the route: an HTTP status (or -1 when no response came), body and Retry-After. */
    public record Answer(int status, String body, long retryAfterSeconds, String failure) {
        static Answer of(int status, String body, long retryAfterSeconds) {
            return new Answer(status, body, retryAfterSeconds, null);
        }

        static Answer failed(String failure) {
            return new Answer(-1, null, -1, failure);
        }
    }

    /** One request to the route. */
    @FunctionalInterface
    public interface Fetcher {
        Answer fetch() throws InterruptedException;
    }

    /** How the resolver waits; tests pass one that only advances a clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** The venue has no ignition route (404) and {@code BOTS_OPEN_PX} is unset. */
    public static final class NoOpenPriceException extends RuntimeException {
        NoOpenPriceException(String message) {
            super(message);
        }
    }

    /** The real fetcher: {@code GET <ignition>} with a 5 s timeout. */
    public static Fetcher http(HttpClient http, URI ignition) {
        return () -> {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(ignition)
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                java.util.List<String> ra = res.headers().allValues("Retry-After");
                long retryAfter = ra.isEmpty() ? -1L : seconds(ra.get(0));
                return Answer.of(res.statusCode(), res.body(), retryAfter);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                return Answer.failed(e.getClass().getSimpleName());
            }
        };
    }

    /**
     * Resolve the open at start (see the class comment).
     *
     * @param fallbackUnits {@code BOTS_OPEN_PX} in units, or {@link Prices#NA} when unset
     */
    public static long resolve(Fetcher fetcher, long fallbackUnits, ObjectMapper mapper, Sleeper sleeper,
                               LongSupplier clockMillis) throws InterruptedException {
        WarnLimiter warn = new WarnLimiter(WARN_INTERVAL_MILLIS, clockMillis);
        boolean failed = false;
        while (true) {
            Answer a = fetcher.fetch();
            if (a.status() == 200) {
                long units = openPx(mapper, a.body());
                if (units > 0) {
                    if (failed) {
                        log.info("Read the open from /market/ignition after waiting");
                    }
                    log.info("Open from /market/ignition: {} units ($0.0001)", units);
                    return units;
                }
                failed = true;
                warn(warn, "/market/ignition answered 200 with a body that has no valid openPx");
                sleeper.sleep(RETRY_MILLIS);
                continue;
            }
            if (a.status() == 404) {
                if (fallbackUnits > 0) {
                    log.info("/market/ignition answered 404 (the venue has no Alpaca keys); using BOTS_OPEN_PX = {} units",
                            fallbackUnits);
                    return fallbackUnits;
                }
                throw new NoOpenPriceException("/market/ignition answered 404 (the venue has no Alpaca keys) and "
                        + "BOTS_OPEN_PX is unset: set BOTS_OPEN_PX (dollars, near the instrument's real price) "
                        + "or give the venue its Alpaca keys");
            }
            failed = true;
            long waitMillis;
            if (a.status() == 503) {
                long s = a.retryAfterSeconds() > 0 ? a.retryAfterSeconds() : RETRY_MILLIS / 1_000;
                waitMillis = 1_000L * Math.max(MIN_RETRY_AFTER_SECONDS, Math.min(MAX_RETRY_AFTER_SECONDS, s));
                warn(warn, "/market/ignition answered 503 (no open cached yet); waiting for it");
            } else {
                waitMillis = RETRY_MILLIS;
                warn(warn, "/market/ignition failed (" + (a.status() < 0 ? a.failure() : "HTTP " + a.status())
                        + "); retrying");
            }
            sleeper.sleep(waitMillis);
        }
    }

    /** One request: the open on a 200 with a valid body, otherwise {@link Prices#NA}. */
    public static long readOnce(Fetcher fetcher, ObjectMapper mapper) throws InterruptedException {
        Answer a = fetcher.fetch();
        return a.status() == 200 ? openPx(mapper, a.body()) : Prices.NA;
    }

    /** {@code openPx} from an ignition body: a positive on-tick price, otherwise {@link Prices#NA}. */
    static long openPx(ObjectMapper mapper, String body) {
        if (body == null) {
            return Prices.NA;
        }
        try {
            JsonNode px = mapper.readTree(body).path("openPx");
            if (!px.isIntegralNumber() || !px.canConvertToLong()) {
                return Prices.NA;
            }
            long units = px.asLong();
            return Prices.isOnTick(units) ? units : Prices.NA;
        } catch (Exception e) {
            return Prices.NA;
        }
    }

    /**
     * Dollars as exact decimal text (at most 4 decimals) to units of $0.0001. The result must be a
     * positive price on the tick; nothing is rounded.
     *
     * @throws IllegalArgumentException naming {@code name} for anything else
     */
    public static long parseDollars(String text, String name) {
        String t = text.trim();
        int dot = t.indexOf('.');
        String whole = dot < 0 ? t : t.substring(0, dot);
        String frac = dot < 0 ? "" : t.substring(dot + 1);
        if (whole.isEmpty() || whole.length() > 9 || frac.length() > 4 || !digits(whole) || !digits(frac)) {
            throw new IllegalArgumentException(name + " must be dollars like 1405.00 (at most 4 decimals), got '"
                    + text + "'");
        }
        long units = Long.parseLong(whole) * Prices.SCALE
                + (frac.isEmpty() ? 0 : Long.parseLong((frac + "0000").substring(0, 4)));
        if (!Prices.isOnTick(units)) {
            throw new IllegalArgumentException(name + " must be a positive price on the $"
                    + (Prices.TICK / (double) Prices.SCALE) + " tick, got '" + text + "'");
        }
        return units;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static long seconds(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void warn(WarnLimiter limiter, String message) {
        long held = limiter.occurrence();
        if (held == 0) {
            log.warn(message);
        } else if (held > 0) {
            log.warn("{} ({} more since the last warning)", message, held);
        }
    }
}
