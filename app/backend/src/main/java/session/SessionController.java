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
 * One decision serves {@link #startup()}, once after recovery and before the port is bound, and
 * {@link #roll()}, on every scheduler tick while the venue runs (P15-9):
 * <ul>
 *   <li>open on today's date: nothing, trading resumes;</li>
 *   <li>open on an earlier date: the stale day is closed, whatever {@code SESSION_AUTO_OPEN} says,
 *       so the venue never trades on a stale business date;</li>
 *   <li>open or last closed on a date after today: the clock or the time zone moved back. Startup
 *       refuses; a running venue logs one ERROR and takes no action;</li>
 *   <li>closed, with auto-open: today's session is opened, unless today was already closed (the
 *       engine refuses to reopen a closed day) or the calendar says today is not a trading day;</li>
 *   <li>reference source unavailable: the session stays closed, and a later tick retries;</li>
 *   <li>reference source unusable: startup refuses; a running venue logs an ERROR, stays closed and
 *       a later tick retries.</li>
 * </ul>
 * The decision reads only the engine's session state, which recovery restores from the journal, so a
 * restart at any point, including between a close and the next open, converges to the same state.
 * The source is read only when a session is about to open, so an open day never touches it. Graceful
 * shutdown never closes the session: a restart is not the end of the day.
 *
 * "Today" is the calendar date in the session's time zone. Used by one thread at a time: the startup
 * thread, then the session scheduler's.
 */
public final class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /** Default bound on handing a command over and on waiting for the engine to apply it. */
    public static final long APPLY_TIMEOUT_MS = 10_000L;

    /** A running venue reads its reference source at most this often while a day waits to open. */
    public static final long SOURCE_RETRY_MS = 30_000L;

    private final ReferenceSource source;
    private final ZoneId zone;
    private final Clock clock;
    private final boolean autoOpen;
    private final OrderGateway gateway;
    private final Executor gatewayThread;
    private final VenueStatus status;
    private final long timeoutMs;

    private boolean sourceRead;
    private long sourceReadMillis;
    private int reportedAheadDate = -1;
    private int reportedNonTradingDate = -1;

    /**
     * @param source        where reference data and the calendar come from
     * @param gatewayThread runs each publish on the gateway's thread (the Netty worker in production)
     * @param status        where the engine's session state and liveness are read
     * @param timeoutMs     bound on each hand-over and each wait for the engine
     */
    public SessionController(ReferenceSource source, ZoneId zone, Clock clock, boolean autoOpen,
                             OrderGateway gateway, Executor gatewayThread, VenueStatus status, long timeoutMs) {
        this.source = source;
        this.zone = zone;
        this.clock = clock;
        this.autoOpen = autoOpen;
        this.gateway = gateway;
        this.gatewayThread = gatewayThread;
        this.status = status;
        this.timeoutMs = timeoutMs;
    }

    /** With fixed reference data, every date a trading day. */
    public SessionController(SessionReference reference, ZoneId zone, Clock clock, boolean autoOpen,
                             OrderGateway gateway, Executor gatewayThread, VenueStatus status, long timeoutMs) {
        this(new ConfigReferenceSource(reference), zone, clock, autoOpen, gateway, gatewayThread, status, timeoutMs);
    }

    /** From configuration: the {@code SESSION_*} variables and {@code SESSION_AUTO_OPEN}. */
    public static SessionController fromConfig(ExchangeConfig config, Clock clock, OrderGateway gateway,
                                               Executor gatewayThread, VenueStatus status) {
        return fromConfig(config, new ConfigReferenceSource(config), clock, gateway, gatewayThread, status);
    }

    /** From configuration, with reference data and the calendar from {@code source}. */
    public static SessionController fromConfig(ExchangeConfig config, ReferenceSource source, Clock clock,
                                               OrderGateway gateway, Executor gatewayThread, VenueStatus status) {
        return new SessionController(source, config.sessionTimeZone(), clock, config.sessionAutoOpen(), gateway,
                gatewayThread, status, APPLY_TIMEOUT_MS);
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
        if (autoOpen) {
            try {
                source.checkAtStartup();
            } catch (ReferenceDataException e) {
                throw new SessionStartupException(e.getMessage(), e);
            }
        }
        decide(true);
    }

    /**
     * The same decision while the venue runs: closes a day that has ended and opens the next one. Does
     * nothing after a fail-stop: no read, no publish. Never throws for a session failure, because a
     * running venue does not exit over one; it logs and a later tick tries again.
     */
    public void roll() {
        if (!status.running()) return;
        try {
            decide(false);
        } catch (SessionStartupException e) {
            log.error("Session roll failed: {}", e.getMessage(), e);
        }
    }

    private void decide(boolean starting) {
        final int today = today();
        long word = status.session();
        int lastClosed;
        if (VenueStatus.isSessionOpen(word)) {
            int open = VenueStatus.sessionDate(word);
            if (open == today) {
                if (starting) log.info("Session for business date {} is open (recovered); trading resumes", open);
                return;
            }
            if (open > today) {
                dateAhead("An open session", open, today, starting);
                return;
            }
            log.warn("A session is still open for business date {}, before today ({} in {}): closing it so the"
                    + " venue never trades on a stale business date", open, today, zone);
            close(open);
            lastClosed = open;
        } else {
            lastClosed = VenueStatus.sessionDate(word);
        }

        if (lastClosed > today) {
            dateAhead("The last closed session", lastClosed, today, starting);
            return;
        }
        if (!autoOpen) {
            if (starting) {
                log.info("Session closed{}; {}=false, so none is opened", lastClosed < 0 ? "" : " (last closed "
                        + lastClosed + ")", ExchangeConfig.SESSION_AUTO_OPEN);
            }
            return;
        }
        if (lastClosed == today) {
            if (starting) {
                log.warn("Today's session ({}) was already closed and a closed business date is never reopened;"
                        + " starting with the session closed", today);
            }
            return;
        }
        if (!starting && (!status.running() || !sourceReadIsDue())) {
            return;
        }
        openIfTradingDay(today);
    }

    private boolean sourceReadIsDue() {
        long now = clock.millis();
        return !sourceRead || now < sourceReadMillis || now - sourceReadMillis >= SOURCE_RETRY_MS;
    }

    private void openIfTradingDay(int today) {
        sourceRead = true;
        sourceReadMillis = clock.millis();
        SessionDay day;
        try {
            day = source.load(today);
        } catch (ReferenceUnavailableException e) {
            log.error("Reference data for business date {} cannot be read, so the session stays closed and the read"
                    + " is retried: {}", today, e.getMessage());
            return;
        } catch (ReferenceDataException e) {
            throw new SessionStartupException(e.getMessage(), e);
        }
        if (!day.tradingDay()) {
            if (reportedNonTradingDate != today) {
                reportedNonTradingDate = today;
                log.info("The trading calendar says {} is not a trading day; the session stays closed", today);
            }
            return;
        }
        issueOpen(today, day.reference());
    }

    /**
     * Opens a session for {@code businessDate} with the source's reference data, and waits until the
     * engine has applied it. The calendar is not consulted: the caller asks for this date.
     *
     * @throws SessionStartupException if the source cannot answer, the engine would refuse it, or
     *                                 the engine did not apply it in time
     */
    public void open(int businessDate) {
        SessionDay day;
        try {
            day = source.load(businessDate);
        } catch (ReferenceUnavailableException | ReferenceDataException e) {
            throw new SessionStartupException("Cannot open a session for business date " + businessDate + ": "
                    + e.getMessage(), e);
        }
        issueOpen(businessDate, day.reference());
    }

    private void issueOpen(int businessDate, SessionReference reference) {
        long word = status.session();
        boolean open = VenueStatus.isSessionOpen(word);
        int lastClosed = open ? -1 : VenueStatus.sessionDate(word);
        byte[] symbol = reference.symbolBytes();
        int verdict = SessionRules.checkOpen(open, lastClosed, businessDate, symbol, reference.tickSize(),
                reference.lotSize(), reference.priceScale());
        if (verdict != SessionRules.OK) {
            boolean badReference = SessionRules.checkReference(symbol, reference.tickSize(), reference.lotSize(),
                    reference.priceScale()) != SessionRules.OK;
            throw new SessionStartupException("Cannot open a session for business date " + businessDate + ": "
                    + SessionRules.describe(verdict) + "." + (badReference ? " " + source.remedy() : ""));
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

    /** Refuses at startup; a running venue reports it once per date, and takes no action. */
    private void dateAhead(String what, int date, int today, boolean starting) {
        String message = what + " has business date " + date + ", after today (" + today + " in "
                + zone + "). The system clock or " + ExchangeConfig.SESSION_TIME_ZONE + " has moved backwards. Fix the"
                + " clock or the time zone, or move the data directory aside to start a fresh trading history.";
        if (starting) {
            throw new SessionStartupException(message);
        }
        if (reportedAheadDate != date) {
            reportedAheadDate = date;
            log.error(message);
        }
    }

    /**
     * Runs {@code publish} on the gateway's thread and waits for it to finish. The liveness check runs
     * there too, immediately before the publish, so no command reaches the gateway after a fail-stop.
     */
    private void handOver(Runnable publish, String what) {
        Runnable checked = () -> {
            if (!status.running()) {
                throw new SessionStartupException("The venue stopped (fail-stop) before publishing " + what
                        + "; see the FATAL error above.");
            }
            publish.run();
        };
        try {
            CompletableFuture.runAsync(checked, gatewayThread).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof SessionStartupException stopped) throw stopped;
            throw new SessionStartupException("The gateway failed to publish " + what + ": " + cause, cause);
        } catch (TimeoutException e) {
            throw new SessionStartupException("The gateway's thread did not publish " + what + " within "
                    + timeoutMs + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionStartupException("Interrupted while publishing " + what, e);
        }
    }

    /** Waits until the engine publishes {@code expected}, or fails on a fatal, an interrupt or the timeout. */
    private void await(long expected, String what) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (status.session() != expected) {
            // Re-read after the liveness check: a fatal reported downstream of the command (a recorder
            // failing on its event) comes after the engine applied it, and does not undo that.
            if (!status.running() && status.session() != expected) {
                throw new SessionStartupException("The venue stopped (fail-stop) before applying " + what
                        + "; see the FATAL error above.");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new SessionStartupException("Interrupted while waiting for the engine to apply " + what);
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
