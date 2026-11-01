package engine;

import io.aeron.Image;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentTerminationException;

/**
 * The matching engine's thread (SRS §3.3): an Agrona agent that polls the recorded inbound
 * stream and hands each command's bytes to the {@link CommandApplier}, which decodes it, checks
 * its seq and drives the {@link CommandHandler} (the {@link MatchingEngineHandler}). It is the
 * stream's sole reader. Startup recovery replays the journal tail through the same applier
 * before this agent starts (P15-5), so recovery and live matching share one path.
 *
 * Gating (D11, SRS §5.7). Every poll is bounded by {@link CommandGate#limit()}, the Archive's
 * recorded position for the stream, so a command is processed only once it is in the journal.
 *
 * Nothing is ever skipped. Seqs must arrive as exactly {@code appliedSeq + 1}. Aeron's image
 * poll catches an exception thrown by a fragment handler, reports it to the client's error
 * handler and moves past that fragment, so the handler here never lets one escape: it records
 * the failure and returns {@code ABORT}, which leaves the image on the failing command. The
 * agent then throws {@link AgentTerminationException}; its runner stops and its error handler
 * (fail-stop) halts the venue. A gap, a repeat, an undecodable command, a handler exception, a
 * dead gate, a closed image or a late join are all fatal in this way.
 *
 * Progress. {@link #appliedSeq()} is the seq of the last command fully processed, published
 * with a release store so any thread may read it (graceful shutdown, /health at P15-7), and
 * {@link #appliedPosition()} the recording position just after it (the shutdown snapshot).
 *
 * Zero allocation per command (SRS §5.1): the applier's flyweight codec and reused carrier, a
 * fragment handler built once, primitive state. Exceptions are on the fatal path only. No logging.
 */
public final class EngineAgent implements Agent {

    /** Commands per duty cycle; bounds how long one doWork holds the thread. */
    static final int FRAGMENT_LIMIT = 64;

    private final Image image;
    private final CommandGate gate;
    private final CommandApplier applier;
    private final long expectedJoinPosition;
    private final ControlledFragmentHandler fragmentHandler = this::onFragment;
    private final Runnable startAction;

    /** Agent thread only: set by the fragment handler, thrown by doWork. */
    private Throwable failure;

    /**
     * @param image                the inbound stream image of the sequencer's publication session
     * @param gate                 the recorded position the agent may read up to
     * @param handler              processes each decoded command
     * @param expectedJoinPosition where the image must have joined: the publication's position
     *                             when this run's journal was opened, so no command was missed
     * @param appliedSeq           the seq already applied to the engine's state (0 for an empty book)
     */
    public EngineAgent(Image image, CommandGate gate, CommandHandler handler,
                       long expectedJoinPosition, long appliedSeq) {
        this(image, gate, new CommandApplier(handler, AppliedListener.NO_OP, appliedSeq, expectedJoinPosition),
                expectedJoinPosition, null);
    }

    /**
     * @param image                the inbound stream image of the sequencer's publication session
     * @param gate                 the recorded position the agent may read up to
     * @param applier              applies each command; already advanced by recovery's replay
     * @param expectedJoinPosition where the image must have joined: the publication's position
     *                             when this run's journal was opened, so no command was missed
     * @param startAction          run on the agent's thread before its first poll, or null. Recovery
     *                             publishes the recovered BOOK here, so every ring keeps one producer
     *                             thread (the Disruptor's SINGLE producer, SRS §5.2)
     */
    public EngineAgent(Image image, CommandGate gate, CommandApplier applier, long expectedJoinPosition,
                       Runnable startAction) {
        this.image = image;
        this.gate = gate;
        this.applier = applier;
        this.expectedJoinPosition = expectedJoinPosition;
        this.startAction = startAction;
    }

    /** The seq of the last command the engine fully processed. Readable from any thread. */
    public long appliedSeq() {
        return applier.appliedSeq();
    }

    /** The recording position just after the last applied command. Readable from any thread. */
    public long appliedPosition() {
        return applier.appliedPosition();
    }

    @Override
    public String roleName() {
        return "engine-agent";
    }

    @Override
    public void onStart() {
        if (image.joinPosition() != expectedJoinPosition) {
            throw new IllegalStateException("Engine joined the inbound stream at " + image.joinPosition()
                    + ", expected " + expectedJoinPosition + "; commands would be missed");
        }
        if (startAction != null) {
            startAction.run();
        }
    }

    @Override
    public int doWork() {
        final int fragments = image.boundedControlledPoll(fragmentHandler, gate.limit(), FRAGMENT_LIMIT);
        if (failure != null) {
            throw new AgentTerminationException(failure);
        }
        if (fragments == 0) {
            if (image.isClosed()) {
                throw new AgentTerminationException(new IllegalStateException(
                        "Inbound image closed at position " + image.position() + ", applied seq " + applier.lastSeq()));
            }
            if (!gate.isLive()) {
                throw new AgentTerminationException(new IllegalStateException(
                        "Inbound recording is no longer active at applied seq " + applier.lastSeq()));
            }
        }
        return fragments;
    }

    private ControlledFragmentHandler.Action onFragment(
            DirectBuffer buffer, int offset, int length, Header header) {
        try {
            applier.apply(buffer, offset, header.position());
            return ControlledFragmentHandler.Action.CONTINUE;
        } catch (Throwable t) {
            failure = t;
            return ControlledFragmentHandler.Action.ABORT;
        }
    }
}
