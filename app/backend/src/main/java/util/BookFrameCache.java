package util;

/**
 * Single-slot, thread-safe cache of the most recently published BOOK frame (its serialized JSON
 * text), shared between the outbound publisher that writes it and the per-channel WebSocket handler
 * that reads it on connect (snapshot-on-connect).
 *
 * <p>Why a cache rather than reading the live book on connect: the order book is owned by the
 * matching-engine thread, and reading its {@code TreeMap} from the Netty worker thread when a
 * client connects would be a cross-thread read against live mutation — the exact data race the
 * project's single-writer discipline exists to prevent. Instead the publisher, which already
 * serializes every BOOK frame on its own consumer thread, stashes the latest text here, and the
 * handler reads it on the worker thread.
 *
 * <p>The field is {@code volatile}: the stored {@code String} is immutable, so a volatile write and
 * read are enough to publish it safely across the two threads with no lock. A {@code null} value
 * means no BOOK frame has been published yet (an empty venue), in which case a connecting client is
 * simply sent nothing and receives its first book on the next published snapshot.
 */
public final class BookFrameCache {

    private volatile String latest;

    /** Store the latest serialized BOOK frame. Called by the publisher's snapshot consumer. */
    public void set(String bookFrameJson) {
        this.latest = bookFrameJson;
    }

    /** The latest serialized BOOK frame, or {@code null} if none has been published yet. */
    public String get() {
        return latest;
    }
}
