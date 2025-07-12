package com.musicstreamer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;


public class AudioStreamReceiver implements Runnable {
    private static final int BUFFER_SIZE = 4096+32;
    private static final int JITTER_BUFFER_SIZE = 100; // Increased buffer size
    private static final int SAMPLE_RATE = 44100;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 2;
    
    private final int port;
    private boolean isRunning = false;
    private DatagramSocket socket;
    private BlockingQueue<AudioPacket> jitterBuffer;
    private byte[] lastGoodPacket;
    private SourceDataLine audioLine;
    private float volume = 1.0f;
    
    // Audio format for playback - PCM_SIGNED, little endian (typical for MP3 decoded audio)
    private final AudioFormat audioFormat = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE,
            BITS_PER_SAMPLE,
            CHANNELS,
            CHANNELS * (BITS_PER_SAMPLE / 8), // Frame size in bytes
            SAMPLE_RATE,
            false  // Little endian
    );
    
    public AudioStreamReceiver(int port) {
        this.port = port;
        this.jitterBuffer = new ArrayBlockingQueue<>(JITTER_BUFFER_SIZE);
        this.lastGoodPacket = new byte[BUFFER_SIZE];
    }
    
    public void start() {
        if (!isRunning) {
            isRunning = true;
            new Thread(this).start();
            new Thread(this::processAudio).start();
        }
    }
    
    public void stop() {
        isRunning = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (audioLine != null && audioLine.isOpen()) {
        audioLine.stop();      // 1) halt playback immediately
        audioLine.flush();     // 2) throw away anything still queued
        audioLine.close();     // 3) release the line
    }
jitterBuffer.clear();      // optional: drop any packets we cached

    }
    
    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            byte[] buffer = new byte[BUFFER_SIZE + 32]; // Larger buffer to ensure complete headers
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            System.out.println("AudioStreamReceiver: Waiting for UDP packets on port " + port);
            
            while (isRunning) {
                socket.receive(packet);
                
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                processPacket(data);
                
                // Reset the packet
                packet.setLength(buffer.length);
            }
        } catch (SocketException e) {
            if (isRunning) {
                System.err.println("Socket error: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
    
    private void processPacket(byte[] data) {
        if (data.length < 19) return;                    // not even a full header

        ByteBuffer bb = ByteBuffer.wrap(data)
                                .order(ByteOrder.BIG_ENDIAN);

        long  seq   = bb.getLong();                      // 8 B
        long  ts    = bb.getLong();                      // 8 B
        byte  flags = bb.get();                          // 1 B
        int   len   = bb.getShort() & 0xFFFF;            // 2 B (unsigned)

        if (flags == 0x02) {                             // EOS
            System.out.println("End-of-stream packet");
            return;
        }

        if (len > bb.remaining()) {                      // corrupt/truncated
            System.out.println("Packet " + seq +
                    " says len=" + len + ", but only " + bb.remaining() + " left");
            len = bb.remaining();                        // salvage what we have
        }

        byte[] pcm = new byte[len];
        bb.get(pcm);

        /* store last good packet for PLC */
        System.arraycopy(pcm, 0, lastGoodPacket,
                        0, Math.min(pcm.length, lastGoodPacket.length));

        /* queue for playback */
        jitterBuffer.offer(new AudioPacket(seq, ts, flags, pcm));
    }

    
    private void processAudio() {
        try {
            initializeAudioLine();
            
            PriorityQueue<AudioPacket> playbackQueue = new PriorityQueue<>();
            long expectedSequence = 0;
            
            // Wait a bit before starting playback to allow buffer to fill
            Thread.sleep(500);
            
            System.out.println("AudioStreamReceiver: Starting audio playback");
            
            while (isRunning) {
                // Wait until we have enough packets in the jitter buffer to start playback
                while (isRunning && playbackQueue.size() < JITTER_BUFFER_SIZE / 4) {
                    AudioPacket packet = jitterBuffer.poll();
                    if (packet != null) {
                        playbackQueue.add(packet);
                    }
                    Thread.sleep(2); // Shorter sleep time for more responsive buffering
                }
                
                if (!isRunning) break;
                
                // Get the next packet to play
                AudioPacket packet = playbackQueue.poll();
                
                if (packet != null) {
                    // If this is the first packet or a big gap, reset sequence expectations
                    if (expectedSequence == 0 || packet.sequenceNumber > expectedSequence + 20) {
                        expectedSequence = packet.sequenceNumber;
                        System.out.println("AudioStreamReceiver: Starting/Resuming playback at sequence " + expectedSequence);
                    }
                    
                    // Check for packet loss
                    if (packet.sequenceNumber > expectedSequence) {
                        // Packet loss detected
                        long missedPackets = packet.sequenceNumber - expectedSequence;
                        if (missedPackets > 0 && missedPackets < 10) { // Don't try to fill huge gaps
                            System.out.println("AudioStreamReceiver: Packet loss detected, missing " + missedPackets + " packets");
                            // Apply packet loss concealment (repeat last good packet or interpolate)
                            for (long i = 0; i < missedPackets; i++) {
                                audioLine.write(lastGoodPacket, 0, lastGoodPacket.length);
                            }
                        }
                    }
                    
                    // Apply volume control if needed
                    byte[] processedAudio = applyVolume(packet.audioData, volume);
                    
                    // Play the audio data
                    audioLine.write(processedAudio, 0, processedAudio.length);
                    expectedSequence = packet.sequenceNumber + 1;
                } else {
                    // If we run out of packets, wait a bit
                    Thread.sleep(5);
                }
            }
        } catch (Exception e) {
            System.err.println("Error in audio processing: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (audioLine != null && audioLine.isOpen()) {
                audioLine.close();
            }
        }
    }
    
    private void initializeAudioLine() throws LineUnavailableException {
        // Set up the audio output line
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        
        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("AudioStreamReceiver: Audio format not supported by system!");
            
            // Try a fallback format
            AudioFormat fallbackFormat = new AudioFormat(
                    44100,     // Sample rate
                    16,        // Sample size in bits
                    2,         // Channels
                    true,      // Signed
                    false      // Little endian
            );
            
            info = new DataLine.Info(SourceDataLine.class, fallbackFormat);
            if (!AudioSystem.isLineSupported(info)) {
                throw new LineUnavailableException("No compatible audio output format available");
            }
        }
        
        audioLine = (SourceDataLine) AudioSystem.getLine(info);
        audioLine.open(audioFormat);
        audioLine.start();
        
        System.out.println("AudioStreamReceiver: Audio line initialized with format: " + audioFormat);
        System.out.println("AudioStreamReceiver: Buffer size: " + audioLine.getBufferSize() + " bytes");
    }
    
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    private byte[] applyVolume(byte[] audioData, float volume) {
        // If volume is 1.0, return the original array
        if (volume == 1.0f) {
            return audioData;
        }
        
        byte[] processedData = Arrays.copyOf(audioData, audioData.length);
        
        // For 16-bit audio (2 bytes per sample)
        for (int i = 0; i < processedData.length - 1; i += 2) {
            // Convert bytes to short (16-bit sample)
            short sample = (short) ((processedData[i] & 0xFF) | (processedData[i + 1] << 8));
            
            // Apply volume
            sample = (short) (sample * volume);
            
            // Convert back to bytes
            processedData[i] = (byte) (sample & 0xFF);
            processedData[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        
        return processedData;
    }
    
    // Class representing an audio packet with custom protocol fields
    private static class AudioPacket implements Comparable<AudioPacket> {
        private final long sequenceNumber;
        private final long timestamp;
        private final byte flags;
        private final byte[] audioData;
        
        public AudioPacket(long sequenceNumber, long timestamp, byte flags, byte[] audioData) {
            this.sequenceNumber = sequenceNumber;
            this.timestamp = timestamp;
            this.flags = flags;
            this.audioData = audioData;
        }
        
        @Override
        public int compareTo(AudioPacket other) {
            return Long.compare(this.sequenceNumber, other.sequenceNumber);
        }
    }
}