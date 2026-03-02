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
    private static final int pkt_sze = DSPacket.MAX_PACKET_SIZE;

    public static void main(String[] args){
        //setting up the sender arguments syntax as given: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
        if (args.length != 5 && args.length!=6){
            System.err.println("Usage:java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }
        //setting up argument paramerters for each of port role
        String rcvIp = args[0];
        int rcvDataPrt = parsePort(args[1], "rcv_data_port");
        int sndrAckPrt = parsePort(args[1], "sender_ack_port");
        String inputFile = args[3];
        int timeoutMS = parsePositiveInt(args[4], "timeout_ms");

        //first intro for go back N
        boolean useGBN = (args.length == 6);
        int windowSze = 0;
        if(useGBN){
            windowSze = parsePositiveInt(args[5],"window_size");
            if (windowSze > 128 || windowSze % 4 != 0){ //window size has to be ....******
                System.err.println("Error: window_size must be a multiple of 4 and <=128.");
                System.exit(1);
            }
        }

        InetAddress rcvAddr;
        try {
            rcvAddr = InetAddress.getByName(rcvIp);
        } catch (UnknownHostException e) {
            System.err.println("Error: invalid rcv_ip:" + rcvIp);//throw out error message if ip invalid
            return;
        }

        //Establish two sockets, one for sending data and one for recieving ACKs.
        try (DatagramSocket sendSocket = new DatagramSocket();
            DatagramSocket ackSocket = new DatagramSocket(sndrAckPrt)) {
                ackSocket.setSoTimeout(timeoutMS);
                long t0 = System.nanoTime();

                //Handshake time
                if(!doHandshake(sendSocket,ackSocket,rcvAddr,rcvDataPrt)){
                    System.err.println("Unable to transfer file.");
                    return;
                }
                // transfer file error handling
                boolean ok;
                int lastDataSeq; //we need this to the eot sequence later
                if(!useGBN){
                    lastDataSeq = runStopandWaitSender(sendSocket, ackSocket, rcvAddr, rcvDataPrt, inputFile);
                    ok = (lastDataSeq >= 0);
                }else{ // COME BACK FOR THIS SOMEHOW
                    System.out.println("ADD IN GBN SENDER IMPLEMENTATION");
                    ok = false;
                    lastDataSeq = -1;
                }
                if (!ok){
                    System.out.println("Unable to transfer file.");
                    return;
                }
                int eotSeq = (lastDataSeq + 1) % 128;

                //Teardown for the EOT
                if (!doTeardownEOT(sendSocket, ackSocket, rcvAddr,rcvDataPrt, eotSeq)){
                    System.out.println("Unable to transfer file.");
                    return;
                }

                long t1 = System.nanoTime();
                double seconds = (t1-t0)/ 1_000_000_000.0;
                System.out.printf("Total Transmission Time: %.2f second%n", seconds);
            } catch (SocketException e){
                System.err.println("Socket error: " + e.getMessage());
            }
    }
    //class to execute handshake
    private static boolean doHandshake (DatagramSocket sendSocket, DatagramSocket aSocket, InetAddress rcvAddr,int rcvDataPrt){
        int consecutiveTimeouts = 0;

        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT,0, new byte[0]);

        while (true) { 
            try {
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, sot);
                System.out.println("SEND SOT seq=0");

                DSPacket ack = receivePacket(ackSocket);
                if (ack.getType()== DSPacket.TYPE_ACK && ack.getSeqNum() == 0){
                    System.out.println("RCV ACK seq=0");
                    return true;
                }
            }catch(SocketTimeoutException te) {
                consecutiveTimeouts++;
                System.out.println("TIMEOUT waiting for SOT ACK (count=" + consecutiveTimeouts + ")");
                if (consecutiveTimeouts >= 3) return false; //critical failure
            }catch(IOException ioe){
                System.err.println("Handshake IO error:" + ioe.getMessage());
                return false;
            }
        }
    }
      private static boolean doTeardownEOT (DatagramSocket sendSocket, DatagramSocket aSocket, InetAddress rcvAddr,int rcvDataPrt, int eotSeq){
        int consecutiveTimeouts = 0;
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, new byte[0]);

        while (true) { 
            try {
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, eot);
                System.out.println("SEND EOT seq=0" + eotSeq);

                DSPacket ack = receivePacket(ackSocket);
                if (ack.getType()== DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq){
                    System.out.println("RCV ACK seq=" + eotSeq);
                    return true;
                }
            }catch(SocketTimeoutException te) {
                consecutiveTimeouts++;
                System.out.println("TIMEOUT waiting for EOT ACK (count=" + consecutiveTimeouts + ")");
                if (consecutiveTimeouts >= 3) return false; 
            }catch(IOException ioe){
                System.err.println("Teardown IO error:" + ioe.getMessage());
                return false;
            }
        }
    }
}

