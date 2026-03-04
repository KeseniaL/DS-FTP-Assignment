import java.io.*;
import java.net.*;

/*Establishing sender class, this is for the Mars rover, responsible for:
- reading command line arguments
- set up udp sockets (one for each, send & recieve)
- set up/perform handshake (sot then wait for ack)
- send files using stop and wait or go back n
- retransmit any packets if ack not recieved
- send eot pack to close connection
*/

public class Sender {

    // all packets are always 128 bytes on wire (DSPacket enforces this)
    private static final int PACKET_SIZE = DSPacket.MAX_PACKET_SIZE;

    public static void main(String[] args) {

        // syntax:
        // java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file>
        // <timeout_ms> [window_size]
        if (args.length != 5 && args.length != 6) {
            System.err.println(
                    "Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        // setting up argument parameters
        String rcvIp = args[0];
        int rcvDataPrt = parsePort(args[1], "rcv_data_port");
        int sndrAckPrt = parsePort(args[2], "sender_ack_port"); // FIX: was args[1]
        String inputFile = args[3];
        int timeoutMS = parsePositiveInt(args[4], "timeout_ms");

        // intro for Go-Back-N (Person 2 later)
        boolean useGBN = (args.length == 6);
        int windowSze = 0;

        if (useGBN) {
            windowSze = parsePositiveInt(args[5], "window_size");

            // assignment rule: window must be multiple of 4 and <= 128
            if (windowSze > 128 || windowSze % 4 != 0) {
                System.err.println("Error: window_size must be a multiple of 4 and <= 128.");
                System.exit(1);
            }
        }

        // make receiver IP into InetAddress (UDP needs this)
        InetAddress rcvAddr;
        try {
            rcvAddr = InetAddress.getByName(rcvIp);
        } catch (UnknownHostException e) {
            System.err.println("Error: invalid rcv_ip: " + rcvIp);
            return;
        }

        // make two sockets:
        // - sendSocket: sends SOT/DATA/EOT to receiver data port
        // - ackSocket: listens on sender_ack_port for ACKs
        try (DatagramSocket sendSocket = new DatagramSocket();
                DatagramSocket ackSocket = new DatagramSocket(sndrAckPrt)) {

            // timeout triggers retransmission
            ackSocket.setSoTimeout(timeoutMS);

            // measuring time from sending SOT to receiving EOT ACK
            long t0 = System.nanoTime();

            // P1: Handshake
            if (!doHandshake(sendSocket, ackSocket, rcvAddr, rcvDataPrt)) {
                System.out.println("Unable to transfer file.");
                return;
            }

            // P2: Transfer
            boolean ok;
            int lastDataSeq; // needed for EOT sequence

            if (!useGBN) {
                // Stop-and-Wait
                lastDataSeq = runStopandWaitSender(sendSocket, ackSocket, rcvAddr, rcvDataPrt, inputFile);
                ok = (lastDataSeq >= 0);
            } else {
                lastDataSeq = runGBNSender(sendSocket, ackSocket, rcvAddr, rcvDataPrt, inputFile, windowSze);
                ok = (lastDataSeq >= 0);
            }

            if (!ok) {
                System.out.println("Unable to transfer file.");
                return;
            }

            // EOT sequence = lastDataSeq + 1 (mod 128)
            int eotSeq = (lastDataSeq + 1) % 128;

            // P3: Teardown (EOT)
            if (!doTeardownEOT(sendSocket, ackSocket, rcvAddr, rcvDataPrt, eotSeq)) {
                System.out.println("Unable to transfer file.");
                return;
            }

            // print timing
            long t1 = System.nanoTime();
            double seconds = (t1 - t0) / 1_000_000_000.0;
            System.out.printf("Total Transmission Time: %.2f seconds%n", seconds);

        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        }
    }

    // handshake: send SOT, wait for ACK0, timeout -> resend, 3 timeouts -> fail
    private static boolean doHandshake(DatagramSocket sendSocket, DatagramSocket ackSocket,
            InetAddress rcvAddr, int rcvDataPrt) {
        int consecutiveTimeouts = 0;

        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, new byte[0]);

        while (true) {
            try {
                // send SOT
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, sot);
                System.out.println("SEND SOT seq=0");

                // wait for ACK0
                DSPacket ack = receivePacket(ackSocket);

                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("RCV ACK seq=0");
                    return true;
                }

                // ignore wrong packets and keep waiting

            } catch (SocketTimeoutException te) {
                consecutiveTimeouts++;
                System.out.println("TIMEOUT waiting for SOT ACK (count=" + consecutiveTimeouts + ")");
                if (consecutiveTimeouts >= 3)
                    return false;
            } catch (IOException ioe) {
                System.err.println("Handshake IO error: " + ioe.getMessage());
                return false;
            }
        }
    }

    // teardown: send EOT, wait for ACK(eotSeq), timeout -> resend, 3 timeouts ->
    // fail
    private static boolean doTeardownEOT(DatagramSocket sendSocket, DatagramSocket ackSocket,
            InetAddress rcvAddr, int rcvDataPrt, int eotSeq) {
        int consecutiveTimeouts = 0;

        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, new byte[0]);

        while (true) {
            try {
                // send EOT
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, eot);
                System.out.println("SEND EOT seq=" + eotSeq); // FIX: removed extra "0"

                // wait for ACK(eotSeq)
                DSPacket ack = receivePacket(ackSocket);

                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    System.out.println("RCV ACK seq=" + eotSeq);
                    return true;
                }

            } catch (SocketTimeoutException te) {
                consecutiveTimeouts++;
                System.out.println("TIMEOUT waiting for EOT ACK (count=" + consecutiveTimeouts + ")");
                if (consecutiveTimeouts >= 3)
                    return false;
            } catch (IOException ioe) {
                System.err.println("Teardown IO error: " + ioe.getMessage());
                return false;
            }
        }
    }

    // Stop and Wait Sender implementation
    /*- send file data in 124 byte chunks
    - seq starts at 1
    - send DATA(seq) and wait for matching ACK(seq)
    - timeout -> resend same DATA
    - 3 timeouts on same packet -> fail
    - return lastDataSeq on success
    */
    private static int runStopandWaitSender(DatagramSocket sendSocket, DatagramSocket ackSocket, InetAddress rcvAddr,
            int rcvDataPrt, String inputFile) {

        File f = new File(inputFile);

        // if file path wrong -> fail early
        if (!f.exists() || !f.isFile()) {
            System.err.println("Error: input_file not found: " + inputFile);
            return -1;
        }

        // empty file case -> no DATA packets; lastDataSeq treated as 0 so EOT seq
        // becomes 1
        if (f.length() == 0) {
            return 0;
        }

        int seq = 1; // first DATA packet uses seq=1
        int lastDataSeq = 0;

        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];

            while (true) {
                int read = in.read(chunk);
                if (read == -1)
                    break;

                // exact payload size (final chunk can be < 124)
                byte[] payload = new byte[read];
                System.arraycopy(chunk, 0, payload, 0, read);

                DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

                // timeout counter for THIS packet
                int consecutiveTimeoutsSamePkt = 0;

                while (true) {
                    try {
                        // send DATA
                        sendPacket(sendSocket, rcvAddr, rcvDataPrt, dataPkt);
                        System.out.println("SEND DATA seq=" + seq + " len=" + read);

                        // wait for ACK(seq)
                        DSPacket ack = receivePacket(ackSocket);

                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                            System.out.println("RCV ACK seq=" + seq);

                            lastDataSeq = seq;
                            seq = (seq + 1) % 128; // advance seq only after correct ACK
                            break;
                        }

                        // ignore wrong ACK and keep waiting

                    } catch (SocketTimeoutException te) {
                        consecutiveTimeoutsSamePkt++;
                        System.out.println("TIMEOUT seq=" + seq + " (count=" + consecutiveTimeoutsSamePkt + ")");

                        if (consecutiveTimeoutsSamePkt >= 3) {
                            return -1; // critical failure
                        }
                        // else retransmit same packet
                    }
                }
            }
            return lastDataSeq;

        } catch (IOException e) {
            System.err.println("File/IO error: " + e.getMessage());
            return -1;
        }
    }

    private static int runGBNSender(DatagramSocket sendSocket, DatagramSocket ackSocket,
            InetAddress rcvAddr, int rcvDataPrt,
            String inputFile, int windowSze) {
        java.util.List<DSPacket> allPackets;
        try {
            allPackets = Util.chunkFileIntoPackets(inputFile);
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
            return -1;
        }

        if (allPackets.isEmpty()) {
            return 0; // Empty file special case
        }

        int baseIdx = 0;
        int nextIdx = 0;
        int n = allPackets.size();
        int consecutiveTimeouts = 0;

        while (baseIdx < n) {
            // Send packets while inside window
            while (nextIdx < baseIdx + windowSze && nextIdx < n) {
                java.util.List<DSPacket> group = new java.util.ArrayList<>();
                int groupLimit = Math.min(n, baseIdx + windowSze);
                int packetsLeft = groupLimit - nextIdx;
                int toSend = Math.min(4, packetsLeft);

                for (int i = 0; i < toSend; i++) {
                    group.add(allPackets.get(nextIdx + i));
                }

                java.util.List<DSPacket> permuted = ChaosEngine.permutePackets(group);

                for (DSPacket p : permuted) {
                    try {
                        sendPacket(sendSocket, rcvAddr, rcvDataPrt, p);
                        System.out.println("SEND DATA seq=" + p.getSeqNum() + " len=" + p.getLength());
                    } catch (IOException e) {
                        return -1;
                    }
                }
                nextIdx += toSend;
            }

            try {
                DSPacket ack = receivePacket(ackSocket);
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackSeq = ack.getSeqNum();
                    int baseSeq = allPackets.get(baseIdx).getSeqNum();
                    int dist = Util.seqDist(ackSeq, baseSeq);
                    int unackedCount = nextIdx - baseIdx;

                    if (dist >= 0 && dist < unackedCount) {
                        System.out.println("RCV ACK seq=" + ackSeq);
                        baseIdx += (dist + 1);
                        consecutiveTimeouts = 0;
                    }
                }
            } catch (SocketTimeoutException te) {
                int baseSeq = allPackets.get(baseIdx).getSeqNum();
                consecutiveTimeouts++;
                System.out.println("TIMEOUT seq=" + baseSeq + " (count=" + consecutiveTimeouts + ")");

                if (consecutiveTimeouts >= 3) {
                    return -1;
                }
                nextIdx = baseIdx;
            } catch (IOException e) {
                return -1;
            }
        }

        return allPackets.get(n - 1).getSeqNum();
    }

    // send helper (DSPacket.toBytes() is always 128 bytes)
    private static void sendPacket(DatagramSocket sock, InetAddress addr, int port, DSPacket p) throws IOException {
        byte[] raw = p.toBytes();
        DatagramPacket dp = new DatagramPacket(raw, raw.length, addr, port);
        sock.send(dp);
    }

    // receive helper (always read 128 bytes)
    private static DSPacket receivePacket(DatagramSocket sock) throws IOException {
        byte[] buf = new byte[PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        sock.receive(dp);
        return new DSPacket(dp.getData());
    }

    // parsing helper for ports
    private static int parsePort(String s, String name) {
        int v = parsePositiveInt(s, name);
        if (v < 1 || v > 65535) {
            System.err.println("Error: " + name + " must be 1..65535");
            System.exit(1);
        }
        return v;
    }

    // parsing helper for timeout/window/etc
    private static int parsePositiveInt(String s, String name) {
        try {
            int v = Integer.parseInt(s);
            if (v <= 0)
                throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            System.err.println("Error: " + name + " must be a positive integer.");
            System.exit(1);
            return -1;
        }
    }
}
