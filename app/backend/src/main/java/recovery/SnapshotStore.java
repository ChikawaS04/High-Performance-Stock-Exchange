package recovery;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * The snapshot directory (P15-5, D14, SRS §3.10): {@code EXCHANGE_DATA_DIR/snapshots}.
 *
 * A file is named {@code snapshot-<inbound seq, 20 digits>.sbe} and holds the snapshot body
 * ({@link SnapshotCodec}) followed by a 12-byte little-endian trailer: the body length (int64)
 * and the CRC32C of the body (int32). It is written whole to {@code <name>.tmp}, closed, and only
 * then renamed into place atomically in the same directory, so a partly written snapshot never
 * carries a snapshot name.
 *
 * No fsync, by decision: the journal is page-cache durable ({@code fileSyncLevel 0}, SRS §3.9),
 * and forcing a snapshot to disk ahead of the journal would make "snapshot beyond journal after a
 * power loss" more likely. A process crash cannot lose a renamed file; a power loss that tears
 * one is caught by the checksum, and recovery falls back to the previous snapshot.
 *
 * The newest {@link #DEFAULT_KEEP} snapshots by seq are kept; older ones are deleted after each
 * successful write. A file whose name, length, checksum, version or contents is wrong is
 * ignored with a WARN naming the reason, and loading moves on to the next older one.
 *
 * Close snapshots (P16-7, R3). Journal retention takes its window from the snapshots taken at
 * session close, so besides the newest few, the store keeps the close snapshot (the lowest seq
 * carrying that close date) of each of the newest {@code keepCloses} distinct close dates. A close
 * snapshot is recognised from its root block ({@link SnapshotCodec#peekCloseDate}), so its file name
 * is the ordinary one and {@link #exists(long)} (the shutdown snapshot's skip) is unchanged.
 *
 * A snapshot whose seq already has a valid file is not rewritten (P16-7): replay after a restart
 * passes the same seqs again, the state is identical by determinism, and the file written live
 * carries the outbound position that a replay-time snapshot cannot know.
 *
 * Writes come from one thread at a time (the snapshot writer, or shutdown after it is idle).
 * Logging here is fine: nothing in this class runs on the engine thread.
 */
public final class SnapshotStore implements SnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    /** Snapshots kept: the newest, plus two to fall back on if it is corrupt. */
    public static final int DEFAULT_KEEP = 3;
    /** Body length (int64) then CRC32C (int32), little-endian. */
    static final int TRAILER_LENGTH = 12;

    private static final Pattern NAME = Pattern.compile("snapshot-(\\d{20})\\.sbe");
    private static final String TEMP_SUFFIX = ".tmp";

    /** Bytes read from a file to recognise a close snapshot: the header and root block, with room to spare. */
    private static final int PEEK_LENGTH = 256;

    private final Path dir;
    private final int keep;
    private final int keepCloses;
    private final ByteBuffer trailer = ByteBuffer.allocate(TRAILER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();

    public SnapshotStore(Path dir) {
        this(dir, DEFAULT_KEEP);
    }

    public SnapshotStore(Path dir, int keep) {
        this(dir, keep, 0);
    }

    /**
     * @param keep       the newest snapshots kept, whatever they are
     * @param keepCloses close dates whose close snapshot is kept besides them (P16-7: the retention
     *                   window's closes, {@code JOURNAL_RETENTION_DAYS + 1}); 0 keeps none
     */
    public SnapshotStore(Path dir, int keep, int keepCloses) {
        if (keep < 1) throw new IllegalArgumentException("keep must be >= 1, got " + keep);
        if (keepCloses < 0) throw new IllegalArgumentException("keepCloses must be >= 0, got " + keepCloses);
        this.dir = dir;
        this.keep = keep;
        this.keepCloses = keepCloses;
    }

    public Path dir() {
        return dir;
    }

    /** The file name for the snapshot taken after inbound seq {@code seq}. */
    public static String fileName(long seq) {
        return String.format("snapshot-%020d.sbe", seq);
    }

    /** The seq in a snapshot file name, or -1 if the name is not a complete snapshot name. */
    static long seqOf(String name) {
        Matcher m = NAME.matcher(name);
        return m.matches() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** True if a snapshot of exactly this seq is already in place. */
    public boolean exists(long seq) {
        return Files.exists(dir.resolve(fileName(seq)));
    }

    @Override
    public void write(DirectBuffer body, int length, long seq) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName(seq));
        Path temp = dir.resolve(fileName(seq) + TEMP_SUFFIX);
        if (Files.exists(target) && isValid(target)) {
            log.info("Snapshot {} already exists and is valid; kept", target.getFileName());
            return;
        }

        byte[] copy = new byte[length];
        body.getBytes(0, copy);
        crc.reset();
        crc.update(copy, 0, length);
        trailer.clear();
        trailer.putLong(0, length).putInt(8, (int) crc.getValue());

        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(copy);
                while (bytes.hasRemaining()) channel.write(bytes);
                while (trailer.hasRemaining()) channel.write(trailer);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        log.info("Snapshot written: {} ({} bytes)", target.getFileName(), length + TRAILER_LENGTH);
        prune();
    }

    /**
     * Deletes all but the newest {@code keep} snapshots and the close snapshot of each of the newest
     * {@code keepCloses} close dates. A failed delete is logged and left.
     */
    void prune() throws IOException {
        List<Path> snapshots = listByNewest();
        if (snapshots.size() <= keep) return;
        Set<Path> kept = new HashSet<>(snapshots.subList(0, keep));
        if (keepCloses > 0) {
            // Newest first, so a date's close snapshot is the last file seen with that date.
            Map<Integer, Path> closeOf = new LinkedHashMap<>();
            for (Path file : snapshots) {
                int date = peekCloseDate(file);
                if (date > 0) closeOf.put(date, file);
            }
            List<Integer> dates = new ArrayList<>(closeOf.keySet());
            dates.sort(Comparator.reverseOrder());
            for (int i = 0; i < Math.min(keepCloses, dates.size()); i++) {
                kept.add(closeOf.get(dates.get(i)));
            }
        }
        for (Path file : snapshots) {
            if (kept.contains(file)) continue;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete old snapshot {}: {}", file, e.toString());
            }
        }
    }

    /** The close date in a snapshot file's root block, or -1 (not a close snapshot, or unreadable). */
    static int peekCloseDate(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(PEEK_LENGTH);
            while (head.hasRemaining() && channel.read(head) > 0) {
                // keep reading until the buffer is full or the file ends
            }
            return SnapshotCodec.peekCloseDate(new UnsafeBuffer(head.array()), head.position());
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean isValid(Path file) {
        try {
            read(file);
            return true;
        } catch (IOException | InvalidSnapshotException e) {
            return false;
        }
    }

    /**
     * Every valid snapshot in the directory, newest seq first (P16-7: what journal retention counts
     * and floors on). Invalid files are left out silently (recovery logs them); a file that disappears
     * while it is read was pruned and is left out too.
     *
     * @throws IOException if the directory cannot be listed or a file cannot be read
     */
    public List<RestoredSnapshot> listValid() throws IOException {
        List<RestoredSnapshot> found = new ArrayList<>();
        for (Path file : listByNewest()) {
            try {
                found.add(read(file));
            } catch (NoSuchFileException e) {
                // pruned by the writer between the listing and the read
            } catch (InvalidSnapshotException e) {
                // unusable; recovery reports it
            }
        }
        return found;
    }

    /** Every file with a complete snapshot name, newest seq first. */
    List<Path> listByNewest() throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) return found;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (seqOf(file.getFileName().toString()) >= 0) found.add(file);
            }
        }
        found.sort(Comparator.comparingLong((Path p) -> seqOf(p.getFileName().toString())).reversed());
        return found;
    }

    /**
     * The newest valid snapshot, or null if there is none. Leftover temporary files (a write cut
     * short by a crash) are deleted first. Every invalid file is logged with its reason and left
     * in place; retention removes it once newer snapshots exist.
     */
    public RestoredSnapshot loadNewestValid() throws IOException {
        return loadNewestValid(Long.MAX_VALUE);
    }

    /**
     * The newest valid snapshot whose outbound seq is at or below {@code maxOutboundSeq} (the last
     * seq in the outbound recording, P15-6), or null if there is none. A newer snapshot above it was
     * taken while the recorder had not yet written every event it covers; recovering from it would
     * leave those events out of the outbound record for good, because replay starts after the
     * snapshot. It is skipped with a WARN and left in place, so replay from an older snapshot (or
     * from the journal's start) regenerates them.
     */
    public RestoredSnapshot loadNewestValid(long maxOutboundSeq) throws IOException {
        return loadNewestValid(maxOutboundSeq, 0L);
    }

    /**
     * As {@link #loadNewestValid(long)}, also skipping a snapshot whose inbound position is below
     * {@code minInboundPosition}, the inbound recording's start (P16-7): once journal retention has
     * moved the start, an older snapshot's replay would begin at a position that no longer exists. It
     * is skipped with a WARN and left in place.
     */
    public RestoredSnapshot loadNewestValid(long maxOutboundSeq, long minInboundPosition) throws IOException {
        deleteTemporaries();
        for (Path file : listByNewest()) {
            try {
                RestoredSnapshot snapshot = read(file);
                if (snapshot.inboundPosition() < minInboundPosition) {
                    log.warn("Skipping snapshot {}: its inbound position {} is before the journal's start {}, which"
                                    + " journal retention has moved past it", file.getFileName(),
                            snapshot.inboundPosition(), minInboundPosition);
                    continue;
                }
                if (snapshot.outboundLastSeq() > maxOutboundSeq) {
                    log.warn("Skipping snapshot {}: it records outbound seq {} but the outbound recording ends at {};"
                                    + " the recorder had not written events {} to {} when the venue stopped, so an"
                                    + " older snapshot is used and replay regenerates them",
                            file.getFileName(), snapshot.outboundLastSeq(), maxOutboundSeq, maxOutboundSeq + 1,
                            snapshot.outboundLastSeq());
                    continue;
                }
                log.info("Using snapshot {}: inbound seq {}, {} resting orders",
                        file.getFileName(), snapshot.inboundSeq(), snapshot.orders().size());
                return snapshot;
            } catch (InvalidSnapshotException e) {
                log.warn("Ignoring snapshot {}: {}", file, e.getMessage());
            }
        }
        return null;
    }

    /** Reads and validates one snapshot file. */
    static RestoredSnapshot read(Path file) throws IOException, InvalidSnapshotException {
        long expectedSeq = seqOf(file.getFileName().toString());
        if (expectedSeq < 0) {
            throw new InvalidSnapshotException("incomplete or foreign file name");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < SnapshotCodec.HEADER_LENGTH + TRAILER_LENGTH) {
            throw new InvalidSnapshotException("truncated: " + bytes.length + " bytes");
        }
        int bodyLength = bytes.length - TRAILER_LENGTH;
        ByteBuffer tail = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long recordedLength = tail.getLong(bodyLength);
        int recordedCrc = tail.getInt(bodyLength + 8);
        if (recordedLength != bodyLength) {
            throw new InvalidSnapshotException("truncated or extended: trailer says " + recordedLength
                    + " body bytes, file holds " + bodyLength);
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bodyLength);
        if ((int) crc.getValue() != recordedCrc) {
            throw new InvalidSnapshotException("checksum mismatch");
        }
        RestoredSnapshot snapshot = SnapshotCodec.decode(new UnsafeBuffer(bytes), bodyLength);
        if (snapshot.inboundSeq() != expectedSeq) {
            throw new InvalidSnapshotException("name says seq " + expectedSeq + ", contents say " + snapshot.inboundSeq());
        }
        return snapshot;
    }

    private void deleteTemporaries() throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + TEMP_SUFFIX)) {
            for (Path file : files) {
                log.warn("Deleting incomplete snapshot {}", file);
                Files.deleteIfExists(file);
            }
        }
    }
}
