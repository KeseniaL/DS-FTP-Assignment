import java.io.*;
import java.net.*;

/*Establishing receiver class,"Earth station" side, responsible for:
 - reading command line args (sender IP, ports, output file, RN)
 - listening on the receiver data port for SOT / DATA / EOT packets
 - sending ACKs back to the sender’s ACK port
 - writing incoming file bytes to the output file (raw binary)
 - handling reliability on the receiver side:
      -stop n wait only accept expected seq, ignore duplicates, resend last ACK
      - go back n (later): buffer out-of-order + send cumulative ACKs
 - using ChaosEngine to simulate ACK loss (drop every RNth ack)
*/

public class Receiver {
    public static void main(String[] args) throws Exception {
        // syntax:
        // java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file>
        // <RN>
        if (args.length != 5) {
            System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        String senderIp = args[0];
        int sndrAckPrt = Integer.parseInt(args[1]);
        int rcvDataPrt = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        InetAddress senderAddr = InetAddress.getByName(senderIp);

        // Set up socket: receiver listens on rcvDataPrt for SOT/DATA/EOT from sender.
        // Receiver sends ACKs back to senderAddr:sndrAckPrt
        DatagramSocket rcvSocket = new DatagramSocket(rcvDataPrt);

        // ACK counter (ChaosEngine uses this to decide when to drop)
        int ackCount = 0;

        // P1: Handshake
        while (true) {
            DSPacket p = recvDSPacket(rcvSocket);

            // FIX: DSPacket has getSeqNum(), not getSeq()
            if (p.getType() == DSPacket.TYPE_SOT && p.getSeqNum() == 0) {
                // after getting SOT: send ACK0 (this might get dropped by chaos)
                ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, 0, rn, ackCount);
                break;
            }
            // ignore anything else til SOT comes
        }

        // Receive loop
        // We will call our new GBN Receiver by default, as it handles both
        // Stop-And-Wait and GBN seamlessly
        ackCount = runGBNReceiver(rcvSocket, senderAddr, sndrAckPrt, outputFile, rn, ackCount);

        rcvSocket.close();
    }

    // Stop-and-Wait Receiver (Kept here for reference /
    @SuppressWarnings("unused")
    private static int runStopAndWaitReceiver(DatagramSocket rcvSocket, InetAddress senderAddr,
            int sndrAckPrt, String outputFile, int rn, int ackCount) throws Exception {
        int expectedSeq = 1;
        int lastInOrder = 0;

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            while (true) {
                DSPacket p = recvDSPacket(rcvSocket);

                if (p.getType() == DSPacket.TYPE_DATA) {
                    int seq = p.getSeqNum();

                    if (seq == expectedSeq) {
                        out.write(p.getPayload());
                        out.flush();

                        ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, seq, rn, ackCount);
                        lastInOrder = seq;
                        expectedSeq = (expectedSeq + 1) % 128;
                    } else {
                        ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, lastInOrder, rn, ackCount);
                    }
                } else if (p.getType() == DSPacket.TYPE_EOT) {
                    ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, p.getSeqNum(), rn, ackCount);
                    break;
                } else if (p.getType() == DSPacket.TYPE_SOT) {
                    ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, 0, rn, ackCount);
                }
            }
        }
        return ackCount;
    }

    // Go-Back-N Receiver (Universal)
    private static int runGBNReceiver(DatagramSocket rcvSocket, InetAddress senderAddr,
            int sndrAckPrt, String outputFile, int rn, int ackCount) throws Exception {
        int expectedSeq = 1;
        byte[][] buffer = new byte[128][];
        boolean[] arrived = new boolean[128];

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            while (true) {
                DSPacket p = recvDSPacket(rcvSocket);

                if (p.getType() == DSPacket.TYPE_DATA) {
                    int seq = p.getSeqNum();
                    int dist = Util.seqDist(seq, expectedSeq);

                    if (dist == 0) {
                        // In-order packet
                        out.write(p.getPayload());
                        expectedSeq = Util.inc(expectedSeq);

                        // Deliver buffered packets that are now in-order
                        while (arrived[expectedSeq]) {
                            out.write(buffer[expectedSeq]);
                            arrived[expectedSeq] = false; // consume
                            expectedSeq = Util.inc(expectedSeq);
                        }
                        out.flush();

                        // Send cumulative ACK (which is expectedSeq - 1)
                        int ackSeq = (expectedSeq - 1 + 128) % 128;
                        ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, ackSeq, rn, ackCount);

                    } else if (dist < 64 && dist > 0) {
                        // Out-of-order within range (dist > 0 ensures it's not a duplicate currently
                        // expected)
                        if (!arrived[seq]) {
                            arrived[seq] = true;
                            buffer[seq] = p.getPayload();
                        }
                        // Re-send cumulative ACK
                        int ackSeq = (expectedSeq - 1 + 128) % 128;
                        ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, ackSeq, rn, ackCount);
                    } else {
                        // Older duplicate (already received). Must re-send cumulative ACK
                        // to unblock the Sender if its ACK was dropped!
                        int ackSeq = (expectedSeq - 1 + 128) % 128;
                        ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, ackSeq, rn, ackCount);
                    }
                } else if (p.getType() == DSPacket.TYPE_EOT) {
                    // Teardown
                    ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, p.getSeqNum(), rn, ackCount);
                    break;
                } else if (p.getType() == DSPacket.TYPE_SOT) {
                    // Sender's SOT ACK was dropped, resend it
                    ackCount = sendACkWithChaos(rcvSocket, senderAddr, sndrAckPrt, 0, rn, ackCount);
                }
            }
        }
        return ackCount;
    }

    // ACK sender with chaos factor
    private static int sendACkWithChaos(DatagramSocket sock, InetAddress senderAddr, int senderAckPort,
            int ackSeq, int rn, int ackCount) throws IOException {
        int newAckCount = ackCount + 1;

        // FIX: newAckCount variable name
        if (ChaosEngine.shouldDrop(newAckCount, rn)) {
            // simulate ACK loss (don't send anything)
            return newAckCount;
        }

        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, ackSeq, new byte[0]);
        sendDSPacket(sock, senderAddr, senderAckPort, ack);
        return newAckCount;
    }

    // send/receive helpers (DSPacket wrapper)
    private static void sendDSPacket(DatagramSocket sock, InetAddress addr, int port, DSPacket p) throws IOException {
        byte[] raw = p.toBytes();
        DatagramPacket dp = new DatagramPacket(raw, raw.length, addr, port);
        sock.send(dp);
    }

    private static DSPacket recvDSPacket(DatagramSocket sock) throws IOException {
        byte[] raw = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(raw, raw.length);
        sock.receive(dp);
        return new DSPacket(dp.getData());
    }
}