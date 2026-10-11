import java.net.DatagramSocket;
import java.net.Inet4Address;

public class Object {

    // Constants representing the flag values for SYN, ACK, FIN, and RST in the packet header
    private static final int FLAG_SYN = 0b1000;
    private static final int FLAG_ACK = 0b0100;
    private static final int FLAG_FIN = 0b0010;
    private static final int FLAG_RST = 0b0001;
    // Constant representing the size of the packet header in bytes
    private static final int HEADER_SIZE = 5; 
    

    // Inner class representing a packet with header fields and data
    public static class Packet{
        int dataSize;
        int sequenceNumber;
        boolean SYN;
        boolean ACK;
        boolean FIN;
        boolean RST;
        byte[] data;

        // Constructor to initialize a Packet object with the specified fields
        // @param dataSize The size of the data payload in bytes
        // @param sequenceNumber The sequence number of the packet
        // @param SYN A boolean flag indicating if the SYN flag is set
        // @param ACK A boolean flag indicating if the ACK flag is set
        // @param FIN A boolean flag indicating if the FIN flag is set
        // @param RST A boolean flag indicating if the RST flag is set
        // @param data The byte array containing the data payload of the packet
        public Packet(int dataSize, int sequenceNumber, boolean SYN, boolean ACK, boolean FIN, boolean RST, byte[] data) {
            this.dataSize = dataSize;
            this.sequenceNumber = sequenceNumber;
            this.SYN = SYN;
            this.ACK = ACK;
            this.FIN = FIN;
            this.RST = RST;
            this.data = data;
        }

        public int getDataSize() {
            return dataSize;
        }

        public int getSequenceNumber() {
            return sequenceNumber;
        }

        boolean isSYN() {
            return SYN;
        }

        boolean isACK() {
            return ACK;
        }

        boolean isFIN() {
            return FIN;
        }

        boolean isRST() {
            return RST;
        }

        // Function to convert the Packet object into a byte array for transmission
        // @return A byte array representing the serialized form of the Packet object
        public byte[] toBytes() {
            byte[] bytes = new byte[HEADER_SIZE + dataSize];

            bytes[0] = (byte) ((dataSize >> 8) & 0xFF);
            bytes[1] = (byte) (dataSize & 0xFF);

            bytes[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
            bytes[3] = (byte) (sequenceNumber & 0xFF);
            bytes[4] = (byte) ((SYN ? FLAG_SYN : 0) | (ACK ? FLAG_ACK : 0) | (FIN ? FLAG_FIN : 0) | (RST ? FLAG_RST : 0));

            if (dataSize > 0 && data != null && data.length >= dataSize) {
                System.arraycopy(data, 0, bytes, HEADER_SIZE, dataSize);
            }
            return bytes;
        }

        // Static function to create a Packet object from a byte array received from the network
        // @param bytes The byte array containing the serialized form of a Packet object
        // @return A Packet object deserialized from the byte array
        public static Packet fromBytes(byte[] bytes) {

            if (bytes.length < HEADER_SIZE) {
                throw new IllegalArgumentException("Paquet trop court: " + bytes.length + " octets (minimum " + HEADER_SIZE + ")");
            }
            int dataSize = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            int sequenceNumber = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            boolean SYN = (bytes[4] & FLAG_SYN) != 0;
            boolean ACK = (bytes[4] & FLAG_ACK) != 0;
            boolean FIN = (bytes[4] & FLAG_FIN) != 0;
            boolean RST = (bytes[4] & FLAG_RST) != 0;

            byte[] data = new byte[dataSize];
            if (dataSize > 0 && bytes.length >= HEADER_SIZE + dataSize) {
                System.arraycopy(bytes, HEADER_SIZE, data, 0, dataSize);
            }
            return new Packet(dataSize, sequenceNumber, SYN, ACK, FIN, RST, data);
        }
    }

    // Inner class representing the connection information for a sender or receiver
    public static class connectedInfo{
        DatagramSocket socket;
        Inet4Address address;
        int port;
        int sharedSequenceNumber;
        int receiverSequenceNumber;
        
        // Constructor to initialize a connectedInfo object with the specified fields
        // @param socket The DatagramSocket associated with the connection
        // @param address The Inet4Address of the remote host in the connection
        // @param port The port number of the remote host in the connection
        // @param senderSequenceNumber The initial sequence number used by the sender in the connection
        // @param receiverSequenceNumber The initial sequence number used by the receiver in the connection
        public connectedInfo(DatagramSocket socket, Inet4Address address, int port, int senderSequenceNumber, int receiverSequenceNumber) {
            this.socket = socket;
            this.address = address;
            this.port = port;
            this.sharedSequenceNumber = senderSequenceNumber;
            this.receiverSequenceNumber = receiverSequenceNumber;
        }
    }

}
