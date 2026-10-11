import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeMap;

public class Sender {
 
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: java Sender <receiverAddress> <port> <outputFilePath>");
            System.exit(1);
        }
        String receiverAddress = args[0];
        int port = Integer.parseInt(args[1]);
        File outputFile = new java.io.File(args[2]);
        System.out.println("Sender is running. Connecting to receiver at " + receiverAddress + ":" + port);
        try {
            Object.connectedInfo info = new Sender().ConnectToReceiver(receiverAddress, port, outputFile);
            if (info != null) {
                new Sender().sendFile(info, outputFile);
                System.out.println("Connected to receiver: " + info.sharedSequenceNumber);
            } else {
                System.err.println("Failed to connect to receiver.");
            }
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    // Function to connect to the receiver by performing a three-way handshake using SYN and ACK packets
    // @param receiverAddress The IP address of the receiver to connect to
    // @param port The port number of the receiver to connect to
    // @param outputFile The File object representing the file to be sent to the receiver
    // @return An Object.connectedInfo object containing the connection information if the connection is successful, or null if the connection fails
    public Object.connectedInfo ConnectToReceiver(String receiverAddress, int port, java.io.File outputFile) throws java.io.IOException {

        Inet4Address receiverInetAddress = (Inet4Address) Inet4Address.getByName(receiverAddress);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(3000);
        int senderSequenceNumber = new Random().nextInt(65536);

        try{
            Object.Packet synAckpacket = null;
            DatagramPacket datagramPacket = null;
            int attempts = 0;
            // Try up to 3 times to send SYN and receive SYN-ACK
            while (attempts < 3) {
                attempts++;
                //Send SYN packet (same sequence number for retries)
                Object.Packet synPacket = new Object.Packet(0, senderSequenceNumber, true, false, false, false, new byte[0]);
                Utils.sendPacket(socket, synPacket, receiverInetAddress, port);
                System.out.println("Sent SYN packet (attempt " + attempts + "): " + synPacket.sequenceNumber);

                try {
                    //Wait for SYN-ACK response
                    datagramPacket = Utils.receiveDatagramPacket(socket);
                    synAckpacket = Object.Packet.fromBytes(Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength()));
                    if (!synAckpacket.isSYN() || !synAckpacket.isACK()) {
                        throw new IOException("Expected SYN-ACK packet but received: " + Utils.formatPacket(synAckpacket));
                    }
                    break;
                } catch (java.net.SocketTimeoutException ste) {
                    System.err.println("Timeout waiting for SYN-ACK (attempt " + attempts + ")");
                    if (attempts < 3) {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { /* ignore */ }
                    }
                }
            }

            // If SYN-ACK is still null after 3 attempts, give up on the connection
            if (synAckpacket == null) {
                System.err.println("Failed to receive SYN-ACK after 3 attempts. Abandoning connection.");
                socket.close();
                return null;
            }

            int receiverSequenceNumber = synAckpacket.getSequenceNumber();
            System.out.println("Received SYN-ACK packet with receiver sequence: " + receiverSequenceNumber);
            senderSequenceNumber = Utils.incrementSequenceNumber(senderSequenceNumber);

            //Send SYN packet to complete the handshake
            Object.Packet syn2Packet = new Object.Packet(0, senderSequenceNumber, true, false, false, false, new byte[0]);
            Utils.sendPacket(socket, syn2Packet, receiverInetAddress, port);
            System.out.println("Sent SYN packet: " + syn2Packet.sequenceNumber);

            System.out.println("Connection established with receiver. Sender seq: " + senderSequenceNumber + ", Receiver seq: " + receiverSequenceNumber);
            return new Object.connectedInfo(socket, receiverInetAddress, port, senderSequenceNumber, receiverSequenceNumber);
        }catch(Exception e){
            System.err.println("Error during connection establishment: " + e.getMessage());
            try { socket.close(); } catch (Exception ex) { /* ignore */ }
            return null;
        }
    }

    // Function to send a file to the receiver using the established connection information
    // @param connectedInfo The Object.connectedInfo object containing the connection information for the sender and receiver
    // @param outputFile The File object representing the file to be sent to the receiver
    public void sendFile(Object.connectedInfo connectedInfo, File outputFile) {

        int packetSent = 0, bytes = 0;

        try {
            DatagramSocket socket = connectedInfo.socket;
            socket.setSoTimeout(3000);

            byte[] fileData = Files.readAllBytes(outputFile.toPath());

            if (fileData.length == 0) {
                closeConnection(socket, connectedInfo.address, connectedInfo.port, connectedInfo.sharedSequenceNumber);
                return;
            }

            int maxPacketSize = 1019;
            int windowSize = 256;
            int totalPacketQuantity = (fileData.length + maxPacketSize - 1) / maxPacketSize;
            int[] sequences = new int[totalPacketQuantity];
            byte [][] packetsBuffers = new byte[totalPacketQuantity][];
            TreeMap<Integer, byte[]> pending = new TreeMap<>();

            int sequenceNumber = connectedInfo.sharedSequenceNumber;

            // Prepare all packets with their respective sequence numbers and data buffers
            for (int i = 0; i < totalPacketQuantity; i++) {
                int startIndex = i * maxPacketSize;
                int realSizeOfPacket = Math.min(maxPacketSize, fileData.length - startIndex);
                packetsBuffers[i] = Arrays.copyOfRange(fileData, startIndex, startIndex + realSizeOfPacket);
                sequenceNumber = Utils.incrementSequenceNumber(sequenceNumber);
                sequences[i] = sequenceNumber;
            }

            int base = 0;
            int nextIndexNum = 0;
            int lastAckedSequence = -1;
            int[] duplicateAckPacketSequences = new int[3];
            int duplicateAckCount = 0;
            boolean retransmissionFlag = false;

            // Main loop to send packets and handle ACKs until all packets are acknowledged
            // The sender will keep sending packets within the window and wait for ACKs. If a timeout occurs or 3 duplicate ACKs are received, it will trigger retransmission of the unacknowledged packets.
            while (base < totalPacketQuantity) {

            // If not in retransmission mode, send new packets within the window
            // If in retransmission mode, resend all unacknowledged packets starting from the base index
                if (!retransmissionFlag) {
                    while (nextIndexNum < totalPacketQuantity && nextIndexNum - base < windowSize) {

                        int seqNum = sequences[nextIndexNum];
                        byte[] payload = packetsBuffers[nextIndexNum];
                        Object.Packet dataPacket = new Object.Packet(payload.length, seqNum, false, false, false, false, payload);
                        Utils.sendPacket(socket, dataPacket, connectedInfo.address, connectedInfo.port);
                        pending.put(seqNum, payload);
                        System.out.println(Utils.terminalLog("SENDER", "Sent packet with sequence number: " + seqNum));
                        nextIndexNum++;
                        packetSent++;
                        bytes += payload.length;
                    }
                }

                // Wait for ACK
                try {
                    
                    Object.Packet ackPacket = Utils.receivePacket(socket);
                    System.out.println(Utils.terminalLog("SENDER", "Received ACK packet (receiver_seq=" + ackPacket.getSequenceNumber() + ")"));

                    if (!ackPacket.isACK() || ackPacket.dataSize < 2) continue ; 
                    int ackedSequence = ((ackPacket.data[0] & 0xFF) << 8) | (ackPacket.data[1] & 0xFF);
                    int ackPacketSequence = ackPacket.getSequenceNumber();

                    System.out.println(Utils.terminalLog("SENDER", "ACK content: acking sender_seq=" + ackedSequence + ", from packet sender_seq=" + ackPacketSequence));

                    // Check for duplicate ACKs: If the ACK is for the same sequence number as the last acknowledged one, count it as a duplicate ACK. If 3 duplicate ACKs with different packet sequences are received, trigger fast retransmission.
                    if(ackedSequence == lastAckedSequence){
                        duplicateAckPacketSequences[duplicateAckCount] = ackPacketSequence;
                        duplicateAckCount++;
                        
                        // If 3 duplicate ACKs are received, check if they are for different packet sequences. If they are all different, trigger fast retransmission. If not, reset the duplicate ACK count to 1 to start counting again.
                        if(duplicateAckCount == 3){
                            boolean allDifferent = (duplicateAckPacketSequences[0] != duplicateAckPacketSequences[1]) &&
                                                  (duplicateAckPacketSequences[1] != duplicateAckPacketSequences[2]) &&
                                                  (duplicateAckPacketSequences[0] != duplicateAckPacketSequences[2]);
                            if(allDifferent){
                                System.out.println(Utils.terminalLog("SENDER", "3 duplicate ACKs with different packet sequences received for sender_seq: " + ackedSequence + ". Triggering fast retransmission."));
                                System.out.println(Utils.terminalLog("SENDER", "Packet sequences: " + duplicateAckPacketSequences[0] + ", " + duplicateAckPacketSequences[1] + ", " + duplicateAckPacketSequences[2]));
                                retransmissionFlag = true;
                                nextIndexNum = base;
                                duplicateAckCount = 0;
                            } else {
                                System.out.println(Utils.terminalLog("SENDER", "Received 3 ACKs but packet sequences are not all different. Discarding."));
                                duplicateAckCount = 1;
                            }
                        }
                    } else {
                        lastAckedSequence = ackedSequence;
                        duplicateAckCount = 1;
                        duplicateAckPacketSequences[0] = ackPacketSequence;
                        retransmissionFlag = false;
                        
                        // Advance base only up to the last contiguous acknowledged packet (cumulative ACK)
                        int oldBase = base;
                        while (base < totalPacketQuantity && sequences[base] <= ackedSequence) {
                            pending.remove(sequences[base]);
                            System.out.println(Utils.terminalLog("SENDER", "Removing from pending: seq=" + sequences[base]));
                            base++;
                        }
                        System.out.println(Utils.terminalLog("SENDER", "Window advanced: base moved from " + oldBase + " to " + base + " (ackedSequence=" + ackedSequence + ")"));
                        if (base > 0) System.out.println(Utils.terminalLog("SENDER", "Last acknowledged packet in buffer: seq=" + sequences[base-1]));
                        
                        connectedInfo.sharedSequenceNumber = ackedSequence;
                    }

                }catch(Exception e) {
                    System.out.println(Utils.terminalLog("SENDER", "Timeout occurred. Triggering retransmission."));
                    nextIndexNum = base;

                }
            }
            connectedInfo.sharedSequenceNumber = closeConnection(socket, connectedInfo.address, connectedInfo.port, sequences[totalPacketQuantity - 1]);
            System.out.println(Utils.terminalLog("SENDER", "File transfer completed. Total packets sent: " + packetSent + ", Total bytes sent: " + bytes));
        } catch (Exception e) {
            System.out.println(Utils.terminalLog("SENDER", "Error occurred while sending file: " + e.getMessage()));
            try {
                Utils.sendPacket(connectedInfo.socket, new Object.Packet(0, connectedInfo.sharedSequenceNumber, false, false, false, true, new byte[0]), connectedInfo.address, connectedInfo.port);
            } catch (Exception ex) {
                System.out.println(Utils.terminalLog("SENDER", "Error occurred while sending FIN packet: " + ex.getMessage()));
            }
        }finally {
            try{
                connectedInfo.socket.close();
                }catch(Exception ex){
                    System.out.println(Utils.terminalLog("SENDER", "Error occurred while closing socket: " + ex.getMessage()));
                }
            }
        }


        // Function to close the connection with the receiver by performing a four-way handshake using FIN and ACK packets
        // @param socket The DatagramSocket to use for sending and receiving packets during the connection closure
        // @param address The Inet4Address of the receiver to send the FIN packet to
        // @param port The port number of the receiver to send the FIN packet to
        // @param sequenceNumber The current sequence number to be used in the FIN packet
        // @return The final sequence number acknowledged by the receiver in the ACK packet, or the sequence number of the FIN-ACK packet if the ACK packet does not contain a valid sequence number
        public static int closeConnection(DatagramSocket socket, Inet4Address address, int port, int sequenceNumber) throws java.io.IOException {
            int finalSequenceNumber = Utils.incrementSequenceNumber(sequenceNumber);
            Object.Packet finPacket = new Object.Packet(0, finalSequenceNumber, false, false, true, false, new byte[0]);
            Utils.sendPacket(socket, finPacket, address, port);
            System.out.println(Utils.terminalLog("SENDER", "Sent FIN packet with sequence number: " + finPacket.getSequenceNumber()));

            Object.Packet finAckPacket = Utils.receivePacket(socket);
            if (finAckPacket.isACK() && finAckPacket.isFIN())  {

                System.out.println(Utils.terminalLog("SENDER", "Received FIN+ACK with sequence: " + finAckPacket.getSequenceNumber()));
                Utils.sendAck(socket, address, port, finalSequenceNumber);
                System.out.println(Utils.terminalLog("SENDER", "Sent final ACK with sequence: " + finalSequenceNumber));
                
            } else {
                System.out.println(Utils.terminalLog("SENDER", "Expected FIN+ACK but received: " + Utils.formatPacket(finAckPacket)));
                throw new java.io.IOException("Failed to receive FIN+ACK for FIN packet.");
            }
            
            return finAckPacket.dataSize >= 2 ? ((finAckPacket.data[0] & 0xFF) << 8) | (finAckPacket.data[1] & 0xFF) : finAckPacket.getSequenceNumber();
        }
    }