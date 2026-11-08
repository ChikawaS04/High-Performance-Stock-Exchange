package benchmark;

import codec.ExecutionEventCodec;
import codec.OrderEventCodec;
import event.ExecutionEvent;
import event.ExecutionEventType;
import event.OrderEvent;
import model.Side;
import org.agrona.BitUtil;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * P15-13 questions A5 and A7: the SBE adapters alone, and an upper bound on what
 * {@code commandSeq} (P15-10) costs.
 *
 * {@code commandSeq} cannot be switched off without a main-source seam: the schema is version 1 and
 * the handler always stores it. It adds three stores per execution event: the handler's
 * {@code commandSeq = seq} field store, the slot's {@code e.commandSeq = commandSeq}, and one
 * aligned {@code putLong} in {@code ExecutionEventCodec.encode}. {@link #longFieldStore} and
 * {@link #alignedPutLong} each bound one of those from above (a store measured with the benchmark
 * call around it), and {@link #executionEncode} bounds the whole encode. The recorded frame stays
 * 160 bytes at 32-byte alignment, so it adds no journal bytes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 5, jvmArgsAppend = {BenchSupport.ADD_EXPORTS, BenchSupport.MISC})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CodecBenchmark {

    /** Where {@code commandSeq} sits in the execution message (P15-10: offset 96 in the block). */
    private static final int COMMAND_SEQ_OFFSET = 8 + 96;

    private final OrderEventCodec orderCodec = new OrderEventCodec();
    private final ExecutionEventCodec executionCodec = new ExecutionEventCodec();
    private UnsafeBuffer orderBuffer;
    private UnsafeBuffer encodedOrder;
    private UnsafeBuffer executionBuffer;
    private UnsafeBuffer encodedExecution;
    private OrderEvent order;
    private OrderEvent decodedOrder;
    private ExecutionEvent execution;
    private ExecutionEvent decodedExecution;
    private long seq;

    @Setup(Level.Trial)
    public void setUp() {
        orderBuffer = aligned(OrderEventCodec.MAX_ENCODED_LENGTH);
        encodedOrder = aligned(OrderEventCodec.MAX_ENCODED_LENGTH);
        executionBuffer = aligned(ExecutionEventCodec.ENCODED_LENGTH);
        encodedExecution = aligned(ExecutionEventCodec.ENCODED_LENGTH);
        order = BenchSupport.newOrder(1L, Side.BUY, 1L);
        decodedOrder = new OrderEvent();
        orderCodec.encode(order, 1L, encodedOrder, 0);

        execution = new ExecutionEvent();
        execution.seq = 1L;
        execution.eventType = ExecutionEventType.ORDER_FILLED;
        execution.orderId = 2L;
        execution.tradeId = 1L;
        execution.price = BenchSupport.PRICE_UNITS;
        execution.filledQuantity = BenchSupport.QTY;
        execution.remainingQuantity = 0L;
        execution.aggressorOrderId = 2L;
        execution.passiveOrderId = 1L;
        execution.aggressorSide = Side.SELL;
        execution.aggressorParticipantId = 1L;
        execution.passiveParticipantId = 1L;
        execution.businessDate = -1;
        execution.timestamp = 1L;
        execution.commandSeq = 2L;
        decodedExecution = new ExecutionEvent();
        executionCodec.encode(execution, encodedExecution, 0);
    }

    private static UnsafeBuffer aligned(int length) {
        return new UnsafeBuffer(BufferUtil.allocateDirectAligned(
                BitUtil.align(length, BitUtil.CACHE_LINE_LENGTH), BitUtil.CACHE_LINE_LENGTH));
    }

    @Benchmark
    public int orderEncode() {
        order.orderId = ++seq;
        return orderCodec.encode(order, seq, orderBuffer, 0);
    }

    @Benchmark
    public long orderDecode() {
        return orderCodec.decode(encodedOrder, 0, decodedOrder);
    }

    @Benchmark
    public int executionEncode() {
        execution.seq = ++seq;
        execution.commandSeq = seq;
        return executionCodec.encode(execution, executionBuffer, 0);
    }

    @Benchmark
    public long executionDecode() {
        executionCodec.decode(encodedExecution, 0, decodedExecution);
        return decodedExecution.seq;
    }

    @Benchmark
    public long alignedPutLong() {
        final long v = ++seq;
        executionBuffer.putLong(COMMAND_SEQ_OFFSET, v);
        return v;
    }

    @Benchmark
    public long longFieldStore() {
        final long v = ++seq;
        execution.commandSeq = v;
        return v;
    }
}
