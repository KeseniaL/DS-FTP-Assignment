import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/* 
* the purpose of this class is to provide helper methods for both sender and receiver to handle sequence numbers and packetization for GBN protocol
*/
public class Util {

    // helper to increment sequence number modulo 128
    public static int inc(int seq) {
        return (seq + 1) % 128;
    }

    // calculates distance between two sequences while safely navigating wraparound
    // (useful for checking if a seq is within our GBN window).
    // returns distance from base to seq, handling wraparound.
    // equivalent to (seq - base) % 128 but safe for negative differences.
    public static int seqDist(int seq, int base) {
        return (seq - base + 128) % 128;
    }

    // helper to check if a sequence number is within a window
    // window starts at 'base' and has size 'N'
    public static boolean inWindow(int seq, int base, int N) {
        int dist = seqDist(seq, base);
        return dist >= 0 && dist < N;
    }

    // helper to parse file into a list of DSPacket objects
    // reads the entire file, chunks it into exact 124-byte payloads (except the
    // last), applies inc(seq) to each chunk starting at 1, and wraps them all into
    // DSPackets.
    public static List<DSPacket> chunkFileIntoPackets(String inputFile) throws IOException {
        File f = new File(inputFile);
        if (!f.exists() || !f.isFile()) {
            throw new IOException("Input file not found: " + inputFile);
        }

        List<DSPacket> packets = new ArrayList<>();
        if (f.length() == 0) {
            return packets; // Empty list
        }

        int seq = 1;
        try (FileInputStream in = new FileInputStream(f)) {
            while (true) {
                byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];
                int read = in.read(chunk);
                if (read == -1)
                    break;

                byte[] payload = new byte[read];
                System.arraycopy(chunk, 0, payload, 0, read);
                DSPacket pk = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
                packets.add(pk);

                seq = inc(seq);
            }
        }
        return packets;
    }
}
