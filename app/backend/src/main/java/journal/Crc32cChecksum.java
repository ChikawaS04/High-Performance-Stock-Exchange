package journal;

import io.aeron.archive.checksum.Checksum;
import org.agrona.UnsafeApi;

/**
 * Standard CRC32C (Castagnoli, reflected, initial and final value 0xFFFFFFFF), the Archive's
 * per-frame record checksum (P15-5). Gives the same value as {@code java.util.zip.CRC32C} and
 * Aeron's own {@code Checksums.crc32c()}, but in plain Java over Agrona's {@link UnsafeApi}.
 *
 * Why not Aeron's: its {@code Checksums} class initialises reflective handles into
 * {@code java.util.zip} for both CRC variants, which needs
 * {@code --add-opens java.base/java.util.zip=ALL-UNNAMED} on every JVM that runs the exchange.
 * The existing {@code --add-exports} flag already covers {@link UnsafeApi}, so this keeps the
 * venue on one JVM flag.
 *
 * Runs on the Archive's thread, once per recorded frame (about 100 bytes per command), and when
 * the Archive verifies a crashed recording's last frame. Stateless and thread-safe; the table is
 * built once.
 */
public final class Crc32cChecksum implements Checksum {

    public static final Crc32cChecksum INSTANCE = new Crc32cChecksum();

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private Crc32cChecksum() {
    }

    @Override
    public int compute(long address, int offset, int length) {
        int crc = 0xFFFFFFFF;
        long p = address + offset;
        long end = p + length;
        for (; p < end; p++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ UnsafeApi.getByte(p)) & 0xFF];
        }
        return ~crc;
    }
}
