package com.musicstreamer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class AudioStreamReceiver implements Runnable {
    private static final int BUFFER_SIZE_BYTES = 4096;
    private static final int HEADER_SIZE_BYTES = 23; // 8+8+1+4+2 = seq+timestamp+flags+song_index+length
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
    
    // Buffering flag: true until jitter buffer is initially filled
    private volatile boolean buffering = true;
   
    // Track when we just connected to reset sequence expectations
    private volatile boolean justConnected = false;
   
    // Track packets processed since reconnection
    private int packetsProcessedSinceReconnection = 0;

    // Decoupling audio processing from playback
    private Thread playbackThread;
    private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>(100); // Buffer for ~2.3s of audio

    // Current song tracking
    private volatile int currentSongIndex = -1;
    private volatile boolean songIndexChanged = false;

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
        
        try {
            initializeAudioLine();
        } catch (LineUnavailableException e) {
            System.err.println("Audio line unavailable, cannot start playback: " + e.getMessage());
            return;
        }

        isRunning = true;
        buffering = true; // Reset buffering state on (re)start
        justConnected = true; // Mark as just connected
        
        // Clear jitter buffer and reset all tracking state on (re)connect
        synchronized (packetBuffer) {
            packetBuffer.clear();
        }
        currentSongIndex = -1;
        songIndexChanged = false;
        packetsProcessedSinceReconnection = 0;
        
        new Thread(this::receivePackets).start();
        new Thread(this::processAudio).start();
        playbackThread = new Thread(this::playbackLoop);
        playbackThread.start();
    }

    public void stop() {
        isRunning = false;
        justConnected = false;
        
        if (playbackThread != null) {
            playbackThread.interrupt();
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        // audioLine is now closed by the playbackLoop
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
        int songIndex = bb.getInt();
        int length = bb.getShort() & 0xFFFF;

        // Check if song changed
        // Song index changes will be handled during playback to ensure accuracy with jitter buffer

        if (length > 0 && bb.remaining() >= length) {
            byte[] audioData = new byte[length];
            bb.get(audioData);
            synchronized (packetBuffer) {
                packetBuffer.offer(new AudioPacket(sequenceNumber, songIndex, audioData));
            }
        }
    }

    private void playbackLoop() {
        try {
            while (isRunning) {
                // take() blocks until an element is available and is interruptible.
                byte[] audioData = playbackQueue.take();
                audioLine.write(audioData, 0, audioData.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Playback thread was interrupted and is stopping.");
        } finally {
            if (audioLine != null) {
                System.out.println("Draining and closing audio line.");
                audioLine.drain();
                audioLine.stop();
                audioLine.close();
            }
        }
    }

    private void processAudio() {
        long nextSequenceNumber = -1;
        byte[] lastGoodPacketData = new byte[BUFFER_SIZE_BYTES];

        while (isRunning) {
            try {
                // Wait until the buffer is filled to the target level before starting
                waitForBufferFill();

                AudioPacket currentPacket;
                synchronized (packetBuffer) {
                    currentPacket = packetBuffer.poll();
                    // Debug buffer status only for critical situations
                    if (packetBuffer.size() < 3) {
                        System.out.println("WARNING: Jitter buffer critically low - " + packetBuffer.size() + " packets remaining");
                    }
                }

                if (currentPacket == null) {
                    // Buffer is empty, wait
                    System.out.println("Buffer empty! Waiting for packets...");
                    Thread.sleep(10);
                    continue;
                }

                // Detect song change *before* any sequence-number logic
                if (currentSongIndex != currentPacket.songIndex) {
                    System.out.println("Song change detected: " + currentSongIndex + " -> " + currentPacket.songIndex);
                    currentSongIndex = currentPacket.songIndex;
                    songIndexChanged = true;

                    // Flush any leftover packets from previous song
                    synchronized (packetBuffer) {
                        packetBuffer.clear();
                    }

                    // Reset sequence tracking and packet-loss state
                    nextSequenceNumber = currentPacket.sequenceNumber;
                    Arrays.fill(lastGoodPacketData, (byte) 0);
                }

                if (nextSequenceNumber == -1 || justConnected) {
                    // This is the first packet or we just reconnected
                    nextSequenceNumber = currentPacket.sequenceNumber;
                    justConnected = false; // Clear the flag
                    System.out.println("Starting/restarting with sequence number: " + nextSequenceNumber);
                }

                if (currentPacket.sequenceNumber < nextSequenceNumber) {
                    // Old packet, discard
                    continue;
                }

                if (currentPacket.sequenceNumber > nextSequenceNumber) {
                    System.out.println("Packet loss detected through nextSequenceNumber: " + (currentPacket.sequenceNumber - nextSequenceNumber) + " packets missing. Seq " + nextSequenceNumber + " to " + (currentPacket.sequenceNumber - 1));
                    // Only detect packet loss if we've processed enough packets since reconnection
                    if (packetsProcessedSinceReconnection >= 10) {
                        // Packet loss detected
                        long packetsLost = currentPacket.sequenceNumber - nextSequenceNumber;
                        System.out.println("Packet loss: " + packetsLost + " packets missing. Seq " + nextSequenceNumber + " to " + (currentPacket.sequenceNumber - 1));
                        handlePacketLoss(packetsLost, lastGoodPacketData);
                    } else {
                        // Still in initial reconnection phase - just update sequence baseline
                        System.out.println("Initial reconnection phase - updating sequence baseline from " + nextSequenceNumber + " to " + currentPacket.sequenceNumber);
                        nextSequenceNumber = currentPacket.sequenceNumber;
                    }
                }

                // We have a good packet
                byte[] audioData = applyVolume(currentPacket.audioData, volume);

                // Queue the processed audio for the dedicated playback thread
                // This call will block if the queue is full, providing back-pressure.
                playbackQueue.put(audioData);

                // Log sequence number of played packet
                System.out.println("Queued packet for playback, seq: " + currentPacket.sequenceNumber);
                
                System.arraycopy(currentPacket.audioData, 0, lastGoodPacketData, 0, currentPacket.audioData.length);

                nextSequenceNumber = currentPacket.sequenceNumber + 1;
                packetsProcessedSinceReconnection++;

                adjustJitterBuffer();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Processing thread interrupted and stopping.");
                break;
            }
        }
    }
    
    private void waitForBufferFill() throws InterruptedException {
        while (isRunning && packetBuffer.size() < jitterBufferSize) {
            Thread.sleep(10);
        }
        // Buffer has filled to target level
        buffering = false;
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
            int currentSize = packetBuffer.size();
            int oldBufferSize = jitterBufferSize;
            
            if (packetBuffer.size() > jitterBufferSize + 20 && jitterBufferSize > MIN_JITTER_BUFFER_PACKETS) {
                // Buffer is growing, we can reduce latency
                jitterBufferSize = Math.max(MIN_JITTER_BUFFER_PACKETS, jitterBufferSize - 5);
            } else if (packetBuffer.size() < jitterBufferSize - 10 && jitterBufferSize < MAX_JITTER_BUFFER_PACKETS) {
                // Buffer is shrinking, we need to increase it to avoid underruns
                jitterBufferSize = Math.min(MAX_JITTER_BUFFER_PACKETS, jitterBufferSize + 5);
            }
            
            if (oldBufferSize != jitterBufferSize) {
                System.out.println("Jitter buffer adjusted: " + oldBufferSize + " -> " + jitterBufferSize + " (current packets: " + currentSize + ")");
            }
        }
    }

    private void initializeAudioLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Audio format not supported: " + audioFormat);
        }
        audioLine = (SourceDataLine) AudioSystem.getLine(info);
        // Use a larger driver buffer (~100 ms) so occasional OS scheduling hiccups don't block writes
        int lineBufferBytes = (int) (SAMPLE_RATE * FRAME_SIZE * 0.10); // 0.10 s ≈ 17 640 bytes
        audioLine.open(audioFormat, lineBufferBytes);
        audioLine.start();
        System.out.println("Audio line initialized with buffer size: " + audioLine.getBufferSize() + " bytes.");
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    public int getCurrentSongIndex() {
        return currentSongIndex;
    }
    
    public boolean hasSongIndexChanged() {
        boolean changed = songIndexChanged;
        songIndexChanged = false; // Reset flag after checking
        return changed;
    }

    public boolean isBuffering() { return buffering; }

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
        private final int songIndex;
        private final byte[] audioData;

        public AudioPacket(long sequenceNumber, int songIndex, byte[] audioData) {
            this.sequenceNumber = sequenceNumber;
            this.songIndex = songIndex;
            this.audioData = audioData;
        }

        @Override
        public int compareTo(AudioPacket other) {
            return Long.compare(this.sequenceNumber, other.sequenceNumber);
        }
    }
}