package com.musicstreamer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PriorityQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class AudioStreamReceiver implements Runnable {
    private static final int BUFFER_SIZE_BYTES = 4096;
    private static final int HEADER_SIZE_BYTES = 19;
    private static final int MAX_PACKET_SIZE = BUFFER_SIZE_BYTES + HEADER_SIZE_BYTES;
    private static final int SAMPLE_RATE = 44100;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 2;
    private static final int FRAME_SIZE = CHANNELS * (BITS_PER_SAMPLE / 8);

    // Jitter Buffer settings
    private static final int MIN_JITTER_BUFFER_PACKETS = 10;
    private static final int MAX_JITTER_BUFFER_PACKETS = 250;
    private static final int TARGET_JITTER_BUFFER_PACKETS = 30; // Start with a modest target
    private int jitterBufferSize = TARGET_JITTER_BUFFER_PACKETS;

    private final int port;
    private volatile boolean isRunning = false;
    private DatagramSocket socket;
    private SourceDataLine audioLine;
    private float volume = 1.0f;

    // Packet reordering and jitter buffer
    private final PriorityQueue<AudioPacket> packetBuffer = new PriorityQueue<>();

    private final AudioFormat audioFormat = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE,
            BITS_PER_SAMPLE,
            CHANNELS,
            FRAME_SIZE,
            SAMPLE_RATE,
            false // Little-endian
    );

    public AudioStreamReceiver(int port) {
        this.port = port;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(this::receivePackets).start();
        new Thread(this::processAudio).start();
    }

    public void stop() {
        isRunning = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (audioLine != null) {
            audioLine.stop();
            audioLine.flush();
            audioLine.close();
        }
    }

    private void receivePackets() {
        try {
            socket = new DatagramSocket(port);
            byte[] receiveBuffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            while (isRunning) {
                try {
                    socket.receive(packet);
                    if (packet.getLength() > HEADER_SIZE_BYTES) {
                        processReceivedPacket(Arrays.copyOf(packet.getData(), packet.getLength()));
                    }
                } catch (SocketException e) {
                    if (isRunning) System.err.println("Socket error during receive: " + e.getMessage());
                } catch (IOException e) {
                    if (isRunning) System.err.println("IO error during receive: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("Failed to open socket on port " + port + ": " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void processReceivedPacket(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        long sequenceNumber = bb.getLong();
        long timestamp = bb.getLong();
        byte flags = bb.get();
        int length = bb.getShort() & 0xFFFF;

        if (length > 0 && bb.remaining() >= length) {
            byte[] audioData = new byte[length];
            bb.get(audioData);
            synchronized (packetBuffer) {
                packetBuffer.offer(new AudioPacket(sequenceNumber, audioData));
            }
        }
    }

    private void processAudio() {
        try {
            initializeAudioLine();
        } catch (LineUnavailableException e) {
            System.err.println("Audio line unavailable: " + e.getMessage());
            return;
        }

        long nextSequenceNumber = -1;
        byte[] lastGoodPacketData = new byte[BUFFER_SIZE_BYTES];

        while (isRunning) {
            try {
                // Wait until the buffer is filled to the target level before starting
                waitForBufferFill();

                AudioPacket currentPacket;
                synchronized (packetBuffer) {
                    currentPacket = packetBuffer.poll();
                }

                if (currentPacket == null) {
                    // Buffer is empty, wait
                    Thread.sleep(10);
                    continue;
                }

                if (nextSequenceNumber == -1) {
                    // This is the first packet
                    nextSequenceNumber = currentPacket.sequenceNumber;
                }

                if (currentPacket.sequenceNumber < nextSequenceNumber) {
                    // Old packet, discard
                    continue;
                }

                if (currentPacket.sequenceNumber > nextSequenceNumber) {
                    // Packet loss detected
                    long packetsLost = currentPacket.sequenceNumber - nextSequenceNumber;
                    System.out.println("Packet loss: " + packetsLost + " packets missing. Seq " + nextSequenceNumber + " to " + (currentPacket.sequenceNumber - 1));
                    handlePacketLoss(packetsLost, lastGoodPacketData);
                }

                // We have a good packet
                byte[] audioData = applyVolume(currentPacket.audioData, volume);
                audioLine.write(audioData, 0, audioData.length);
                System.arraycopy(currentPacket.audioData, 0, lastGoodPacketData, 0, currentPacket.audioData.length);

                nextSequenceNumber = currentPacket.sequenceNumber + 1;

                // Dynamically adjust jitter buffer size
                adjustJitterBuffer();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void waitForBufferFill() throws InterruptedException {
        while (isRunning && packetBuffer.size() < jitterBufferSize) {
            Thread.sleep(10);
        }
    }

    private void handlePacketLoss(long packetsLost, byte[] lastPacketData) {
        // Use a more advanced PLC by stretching the last good packet
        // For short losses, this sounds better than silence or simple repetition.
        if (packetsLost > 0 && packetsLost <= 5) { // Conceal up to 5 lost packets
            System.out.println("Applying PLC for " + packetsLost + " packets.");
            for (int i = 0; i < packetsLost; i++) {
                 // Simple time-stretch: play the first half of the last packet, then the second half.
                 // This is a basic form of time-stretching by repetition.
                int half = lastPacketData.length / 2;
                byte[] concealedPacket = new byte[lastPacketData.length];
                System.arraycopy(lastPacketData, 0, concealedPacket, 0, half);
                System.arraycopy(lastPacketData, 0, concealedPacket, half, half);

                byte[] processedAudio = applyVolume(concealedPacket, volume);
                audioLine.write(processedAudio, 0, processedAudio.length);
            }
        } else if (packetsLost > 5) {
            // For longer losses, it's better to insert silence to avoid horrible distortion
            System.out.println("Gap too large, inserting silence for " + packetsLost + " packets.");
            byte[] silence = new byte[BUFFER_SIZE_BYTES]; // Already filled with zeros
            for (int i = 0; i < packetsLost; i++) {
                audioLine.write(silence, 0, silence.length);
            }
        }
    }

    private void adjustJitterBuffer() {
        synchronized (packetBuffer) {
            if (packetBuffer.size() > jitterBufferSize + 20 && jitterBufferSize > MIN_JITTER_BUFFER_PACKETS) {
                // Buffer is growing, we can reduce latency
                jitterBufferSize = Math.max(MIN_JITTER_BUFFER_PACKETS, jitterBufferSize - 10);
            } else if (packetBuffer.size() < jitterBufferSize - 10 && jitterBufferSize < MAX_JITTER_BUFFER_PACKETS) {
                // Buffer is shrinking, we need to increase it to avoid underruns
                jitterBufferSize = Math.min(MAX_JITTER_BUFFER_PACKETS, jitterBufferSize + 10);
            }
        }
    }

    private void initializeAudioLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Audio format not supported: " + audioFormat);
        }
        audioLine = (SourceDataLine) AudioSystem.getLine(info);
        // Use a larger audio line buffer to prevent underruns at the OS level
        audioLine.open(audioFormat, (BUFFER_SIZE_BYTES * 10));
        audioLine.start();
        System.out.println("Audio line initialized with buffer size: " + audioLine.getBufferSize() + " bytes.");
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    private byte[] applyVolume(byte[] audioData, float volume) {
        if (Math.abs(volume - 1.0f) < 0.01f) {
            return audioData;
        }
        byte[] processedData = new byte[audioData.length];
        for (int i = 0; i < audioData.length - 1; i += 2) {
            short sample = (short) ((audioData[i] & 0xFF) | (audioData[i + 1] << 8));
            sample = (short) (sample * volume);
            processedData[i] = (byte) (sample & 0xFF);
            processedData[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return processedData;
    }
    
    @Override
    public void run() {
        // This run method is now a fallback, the main logic is in receivePackets and processAudio
        System.out.println("Starting AudioStreamReceiver...");
        start();
    }

    private static class AudioPacket implements Comparable<AudioPacket> {
        private final long sequenceNumber;
        private final byte[] audioData;

        public AudioPacket(long sequenceNumber, byte[] audioData) {
            this.sequenceNumber = sequenceNumber;
            this.audioData = audioData;
        }

        @Override
        public int compareTo(AudioPacket other) {
            return Long.compare(this.sequenceNumber, other.sequenceNumber);
        }
    }
}