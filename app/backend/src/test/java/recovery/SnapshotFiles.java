package recovery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Test helper: rewrites snapshot files as an older build wrote them (P16-7). */
public final class SnapshotFiles {

    private static final int MESSAGE_HEADER = 8;
    private static final int V2_EXTRA = SnapshotCodec.HEADER_LENGTH - MESSAGE_HEADER - SnapshotCodec.V1_BLOCK_LENGTH;

    private SnapshotFiles() {
    }

    /**
     * A version 2 snapshot file (body and trailer) as version 1 wrote it: the root block without the
     * three version 2 fields, version 1 in the header, and a new trailer.
     */
    public static byte[] asVersion1(byte[] v2File) {
        int bodyLength = v2File.length - SnapshotStore.TRAILER_LENGTH;
        int rootEnd = MESSAGE_HEADER + SnapshotCodec.V1_BLOCK_LENGTH;
        byte[] body = new byte[bodyLength - V2_EXTRA];
        System.arraycopy(v2File, 0, body, 0, rootEnd);
        System.arraycopy(v2File, rootEnd + V2_EXTRA, body, rootEnd, bodyLength - rootEnd - V2_EXTRA);
        ByteBuffer header = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(0, (short) SnapshotCodec.V1_BLOCK_LENGTH);   // blockLength
        header.putShort(6, (short) 1);                              // version
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        ByteBuffer file = ByteBuffer.allocate(body.length + SnapshotStore.TRAILER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        file.put(body).putLong(body.length).putInt((int) crc.getValue());
        return file.array();
    }
}
