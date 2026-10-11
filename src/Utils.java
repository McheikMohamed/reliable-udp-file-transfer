import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;

public class Utils {

    // Function to send a packet using a DatagramSocket
    // @param socket The DatagramSocket to send the packet through
    // @param packet The Object.Packet to be sent
    // @param address The destination Inet4Address to send the packet to
    // @param port The destination port number to send the packet to
    // @throws IOException If an I/O error occurs while sending the packet
    public static void sendPacket(DatagramSocket socket, Object.Packet packet, Inet4Address address, int port) throws IOException {
        byte[] bytes = packet.toBytes();
        DatagramPacket datagramPacket = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(datagramPacket);
    }

    // Function to receive a Object.Packet using a DatagramSocket
    // @param socket The DatagramSocket to receive the packet from
    // @return The received Object.Packet
    public static Object.Packet receivePacket(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagramPacket);
        return Object.Packet.fromBytes(datagramPacket.getData());
    }

    // Function to receive a DatagramPacket using a DatagramSocket
    // @param socket The DatagramSocket to receive the packet from
    // @return The received DatagramPacket
    public static DatagramPacket receiveDatagramPacket(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagramPacket);
        return datagramPacket;
    }

    // Function to increment a sequence number, wrapping around at 65535
    // @param sequenceNumber The current sequence number to be incremented
    // @return The incremented sequence number, wrapped around at 65535
    public static int incrementSequenceNumber(int sequenceNumber) {
        return (sequenceNumber + 1) & 0xFFFF;
    }

    // Function to compare two 16-bit sequence numbers, taking the wrap-around at 65535 into account
    // @param sequence The sequence number to test
    // @param reference The reference sequence number (for example the last acknowledged one)
    // @return true if sequence is equal to reference or comes before it (within half of the sequence space)
    public static boolean isSequenceAtOrBefore(int sequence, int reference) {
        return ((reference - sequence) & 0xFFFF) < 0x8000;
    }

    // Function to format a log message for terminal output
    // @param role The role of the sender (e.g., "SENDER" or "RECEIVER")
    // @param message The log message to be formatted
    // @return A formatted log message string
    public static String terminalLog(String role , String message) {
        return String.format("[%s] %s", role, message);
    }

    // Function to format a Object.Packet for logging purposes
    // @param packet The Object.Packet to be formatted
    // @return A formatted string representation of the packet
    public static String formatPacket(Object.Packet packet) {
        return String.format("Packet{dataSize=%d, sequenceNumber=%d, SYN=%b, ACK=%b, FIN=%b, RST=%b}",
                packet.dataSize, packet.sequenceNumber, packet.SYN, packet.ACK, packet.FIN, packet.RST);
    }

    // Function to send an ACK packet with a specified base sequence number and receiver sequence number
    // @param socket The DatagramSocket to send the ACK packet through
    // @param address The destination Inet4Address to send the ACK packet to
    // @param port The destination port number to send the ACK packet to
    // @param base The base sequence number that the ACK is acknowledging
    // @param receiverSequenceNumber The sequence number to be used in the ACK packet
    // @throws IOException If an I/O error occurs while sending the ACK packet
    public static void sendAck(DatagramSocket socket, Inet4Address address, int port, int base, int receiverSequenceNumber) throws IOException {
        byte[] payload = new byte[2];
        payload[0] = (byte) ((base >> 8) & 0xFF);
        payload[1] = (byte) (base & 0xFF);
        Object.Packet ack = new Object.Packet(2, receiverSequenceNumber, false, true, false, false, payload);
        Utils.sendPacket(socket, ack, address, port);
        System.out.println(Utils.terminalLog("RECEIVER", "Sent ACK: receiver_seq=" + receiverSequenceNumber + " acking sender_seq=" + base));
    }
    
    // Overloaded function to send an ACK packet with a specified base sequence number, automatically calculating the receiver sequence number
    // @param socket The DatagramSocket to send the ACK packet through
    // @param address The destination Inet4Address to send the ACK packet to
    // @param port The destination port number to send the ACK packet to
    // @param base The base sequence number that the ACK is acknowledging
    // @throws IOException If an I/O error occurs while sending the ACK packet
    public static void sendAck(DatagramSocket socket, Inet4Address address, int port, int base) throws IOException {
        int receiverSequenceNumber = Utils.incrementSequenceNumber(base);
        sendAck(socket, address, port, base, receiverSequenceNumber);
    }
    
}