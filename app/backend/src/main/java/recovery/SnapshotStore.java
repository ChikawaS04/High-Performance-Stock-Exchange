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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private final Path dir;
    private final int keep;
    private final ByteBuffer trailer = ByteBuffer.allocate(TRAILER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();

    public SnapshotStore(Path dir) {
        this(dir, DEFAULT_KEEP);
    }

    public SnapshotStore(Path dir, int keep) {
        if (keep < 1) throw new IllegalArgumentException("keep must be >= 1, got " + keep);
        this.dir = dir;
        this.keep = keep;
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

    /** Deletes all but the newest {@code keep} snapshots. A failed delete is logged and left. */
    void prune() throws IOException {
        List<Path> snapshots = listByNewest();
        for (int i = keep; i < snapshots.size(); i++) {
            try {
                Files.deleteIfExists(snapshots.get(i));
            } catch (IOException e) {
                log.warn("Could not delete old snapshot {}: {}", snapshots.get(i), e.toString());
            }
        }
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
        deleteTemporaries();
        for (Path file : listByNewest()) {
            try {
                RestoredSnapshot snapshot = read(file);
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
