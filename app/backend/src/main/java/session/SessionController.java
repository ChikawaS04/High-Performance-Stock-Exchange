package session;

import config.ExchangeConfig;
import engine.SessionRules;
import gateway.OrderGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.VenueStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Issues the venue's session commands (SRS §3.12, P15-8). It never publishes to the inbound ring
 * itself: every command is handed to the gateway's thread through {@code gatewayThread}, and the
 * gateway publishes it, so the inbound ring keeps a single producer (D15, SRS §5.2). In production
 * that executor is the Netty worker event loop; a test whose own thread is the gateway's only
 * caller passes a direct executor.
 *
 * After handing a command over, the controller waits, bounded, on its own thread (never on the
 * event loop) until the engine publishes the expected session state through {@link VenueStatus},
 * so the caller knows the command was journaled and applied. Before issuing anything it applies
 * {@link SessionRules} itself, so a command the engine would refuse never reaches the journal and
 * the operator gets the reason instead of a timeout.
 *
 * {@link #startup()} runs once, after recovery and before the port is bound:
 * <ul>
 *   <li>open on today's date: nothing, trading resumes;</li>
 *   <li>open on an earlier date: the stale day is closed (WARN), whatever {@code SESSION_AUTO_OPEN}
 *       says, so the venue never trades on a stale business date;</li>
 *   <li>open or last closed on a date after today: refused, the clock or the time zone moved back;</li>
 *   <li>closed, with auto-open: today's session is opened, unless today was already closed (WARN,
 *       the engine refuses to reopen a closed day).</li>
 * </ul>
 * There is no calendar or close timer until P15-9: a venue left running past midnight in the
 * session's time zone keeps trading on the previous business date until the next restart closes
 * it. Graceful shutdown never closes the session: a restart is not the end of the day.
 *
 * "Today" is the calendar date in the session's time zone. Single-threaded: call from one thread.
 */
