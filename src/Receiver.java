import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeMap;

public class Receiver {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java Receiver <port> <inputFilePath>");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        java.io.File inputFile = new java.io.File(args[1]);
        System.out.println("Receiver is running on port " + port);
        try {
            new Receiver().ConnectToSender(port, inputFile);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    // Function to handle the connection establishment and data reception from the sender
    // @param port The port number to listen for incoming connections from the sender
    // @param inputFile The File object representing the file to write the received data to
    private void ConnectToSender(int port ,File inputFile) throws IOException {

        // Initiate the DatagramSocket to listen for incoming packets on the specified port
        DatagramSocket socket = new DatagramSocket(port);

        while (true) {

            //Wait for SYN packet from sender to initiate connection
            DatagramPacket datagramPacket = Utils.receiveDatagramPacket(socket);
            Object.Packet packet = Object.Packet.fromBytes(Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength()));

            int senderSequenceNumber = packet.getSequenceNumber();
            int receiverSequenceNumber = new Random().nextInt(65536);
            
            if (packet.isSYN()) {
                int synAckSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);   
                System.out.println("Received SYN packet from sender: " + senderSequenceNumber);
                //Send SYN-ACK packet back to sender with receiver sequence number
                Object.Packet synAckPacket = new Object.Packet(0, synAckSequenceNumber, true,true,false,false, new byte[0]);
                Utils.sendPacket(socket, synAckPacket, (Inet4Address) datagramPacket.getAddress(), datagramPacket.getPort());
                System.out.println("Sent SYN-ACK packet with receiver sequence: " + synAckPacket.sequenceNumber);

                //Wait for SYN packet from sender to complete 3-way handshake
                DatagramPacket synDatagramPacket = Utils.receiveDatagramPacket(socket);

                Object.Packet synPacket = Object.Packet.fromBytes(Arrays.copyOf(synDatagramPacket.getData(), synDatagramPacket.getLength()));
                if (synPacket.isSYN()) {
                    System.out.println("Received SYN packet from sender with sequence: " + synPacket.sequenceNumber);
                    receiveData(socket, inputFile, (Inet4Address) datagramPacket.getAddress(), datagramPacket.getPort(), synPacket.getSequenceNumber(), synAckSequenceNumber);
                    System.out.println(Utils.terminalLog("RECEIVER", "Connection closed. Ready for new connections..."));
                } else {
                    System.err.println("Expected SYN packet but received: " + Utils.formatPacket(synPacket));
                }
            } else {
                System.err.println("Expected SYN packet but received packet with sequence: " + packet.sequenceNumber);
            }

        }
    }

    // Function to handle the data reception from the sender after the connection has been established
    // @param socket The DatagramSocket to receive data from the sender
    // @param inputFile The File object representing the file to write the received data to
    // @param address The Inet4Address of the sender to receive data from
    // @param port The port number of the sender to receive data from
    // @param senderInitialSequence The initial sequence number used by the sender in the connection
    // @param receiverSequenceNumber The initial sequence number used by the receiver in the connection
    private void receiveData(DatagramSocket socket,File inputFile, Inet4Address address, int port, int senderInitialSequence, int receiverSequenceNumber)  throws IOException {
        TreeMap<Integer, byte[]> packetsMap = new TreeMap<>();
        int base = senderInitialSequence;
        int windowSize = 256;

        try(FileOutputStream outputFile = new FileOutputStream(inputFile,false)){
            while (true) {
                DatagramPacket datagramPacket = Utils.receiveDatagramPacket(socket);
                if(!datagramPacket.getAddress().equals(address) || datagramPacket.getPort() != port) {
                    System.out.println(Utils.terminalLog("RECEIVER", "Received packet from unexpected source: " + datagramPacket.getAddress() + ":" + datagramPacket.getPort()));
                    continue;
                }

                Object.Packet packet = Object.Packet.fromBytes(Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength()));
                System.out.println(Utils.terminalLog("RECEIVER", "Received packet: " + Utils.formatPacket(packet)));

                if(packet.isFIN()) {
                    int expectedFinSequence = Utils.incrementSequenceNumber(base);
                    if(packet.sequenceNumber != expectedFinSequence) {
                        System.out.println(Utils.terminalLog("RECEIVER", "Received BAD FIN packet. Expected sequence: " + expectedFinSequence + ", but received: " + packet.getSequenceNumber()));
                        receiverSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);
                        Utils.sendAck(socket, address, port, base, receiverSequenceNumber);
                        continue;
                    }

                    base = packet.sequenceNumber;
                    receiverSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);
                    Object.Packet finAckPacket = new Object.Packet(0, receiverSequenceNumber, false, true, true, false, new byte[0]);
                    Utils.sendPacket(socket, finAckPacket, address, port);
                    System.out.println(Utils.terminalLog("RECEIVER", "Received FIN packet. Sent FIN + ACK with receiver sequence: " + receiverSequenceNumber + " for sender sequence: " + base));
                    
                    // Wait for final ACK from sender to complete 4-way close
                    try {
                        DatagramPacket finalAckDatagramPacket = Utils.receiveDatagramPacket(socket);
                        if(!finalAckDatagramPacket.getAddress().equals(address) || finalAckDatagramPacket.getPort() != port) {
                            System.out.println(Utils.terminalLog("RECEIVER", "Final ACK from unexpected source, ignoring"));
                        } else {
                            Object.Packet finalAck = Object.Packet.fromBytes(Arrays.copyOf(finalAckDatagramPacket.getData(), finalAckDatagramPacket.getLength()));
                            if (finalAck.isACK()) {
                                System.out.println(Utils.terminalLog("RECEIVER", "Received final ACK from sender with sequence: " + finalAck.getSequenceNumber()));
                            } else {
                                System.out.println(Utils.terminalLog("RECEIVER", "Expected final ACK but received: " + Utils.formatPacket(finalAck)));
                            }
                        }
                    } catch (Exception e) {
                        System.out.println(Utils.terminalLog("RECEIVER", "Timeout/Error waiting for final ACK (normal): " + e.getMessage()));
                    }
                    break;
                }

                // Handle RST packet to terminate connection immediately
                // If a RST packet is received, log the error and terminate the connection
                if (packet.isRST()) {
                    System.err.println(Utils.terminalLog("RECEIVER", "ERROR: Received RST packet from " + address + ":" + port + " with sequence: " + packet.getSequenceNumber()));
                    System.err.println(Utils.terminalLog("RECEIVER", "Connection terminated by sender (RST signal)"));
                    break;
                }

                // If the packet has no data, it is likely a duplicate ACK or a control packet. Acknowledge it but do not write to file.
                // This ensures that the receiver can handle duplicate ACKs or control packets without affecting the integrity of the received file data.
                if (packet.dataSize <= 0) {
                    receiverSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);
                    Utils.sendAck(socket, address, port, base, receiverSequenceNumber);
                    continue;
                }

                int nextExpectedSequence = Utils.incrementSequenceNumber(base);
                int distance = (packet.sequenceNumber - nextExpectedSequence) & 0xFFFF;

                // If the received packet's sequence number is outside the current window, it is either a duplicate or an out-of-order packet. Acknowledge it but do not write to file.
                // This allows the receiver to maintain the integrity of the sliding window protocol by only accepting packets that fall within the expected sequence number range, while still acknowledging out-of-order or duplicate packets to prevent unnecessary retransmissions from the sender.
                if (distance >= windowSize) {
                    System.out.println(Utils.terminalLog("RECEIVER", "Received packet with sequence number outside the window. Expected sequence: " + nextExpectedSequence + ", but received: " + packet.getSequenceNumber()));
                    receiverSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);
                    Utils.sendAck(socket, address, port, base, receiverSequenceNumber);
                    continue;
                }

                packetsMap.put(packet.sequenceNumber, Arrays.copyOf(packet.data, packet.dataSize));

                // Write all in-order packets to file and update the base and next expected sequence number accordingly
                while (packetsMap.containsKey(nextExpectedSequence)) {
                    byte[] data = packetsMap.remove(nextExpectedSequence);
                    outputFile.write(data);
                    outputFile.flush();
                    base = nextExpectedSequence;
                    nextExpectedSequence = Utils.incrementSequenceNumber(base);
                }
                receiverSequenceNumber = Utils.incrementSequenceNumber(receiverSequenceNumber);
                Utils.sendAck(socket, address, port, base, receiverSequenceNumber);

            }
        }
        
    }
}