//Stop and Wait Sender implementation
private static int runStopandWaitSender(DatagramSocket sendSocket, DatagramSocket ackSocket, InetAddress rcvAddr, int rcvDataPrt, String inputFile){
    // dealing with an empty file case: after handshaking, send EOR with Seq=1 immediately. 
    File f = new File(inputFile);
    if (!f.exists()|| !f.isFile()){
        System.err.println("Error: input_file not found:"+ inputFile);
        return -1;
    }
    if (f.length()==0){
        return 0; // if last dtat in sequence is 0 i.e., no data, eotSeq becomes 1
    }

    int seq = 1; //first DATA packet uses Seq=1
    int lastDataSeq = 0;

    try (InputStream in = new BufferedInputStream(new FileInputStream(f))){
        byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        while (true) { 
            int read = in.read(chunk);
            if (read == -1) break;

            byte[] payload = new byte[read];
            System.arraycopy(chunk,0,payload,0, read);

            DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            int consecutiveTimeoutsSamePkt = 0;

            while (true) { 
                try {
                    sendPacket(sendSocket, rcvAddr, rcvDataPrt, dataPkt);
                    System.out.println("SEND DATA seq=" + seq + "len=" + read);

                    DSPacket ack = receivePacket(ackSocket);
                    if (ack.getType()== DSPacket.TYPE_ACK && ack.getSeqNum() == seq){
                        System.err.println("RCV ACK seq=" + seq);
                        lastDataSeq = seq;
                        seq = (seq+1) % 128;
                        break;
                    }
                } catch (SocketTimeoutException te) {
                    consecutiveTimeoutsSamePkt ++;
                    System.err.println("TIMEOUT seq="+ seq + " (count =" + consecutiveTimeoutsSamePkt+ ")");
                    if(consecutiveTimeoutsSamePkt >= 3){
                        return -1; //critical failure;
                    }
                    //get ready to retransmit same seq
                }
            }
        }
        return lastDataSeq;
    } catch (IOException e){
        System.err.println("File/IO error:" + e.getMessage());
        return -1;
    }
}

//packet send and receive helper function implementations
private static void sendPacket(DatagramSocket sock, InetAddress addr, int port, DSPacket p) throws IOException {
    byte[] raw = p.toBytes();
    DatagramPacket dp = new DatagramPacket(raw, raw.length, addr, port);
    sock.send(dp);
}
private static DSPacket receivePacket(DatagramSocket sock) throws IOException{
    byte[] buf = new byte[PACKET_SIZE];
    DatagramPacket dp = new DatagramPacket(buf, buf.length);
    sock.receive(dp);
    return new DSPacket(dp.getData());
}
//parsing helper function
private static int ParsePort(String s, String name){
    int v = parsePositiveInt(s,name);
    if (v < 1 || v > 65535){
        System.err.println("Error:" + name + "must be 1...65535");
        System.exit(1);
    }
    return v;
}