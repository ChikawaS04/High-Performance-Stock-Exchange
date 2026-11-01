package recovery;

import codec.snapshot.MessageHeaderEncoder;
import engine.MatchingEngine;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot directory (P15-5, D14): atomic files named by seq, a checksum trailer, retention,
 * and a newest-valid load that falls back past corrupt, truncated, mislabelled or newer-version
 * files to the previous snapshot.
 */
class SnapshotStoreTest {

    @TempDir
    Path dir;

    private final MatchingEngine engine = SnapshotCodecTest.twoSidedBook();

    private void write(SnapshotStore store, long seq) throws IOException {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(256);
        int length = new SnapshotCodec().encode(buffer, engine, seq, seq * 64, 0L, seq, seq);
        store.write(buffer, length, seq);
    }

    private Path file(long seq) {
        return dir.resolve(SnapshotStore.fileName(seq));
    }

    @Test
    void theNewestValidSnapshotIsLoaded() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        write(store, 20);
        RestoredSnapshot loaded = store.loadNewestValid();
        assertEquals(20L, loaded.inboundSeq());
        assertEquals(1_280L, loaded.inboundPosition());
        assertEquals(EngineStates.describe(engine).size() - 1, loaded.orders().size());
        assertEquals("snapshot-00000000000000000020.sbe", file(20).getFileName().toString());
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "no temporary file is left behind");
        }
    }

    @Test
    void aCorruptNewestSnapshotFallsBackToThePreviousOne() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        write(store, 20);
        byte[] bytes = Files.readAllBytes(file(20));
        bytes[SnapshotCodec.HEADER_LENGTH + 3] ^= 0x40;   // one bit inside an order
        Files.write(file(20), bytes);
        assertEquals(10L, store.loadNewestValid().inboundSeq());
    }

    @Test
    void aTruncatedNewestSnapshotFallsBackToThePreviousOne() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        write(store, 20);
        byte[] bytes = Files.readAllBytes(file(20));
        Files.write(file(20), java.util.Arrays.copyOf(bytes, bytes.length - 30));
        assertEquals(10L, store.loadNewestValid().inboundSeq());

        Files.write(file(20), new byte[5]);
        assertEquals(10L, store.loadNewestValid().inboundSeq(), "a stub file is ignored too");
    }

    @Test
    void aSnapshotFromANewerBuildFallsBack() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        write(store, 20);
        rewriteBody(file(20), body -> new MessageHeaderEncoder()
                .wrap(new org.agrona.concurrent.UnsafeBuffer(body), 0).version(9));
        assertEquals(10L, store.loadNewestValid().inboundSeq());
    }

    @Test
    void aNameThatDisagreesWithItsContentsFallsBack() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        write(store, 20);
        Files.move(file(20), file(30));
        assertEquals(10L, store.loadNewestValid().inboundSeq());
    }

    @Test
    void incompleteNamesAndTemporariesAreIgnoredAndTemporariesDeleted() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        write(store, 10);
        Files.copy(file(10), dir.resolve(SnapshotStore.fileName(40) + ".tmp"));
        Files.copy(file(10), dir.resolve("snapshot-50.sbe"));
        assertEquals(10L, store.loadNewestValid().inboundSeq());
        assertFalse(Files.exists(dir.resolve(SnapshotStore.fileName(40) + ".tmp")));
    }

    @Test
    void noValidSnapshotMeansNone() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        assertNull(store.loadNewestValid(), "an absent directory");
        write(store, 10);
        Files.write(file(10), new byte[100]);
        assertNull(store.loadNewestValid());
    }

    @Test
    void onlyTheNewestThreeAreKept() throws IOException {
        SnapshotStore store = new SnapshotStore(dir);
        for (long seq = 10; seq <= 50; seq += 10) write(store, seq);
        try (var files = Files.list(dir)) {
            assertEquals(List.of(SnapshotStore.fileName(30), SnapshotStore.fileName(40), SnapshotStore.fileName(50)),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertTrue(store.exists(50));
        assertFalse(store.exists(10));
    }

    /** Edits the body and rewrites a correct trailer, so only the edit can make the file invalid. */
    private static void rewriteBody(Path file, java.util.function.Consumer<byte[]> edit) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - SnapshotStore.TRAILER_LENGTH;
        byte[] body = java.util.Arrays.copyOf(bytes, bodyLength);
        edit.accept(body);
        CRC32C crc = new CRC32C();
        crc.update(body, 0, bodyLength);
        ByteBuffer out = ByteBuffer.allocate(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(body).putLong(bodyLength).putInt((int) crc.getValue());
        Files.write(file, out.array());
    }
}