public final class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /** Default bound on handing a command over and on waiting for the engine to apply it. */
    public static final long APPLY_TIMEOUT_MS = 10_000L;

    private final SessionReference reference;
    private final ZoneId zone;
    private final Clock clock;
    private final boolean autoOpen;
    private final OrderGateway gateway;
    private final Executor gatewayThread;
    private final VenueStatus status;
    private final long timeoutMs;

    /**
     * @param gatewayThread runs each publish on the gateway's thread (the Netty worker in production)
     * @param status        where the engine's session state and liveness are read
     * @param timeoutMs     bound on each hand-over and each wait for the engine
     */
    public SessionController(SessionReference reference, ZoneId zone, Clock clock, boolean autoOpen,
                             OrderGateway gateway, Executor gatewayThread, VenueStatus status, long timeoutMs) {
        this.reference = reference;
        this.zone = zone;
        this.clock = clock;
        this.autoOpen = autoOpen;
        this.gateway = gateway;
        this.gatewayThread = gatewayThread;
        this.status = status;
        this.timeoutMs = timeoutMs;
    }

    /** From configuration: the {@code SESSION_*} variables and {@code SESSION_AUTO_OPEN}. */
    public static SessionController fromConfig(ExchangeConfig config, Clock clock, OrderGateway gateway,
                                               Executor gatewayThread, VenueStatus status) {
        return new SessionController(SessionReference.fromConfig(config), config.sessionTimeZone(), clock,
                config.sessionAutoOpen(), gateway, gatewayThread, status, APPLY_TIMEOUT_MS);
    }

    /** Today's business date, YYYYMMDD, in the session's time zone. */
    public int today() {
        LocalDate date = LocalDate.now(clock.withZone(zone));
        return date.getYear() * 10_000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    /**
     * The startup decision (class doc), on the recovered state. Call once, after recovery and
     * before any client can connect.
     *
     * @throws SessionStartupException if the venue must not start; the message says why and what to do
     */
    public void startup() {
        final int today = today();
        if (autoOpen) {
            checkReference();
        }
        long word = status.session();
        int lastClosed = -1;
        if (VenueStatus.isSessionOpen(word)) {
            int open = VenueStatus.sessionDate(word);
            if (open == today) {
                log.info("Session for business date {} is open (recovered); trading resumes", open);
                return;
            }
            if (open > today) {
                throw dateAhead("An open session", open, today);
            }
            log.warn("Recovered a session still open for business date {}, before today ({} in {}): closing it so"
                    + " the venue never trades on a stale business date", open, today, zone);
            close(open);
            lastClosed = open;
        } else {
            lastClosed = VenueStatus.sessionDate(word);
        }

        if (lastClosed > today) {
            throw dateAhead("The last closed session", lastClosed, today);
        }
        if (!autoOpen) {
            log.info("Session closed{}; {}=false, so none is opened", lastClosed < 0 ? "" : " (last closed "
                    + lastClosed + ")", ExchangeConfig.SESSION_AUTO_OPEN);
            return;
        }
        if (lastClosed == today) {
            log.warn("Today's session ({}) was already closed and a closed business date is never reopened;"
                    + " starting with the session closed", today);
            return;
        }
        open(today);
    }

    /**
     * Opens a session for {@code businessDate} with the configured reference data, and waits until
     * the engine has applied it.
     *
     * @throws SessionStartupException if the engine would refuse it, or did not apply it in time
     */
    public void open(int businessDate) {
        long word = status.session();
        boolean open = VenueStatus.isSessionOpen(word);
        int lastClosed = open ? -1 : VenueStatus.sessionDate(word);
        byte[] symbol = reference.symbolBytes();
        int verdict = SessionRules.checkOpen(open, lastClosed, businessDate, symbol, reference.tickSize(),
                reference.lotSize(), reference.priceScale());
        if (verdict != SessionRules.OK) {
            throw new SessionStartupException("Cannot open a session for business date " + businessDate + ": "
                    + SessionRules.describe(verdict) + ".");
        }
        log.info("Opening the session for business date {} ({}, tick {}, lot {}, scale {})", businessDate,
                reference.symbol(), reference.tickSize(), reference.lotSize(), reference.priceScale());
        handOver(() -> gateway.publishSessionOpen(businessDate, symbol, reference.tickSize(), reference.lotSize(),
                reference.priceScale()), "SESSION_OPEN " + businessDate);
        await(VenueStatus.sessionWord(true, businessDate, -1), "SESSION_OPEN " + businessDate);
        log.info("Session open for business date {}", businessDate);
    }

    /**
     * Closes the session open for {@code businessDate}, and waits until the engine has applied it.
     *
     * @throws SessionStartupException if no session is open on that date, or the engine did not apply it in time
     */
    public void close(int businessDate) {
        long word = status.session();
        boolean open = VenueStatus.isSessionOpen(word);
        int verdict = SessionRules.checkClose(open, open ? VenueStatus.sessionDate(word) : -1, businessDate);
        if (verdict != SessionRules.OK) {
            throw new SessionStartupException("Cannot close the session for business date " + businessDate + ": "
                    + SessionRules.describe(verdict) + ".");
        }
        log.info("Closing the session for business date {}", businessDate);
        handOver(() -> gateway.publishSessionClose(businessDate), "SESSION_CLOSE " + businessDate);
        await(VenueStatus.sessionWord(false, -1, businessDate), "SESSION_CLOSE " + businessDate);
        log.info("Session closed for business date {}", businessDate);
    }

    private void checkReference() {
        int verdict = SessionRules.checkReference(reference.symbolBytes(), reference.tickSize(),
                reference.lotSize(), reference.priceScale());
        if (verdict != SessionRules.OK) {
            throw new SessionStartupException("The session reference data is not one this engine can honour: "
                    + SessionRules.describe(verdict) + ". Set " + ExchangeConfig.SESSION_SYMBOL + ", "
                    + ExchangeConfig.SESSION_TICK_SIZE + ", " + ExchangeConfig.SESSION_LOT_SIZE + " and "
                    + ExchangeConfig.SESSION_PRICE_SCALE + " accordingly (or unset them for the defaults).");
        }
    }

    private SessionStartupException dateAhead(String what, int date, int today) {
        return new SessionStartupException(what + " has business date " + date + ", after today (" + today + " in "
                + zone + "). The system clock or " + ExchangeConfig.SESSION_TIME_ZONE + " has moved backwards. Fix the"
                + " clock or the time zone, or move the data directory aside to start a fresh trading history.");
    }

    /** Runs {@code publish} on the gateway's thread and waits for it to finish. */
    private void handOver(Runnable publish, String what) {
        try {
            CompletableFuture.runAsync(publish, gatewayThread).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new SessionStartupException("The gateway failed to publish " + what + ": " + cause, cause);
        } catch (TimeoutException e) {
            throw new SessionStartupException("The gateway's thread did not publish " + what + " within "
                    + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionStartupException("Interrupted while publishing " + what, e);
        }
    }

    /** Waits until the engine publishes {@code expected}, or fails on a fatal or the timeout. */
    private void await(long expected, String what) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (status.session() != expected) {
            // Re-read after the liveness check: a fatal reported downstream of the command (a recorder
            // failing on its event) comes after the engine applied it, and does not undo that.
            if (!status.running() && status.session() != expected) {
                throw new SessionStartupException("The venue stopped (fail-stop) before applying " + what
                        + "; see the FATAL error above.");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new SessionStartupException("The engine did not apply " + what + " within " + timeoutMs
                        + " ms (session state " + describe(status.session()) + ", applied inbound seq "
                        + status.appliedSeq() + ", journaled " + status.journaledSeq() + ").");
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }

    private static String describe(long word) {
        if (word == 0) return "never opened";
        return (VenueStatus.isSessionOpen(word) ? "open on " : "closed, last ") + VenueStatus.sessionDate(word);
    }
}
