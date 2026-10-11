# Reliable File Transfer over UDP

A file transfer protocol built on top of UDP in Java, implementing by hand the mechanisms that make TCP reliable: connection handshake, sequence numbers, cumulative acknowledgements, sliding window, retransmission and connection teardown.

> University project – Networking course, Bachelor in Computer Science.

## Features

- **Custom packet format** with a 5-byte binary header (payload size, sequence number, SYN/ACK/FIN/RST flags).
- **Three-way handshake** with random initial sequence numbers and up to 3 SYN attempts.
- **Sliding window** of 256 packets with **cumulative ACKs**.
- **Loss recovery:** retransmission on timeout (3 s) and **fast retransmission** after 3 duplicate ACKs.
- **Out-of-order reassembly** on the receiver side: packets ahead of the expected sequence are buffered and written in order.
- **16-bit sequence numbers with wrap-around** (65535 → 0).
- **Clean connection close** (FIN, FIN+ACK, final ACK) and immediate abort with **RST** on error.
- Works with any file type (text, images, binaries).

## Packet format

| Bytes | Field | Description |
|---|---|---|
| 0–1 | `dataSize` | Payload size in bytes (16 bits, big-endian) |
| 2–3 | `sequenceNumber` | Sequence number, 0 to 65 535 (16 bits, big-endian) |
| 4 | `flags` | Bit 3 = SYN, bit 2 = ACK, bit 1 = FIN, bit 0 = RST |
| 5+ | `data` | Payload, up to 1 019 bytes (1 024-byte datagrams) |

ACK packets carry, in their 2-byte payload, the sequence number of the last packet received in order.

## Protocol flow

```
Sender                              Receiver
  |  SYN (seq = x)                      |
  | ----------------------------------> |
  |               SYN-ACK (seq = y)     |
  | <---------------------------------- |
  |  SYN (seq = x + 1)                  |
  | ----------------------------------> |   connection established
  |                                     |
  |  DATA x+2 ... x+257  (window = 256) |
  | ----------------------------------> |
  |          ACK (last in-order seq)    |   cumulative ACKs
  | <---------------------------------- |
  |      ... timeout or 3 duplicate ACKs -> resend from window base
  |                                     |
  |  FIN                                |
  | ----------------------------------> |
  |                          FIN + ACK  |
  | <---------------------------------- |
  |  ACK                                |
  | ----------------------------------> |   connection closed
```

## Project structure

```
src/
  Sender.java     Handshake, windowed sending, retransmission, connection close
  Receiver.java   Handshake, out-of-order buffering, cumulative ACKs, file writing
  Object.java     Packet (serialization / deserialization) and connection state
  Utils.java      Send/receive helpers, ACK building, sequence wrap-around, logging
Documentation/
  rapport.pdf     Project report (French)
  build.txt       Build and run notes (French)
```

## Getting started

**Requirements:** Java 17 or later.

```bash
# Compile
javac -d out src/*.java

# Terminal 1 – start the receiver: java Receiver <port> <output_file>
java -cp out Receiver 5000 received.jpg

# Terminal 2 – send a file: java Sender <receiver_ip> <port> <input_file>
java -cp out Sender 127.0.0.1 5000 photo.jpg
```

The receiver keeps listening after each transfer and can accept a new connection. Both programs log every packet they send and receive in the terminal.

## Testing

Transfers were checked byte for byte (`cmp`) on localhost with random files from 5 KB to 5 MB, including runs where the sequence number wraps around 65535.
