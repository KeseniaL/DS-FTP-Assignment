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
        // java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
        if (args.length != 5 && args.length != 6) {
            System.err.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
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

        //make two sockets, one for sending data (sendSocket) and one for recieving ACKs (ackSocket).
        try (DatagramSocket sendSocket = new DatagramSocket();
            DatagramSocket ackSocket = new DatagramSocket(sndrAckPrt)) {
                //timeout here will trigger retransmission
                ackSocket.setSoTimeout(timeoutMS);
                long t0 = System.nanoTime(); //measuring time it takes from sending sot to recieveing eot ack

                // P1: Handshake
                if(!doHandshake(sendSocket,ackSocket,rcvAddr,rcvDataPrt)){
                    System.err.println("Unable to transfer file.");
                    return;
                }
                // P2: Transfer
                boolean ok;
                int lastDataSeq; //we need this to the eot sequence later
               
                if(!useGBN){
                    //Stop and wait 
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
                int eotSeq = (lastDataSeq + 1) % 128; //eot equence has to be lastdata sequence +1 (mod 128)

                // P3: Teardown for the EOT
                if (!doTeardownEOT(sendSocket, ackSocket, rcvAddr,rcvDataPrt, eotSeq)){
                    System.out.println("Unable to transfer file.");
                    return;
                }
                //this will print the timing
                long t1 = System.nanoTime();
                double seconds = (t1-t0)/ 1_000_000_000.0;
                System.out.printf("Total Transmission Time: %.2f second%n", seconds);
            } catch (SocketException e){
                System.err.println("Socket error: " + e.getMessage());
            }
    }
    //class to execute handshake, it will send sot, wait for ack and deals with timeout, if 3 timeout happens, it willfail
    private static boolean doHandshake (DatagramSocket sendSocket, DatagramSocket aSocket, InetAddress rcvAddr,int rcvDataPrt){
        int consecutiveTimeouts = 0;

        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT,0, new byte[0]);

        while (true) { 
            try {
                //send sot
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, sot);
                System.out.println("SEND SOT seq=0");

                //wait for ack
                DSPacket ack = receivePacket(ackSocket);
                //handshake succseful
                if (ack.getType()== DSPacket.TYPE_ACK && ack.getSeqNum() == 0){
                    System.out.println("RCV ACK seq=0");
                    return true;
                }

                //also want to ignore any wrogn packets and keep waiting
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
    // Teardown here has simialr expectations to handshaek setup but with eot rather tahn sot: send eot (seq= eotSeq), wait for ack (seq=eotSeq), timeout will resend eot and 3 consecutive timeouts lead to a failure
    private static boolean doTeardownEOT (DatagramSocket sendSocket, DatagramSocket aSocket, InetAddress rcvAddr,int rcvDataPrt, int eotSeq){
        int consecutiveTimeouts = 0;
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, new byte[0]);

        while (true) { 
            try {
                //send eot
                sendPacket(sendSocket, rcvAddr, rcvDataPrt, eot);
                System.out.println("SEND EOT seq=0" + eotSeq);

                //wait for Ack for Eot
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
//Stop and Wait Sender implementation,
/*recive file data in 124 byte chunks
sequence will start at 1 , send data and wait foir match ack (seq)
similar expecttaion with timeout ouccurence = resend same data, and 3 timeouts = fail
should return LastDataSeq upon success
*/
private static int runStopandWaitSender(DatagramSocket sendSocket, DatagramSocket ackSocket, InetAddress rcvAddr, int rcvDataPrt, String inputFile){
    // dealing with an empty file case: after handshaking, send EOR with Seq=1 immediately. 
    File f = new File(inputFile);
    //if file path wrong, send to fail
    if (!f.exists()|| !f.isFile()){
        System.err.println("Error: input_file not found:"+ inputFile);
        return -1;
    }
    //if file heappend to be empty
    if (f.length()==0){
        return 0; // if last data in sequence is 0 i.e., no data, eotSeq becomes 1
    }

    int seq = 1; //first DATA packet uses Seq=1
    int lastDataSeq = 0;

    try (InputStream in = new BufferedInputStream(new FileInputStream(f))){
        byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];

        while (true) { 
            int read = in.read(chunk);
            if (read == -1) break;
            //make exact length payload (data chunk can be < 124)
            byte[] payload = new byte[read];
            System.arraycopy(chunk,0,payload,0, read);

            DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            //retransmission counter for this exact packet
            int consecutiveTimeoutsSamePkt = 0;

            while (true) { 
                try {
                    //send data
                    sendPacket(sendSocket, rcvAddr, rcvDataPrt, dataPkt);
                    System.out.println("SEND DATA seq=" + seq + "len=" + read);
                   
                    //wait for ack
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

//packet send and receive helper function implementations, always 128 bytes
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
private static int parsePositiveInt(String s, String name){
    try {
       int v = Integer.parseInt(s);
       if (v <=0) throw new NumberFormatException();
       return v; 
    } catch (NumberFormatException e) {
        System.err.println("Error:" + name + "must be a positive integer.");
        System.exit(1);
        return -1;
    }
}

