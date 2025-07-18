import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class server {

    /* ---------------------------------------------------------- *
     *  GLOBAL CONSTANTS / STATE
     * ---------------------------------------------------------- */
    private static final int    TCP_PORT   = 9090;
    private static final String AUDIO_DIR  = "downloaded_audios";

    private static final int    SAMPLE_RATE = 44_100; // Hz
    private static final int    CHANNELS    = 2;      // stereo
    private static final int    BYTES_PER_SEC = SAMPLE_RATE * CHANNELS * 2; // 16-bit
    private static final int    HEADER_SIZE_BYTES = 23; // 8+8+1+4+2 = seq+timestamp+flags+song_index+length

    private static final List<ClientHandler>           clients          = Collections.synchronizedList(new ArrayList<>());
    private static final PlaylistManager               playlistManager  = new PlaylistManager(AUDIO_DIR);
    private static final ExecutorService               clientExecutor   = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService      scheduler        = Executors.newScheduledThreadPool(1);
    private static volatile boolean                    songWasManuallyChanged = false; // Flag to handle playlist changes

    // Radio state - always playing if there are songs
    private static       boolean isRadioActive   = false;  // Radio is on/off
    private static       double  currentVolume   = 1.0;
    private static       double  currentTime     = 0.0;    // seconds into current song
    private static       double  totalDuration   = 0.0;    // seconds
    private static       long    songStartTime   = 0;      // System.currentTimeMillis() when song started
    private static       boolean isStreamActive  = false;  // Whether UDP stream is actively running

    /* ---------------------------------------------------------- *
     *  MAIN
     * ---------------------------------------------------------- */
    public static void main(String[] args) throws IOException {

        /* Ensure audio folder exists */
        File audioDir = new File(AUDIO_DIR);
        if (!audioDir.exists()) audioDir.mkdirs();

        /* Initialize playlist and start radio if songs exist */
        playlistManager.scanDirectory();
        if (playlistManager.hasSongs()) {
            isRadioActive = true;
            System.out.println("🎵 Radio server starting with " + playlistManager.getSongCount() + " songs");
        } else {
            System.out.println("📻 Radio server started - waiting for songs to be added");
        }

        /* TCP server for commands */
        new Thread(new TCPServer()).start();

        /* UDP broadcaster for raw PCM - starts immediately */
        new Thread(new UDPStreamBroadcaster()).start();

        /* Periodic status broadcasts (every 1 second) */
        scheduler.scheduleAtFixedRate(() -> {
            updateCurrentTime();
            broadcastPlaybackState();
        }, 0, 1, TimeUnit.SECONDS);

        /* Periodic playlist updates (every 5 seconds) */
        scheduler.scheduleAtFixedRate(() -> {
            broadcastPlaylistUpdate();
        }, 0, 5, TimeUnit.SECONDS);

        System.out.println("🎵 Collaborative Music Radio Server is running on port " + TCP_PORT);
        System.out.println("📻 Radio status: " + (isRadioActive ? "ON AIR" : "OFF AIR"));

        // Start console handler for admin commands
        new Thread(new ConsoleHandler()).start();
    }

    /* ---------------------------------------------------------- *
     *  TCP SERVER — command & control
     * ---------------------------------------------------------- */
    static class TCPServer implements Runnable {
        @Override
        public void run() {
            try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
                while (true) {
                    Socket s = ss.accept();
                    ClientHandler ch = new ClientHandler(s);
                    clients.add(ch);
                    clientExecutor.submit(ch);
                    System.out.println("📡 New client connected: " + s.getInetAddress());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /* ---------------------------------------------------------- *
     *  CLIENT HANDLER (one per TCP connection)
     * ---------------------------------------------------------- */
    static class ClientHandler implements Runnable {

        private final Socket        socket;
        private final InetAddress   addr;
        private PrintWriter         out;
        private BufferedReader      in;
        private int                 udpPort = -1;

        ClientHandler(Socket s) {
            socket = s;
            addr   = s.getInetAddress();
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("SUBSCRIBE")) {
                        udpPort = Integer.parseInt(line.split(" ")[1]);
                        System.out.println("📡 Client " + addr + " subscribed to UDP port " + udpPort);
                        sendMessage(playlistManager.getPlaylistStateJson());
                        sendMessage(getPlaybackStateJson());
                    } else if (line.startsWith("DOWNLOAD")) {
                        downloadAndAddToPlaylist(line.substring(9).trim());
                    } else if (line.startsWith("STATUS")) {
                        sendMessage(playlistManager.getPlaylistStateJson());
                        sendMessage(getPlaybackStateJson());
                    } else if (line.startsWith("VOLUME")) {
                        // Keep volume control for server admin purposes
                        try {
                            currentVolume = Math.max(0, Math.min(1,
                                    Double.parseDouble(line.split(" ")[1])));
                            System.out.println("🔊 Server volume set to " + (int)(currentVolume * 100) + "%");
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) { }
                System.out.println("📡 Client " + addr + " disconnected.");
            }
        }

        void sendMessage(String msg) { out.println(msg); }
        InetAddress getAddr()        { return addr;      }
        int         getUdpPort()     { return udpPort;   }
    }

    /* ---------------------------------------------------------- *
     *  PLAYLIST MANAGEMENT
     * ---------------------------------------------------------- */
    static class PlaylistManager {

        private final File       dir;
        private final List<File> list = new ArrayList<>();
        private int              idx  = 0;

        PlaylistManager(String d) { dir = new File(d); }

        synchronized void scanDirectory() {
            int oldSize = list.size();
            list.clear();
            File[] files = dir.listFiles((f,n)->n.endsWith(".mp3"));
            if (files != null) Collections.addAll(list, files);
            
            if (list.size() > oldSize) {
                System.out.println("📋 Playlist updated: " + list.size() + " tracks");
                // If this is the first song and radio wasn't active, start it
                if (!isRadioActive && !list.isEmpty()) {
                    isRadioActive = true;
                    resetCurrentSong();
                    System.out.println("🎵 Radio starting with first song!");
                }
            }
            
            if (!list.isEmpty()) {
                updateCurrentTrackDuration();
            }
        }

        synchronized boolean hasSongs() { return !list.isEmpty(); }
        synchronized int getSongCount() { return list.size(); }
        synchronized File getCurrentTrack() { return list.isEmpty() ? null : list.get(idx); }
        synchronized List<File> getPlaylist() { return new ArrayList<>(list); }
        synchronized int getCurrentSongIndex() { return idx; }

        synchronized boolean deleteSong(int songIndex) {
            if (songIndex < 0 || songIndex >= list.size()) {
                System.out.println("❌ Invalid song index.");
                return false;
            }
        
            File songToDelete = list.get(songIndex);
            String songName = songToDelete.getName();
            boolean wasCurrentSong = (songIndex == idx);
        
            // If deleting the current song, we must switch tracks first to release the file lock.
            if (wasCurrentSong) {
                System.out.println("🎵 Current song is playing. Switching to the next track before deleting...");
                // Note: moveToNextTrack() also calls resetCurrentSong() and updateCurrentTrackDuration()
                moveToNextTrack();
                songWasManuallyChanged = true; // Signal the streamer to restart with the new song
            }
        
            // Update the playlist data structure
            list.remove(songIndex);
            System.out.println("📋 Removed '" + songName + "' from playlist.");
        
            // If a song *before* the current one was deleted, the current index shifts down.
            if (!wasCurrentSong && songIndex < idx) {
                idx--;
            }
        
            // If the playlist is now empty, turn off the radio.
            if (list.isEmpty()) {
                isRadioActive = false;
                idx = 0;
                currentTime = 0;
                totalDuration = 0;
                songWasManuallyChanged = true; // Ensure streamer loop stops
                System.out.println("📻 Playlist is empty. Radio OFF AIR.");
            }
        
            broadcastPlaylistUpdate();
        
            // Defer the actual file deletion to give the streamer time to release the file lock.
            new Thread(() -> {
                if (wasCurrentSong) {
                    // Give the streamer a moment to kill the old ffmpeg process.
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (songToDelete.delete()) {
                    System.out.println("🗑️ Deleted song file: " + songName);
                } else {
                    System.out.println("❌ Failed to delete song file: " + songName + ". It may still be locked.");
                }
            }).start();
        
            return true; // Logical deletion was successful.
        }

        synchronized void moveToNextTrack() {
            if (!list.isEmpty()) {
                idx = (idx + 1) % list.size();
                resetCurrentSong();
                updateCurrentTrackDuration();
                System.out.println("🎵 Now playing: " + getCurrentTrack().getName());
            }
        }

        private void resetCurrentSong() {
            currentTime = 0.0;
            songStartTime = System.currentTimeMillis();
        }

        private void updateCurrentTrackDuration() {
            File current = getCurrentTrack();
            if (current == null) {
                totalDuration = 0.0;
                return;
            }
            try {
                // Use ffprobe to get duration in seconds
                ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries",
                    "format=duration", "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    current.getAbsolutePath()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = r.readLine();
                    if (line != null) {
                        totalDuration = Double.parseDouble(line.trim());
                    } else {
                        totalDuration = 180.0; // fallback
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                System.err.println("Failed to get duration for " + current.getName() + ": " + e.getMessage());
                totalDuration = 180.0; // fallback
            }
        }
        
        private String escapeJson(String s) {
            if (s == null) return null;
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        synchronized String getPlaylistStateJson() {
            StringBuilder queue = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                queue.append(String.format("{\"title\":\"%s\",\"isPlaying\":%b}",
                    escapeJson(f.getName()), i == idx));
                if (i < list.size() - 1) queue.append(",");
            }
            
            String now = list.isEmpty() ? "null" :
                String.format("{\"title\":\"%s\",\"duration\":%.1f}",
                              escapeJson(list.get(idx).getName()), totalDuration);
            return String.format("{\"type\":\"PLAYLIST_UPDATE\",\"payload\":{\"queue\":[%s],\"now_playing\":%s}}",
                                 queue, now);
        }
    }

    /* ---------------------------------------------------------- *
     *  UDP STREAM BROADCASTER (PCM over UDP)
     * ---------------------------------------------------------- */
    static class UDPStreamBroadcaster implements Runnable {

        private final DatagramSocket udp;

        UDPStreamBroadcaster() {
            DatagramSocket tmp = null;
            try { tmp = new DatagramSocket(); }
            catch (SocketException e) { e.printStackTrace(); }
            udp = tmp;
        }

        @Override
        public void run() {
            if (udp == null) return;

            byte[] buffer = new byte[4096];  // ≈93 ms of PCM
            long   seq    = 0;

            System.out.println("📻 UDP Stream Broadcaster started");

            while (true) {
                /* Wait until radio is active and has songs */
                if (!isRadioActive || !playlistManager.hasSongs()) {
                    sleep(1000);
                    continue;
                }

                File song = playlistManager.getCurrentTrack();
                if (song == null) { 
                    sleep(1000); 
                    continue; 
                }

                isStreamActive = true;
                System.out.println("🎵 Starting stream for: " + song.getName());

                /* --------- Start ffmpeg process for this track ---------- */
                Process ffmpeg;
                try {
                    // Calculate accurate seek position
                    double seekTime = Math.max(0, currentTime);
                    
                    ffmpeg = new ProcessBuilder(
                            "ffmpeg",
                            "-loglevel", "quiet",
                            "-ss", String.format("%.3f", seekTime),   // Accurate seek
                            "-i",  song.getAbsolutePath(),
                            "-f",  "s16le",
                            "-acodec", "pcm_s16le",
                            "-ac", "2",
                            "-ar", String.valueOf(SAMPLE_RATE),
                            "-"
                    ).start();
                } catch (IOException e) {
                    System.err.println("❌ Failed to start ffmpeg for " + song.getName());
                    e.printStackTrace();
                    sleep(5000);
                    continue;
                }

                try (InputStream pcm = ffmpeg.getInputStream()) {
                    int n;
                    while (isRadioActive && (n = pcm.read(buffer)) != -1) {
                        // Check if we need to move to next track
                        if (currentTime >= totalDuration) {
                            break; // Will trigger next track
                        }

                        if (songWasManuallyChanged) {
                            songWasManuallyChanged = false; // Reset flag
                            System.out.println("Manual track change detected, restarting stream.");
                            break;
                        }

                        /* Apply volume in-place */
                        applyVolume(buffer, n, currentVolume);

                        /* Build packet: 8 B seq, 8 B timestamp, 1 B flags, 4 B song_index, 2 B len */
                        long timestamp = (long) currentTime;
                        int songIndex = playlistManager.getCurrentSongIndex();

                        ByteArrayOutputStream baos = new ByteArrayOutputStream(n + HEADER_SIZE_BYTES);
                        DataOutputStream dos = new DataOutputStream(baos);
                        dos.writeLong(seq++);
                        dos.writeLong(timestamp);
                        dos.writeByte(0);        // flags
                        dos.writeInt(songIndex); // song index in playlist
                        dos.writeShort(n);
                        dos.write(buffer, 0, n);

                        byte[] pkt = baos.toByteArray();

                        /* Send to every subscribed client (even if no clients, keep streaming) */
                        synchronized (clients) {
                            for (ClientHandler c : clients) {
                                if (c.getUdpPort() > 0) {
                                    DatagramPacket dp = new DatagramPacket(
                                            pkt, pkt.length,
                                            c.getAddr(),
                                            c.getUdpPort()
                                    );
                                    try {
                                        udp.send(dp);
                                    } catch (IOException e) {
                                        // Client might have disconnected, continue
                                    }
                                }
                            }
                        }

                        /* Pace: bytes / BYTES_PER_SEC  →  sleep(ms) */
                        long ms = Math.max(1, (n * 1000L) / BYTES_PER_SEC);
                        sleep(ms);
                    }
                } catch (IOException e) {
                    System.err.println("❌ Error streaming " + song.getName());
                    e.printStackTrace();
                } finally {
                    ffmpeg.destroyForcibly();
                    isStreamActive = false;
                }

                // Move to next track after song finishes
                if (isRadioActive) {
                    try {
                        sendEndOfStreamPacket();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // playlistManager.moveToNextTrack();
                    // broadcastPlaylistUpdate();
                    songStartTime = System.currentTimeMillis(); // Reset start time for next track
                }
            }
        }

        private void sendEndOfStreamPacket() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(HEADER_SIZE_BYTES);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(-1);                       // seq = -1 signals EOS
            dos.writeLong(System.currentTimeMillis());
            dos.writeByte(0x02);                     // EOS flag
            dos.writeInt(playlistManager.getCurrentSongIndex()); // song index
            dos.writeShort(0);                       // len = 0
            byte[] pkt = baos.toByteArray();

            synchronized (clients) {
                for (ClientHandler c : clients) {
                    if (c.getUdpPort() > 0) {
                        try {
                            udp.send(new DatagramPacket(pkt, pkt.length,
                                    c.getAddr(), c.getUdpPort()));
                        } catch (IOException e) {
                            // Client disconnected, continue
                        }
                    }
                }
            }
        }

        /* 16-bit PCM volume scale */
        private void applyVolume(byte[] buf, int len, double vol) {
            for (int i = 0; i + 1 < len; i += 2) {
                short s = (short)((buf[i] & 0xFF) | (buf[i+1] << 8));
                s = (short)(s * vol);
                buf[i]   = (byte)(s & 0xFF);
                buf[i+1] = (byte)((s >> 8) & 0xFF);
            }
        }

        private void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
        }
    }

    /* ---------------------------------------------------------- *
     *  UTILITY: broadcast helpers
     * ---------------------------------------------------------- */
    private static void updateCurrentTime() {
        if (isRadioActive && playlistManager.hasSongs()) {
            long now = System.currentTimeMillis();
            currentTime = (now - songStartTime) / 1000.0;

            // System.out.println("DEBUG: currentTime=" + currentTime + ", totalDuration=" + totalDuration + ", songIndex=" + playlistManager.getCurrentSongIndex() + ", songCount=" + playlistManager.getSongCount());
            
            // Check if current song finished
            if (currentTime >= totalDuration) {
                // System.out.println("DEBUG: Song finished - moving to next track");
                playlistManager.moveToNextTrack();
                broadcastPlaylistUpdate();
            }
        }
    }

    private static void broadcastPlaylistUpdate() { 
        broadcast(playlistManager.getPlaylistStateJson()); 
    }

    private static void broadcastPlaybackState() { 
        broadcast(getPlaybackStateJson()); 
    }

    private static void broadcast(String msg) {
        synchronized (clients) { 
            for (ClientHandler c : clients) {
                try {
                    c.sendMessage(msg);
                } catch (Exception e) {
                    // Client might have disconnected
                }
            }
        }
    }

    private static String getPlaybackStateJson() {
        File currentSong = playlistManager.getCurrentTrack();
        String songName = currentSong != null ? currentSong.getName() : "No song";
        
        // Proper JSON escaping for song names with quotes or backslashes
        String escapedSongName = songName.replace("\\", "\\\\").replace("\"", "\\\"");

        return String.format(
            "{\"type\":\"PLAYBACK_STATE\",\"payload\":{\"playing\":%b,\"volume\":%.2f," +
            "\"progress\":%.3f,\"currentTime\":%.1f,\"duration\":%.1f,\"currentSong\":\"%s\",\"radioActive\":%b}}",
            isRadioActive && isStreamActive, currentVolume,
            totalDuration > 0 ? Math.min(1.0, currentTime / totalDuration) : 0,
            currentTime, totalDuration, escapedSongName, isRadioActive
        );
    }

    /* ---------------------------------------------------------- *
     *  CONSOLE HANDLER for admin commands
     * ---------------------------------------------------------- */
    static class ConsoleHandler implements Runnable {
        @Override
        public void run() {
            BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
            // Slight delay to ensure server startup messages appear first
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            System.out.println("\n---------------------------------------------------");
            System.out.println("     SERVER ADMIN CONSOLE IS READY");
            System.out.println("---------------------------------------------------");
            System.out.println("Commands: 'list' (show playlist), 'delete' (remove song), 'exit' (shutdown console).");


            while (true) {
                try {
                    System.out.print("\nadmin> ");
                    String command = consoleReader.readLine();
                    if (command == null || command.equalsIgnoreCase("exit")) {
                        System.out.println("Shutting down admin console.");
                        break;
                    }

                    if (command.equalsIgnoreCase("list")) {
                        printPlaylist();
                    } else if (command.equalsIgnoreCase("delete")) {
                        handleDelete(consoleReader);
                    } else if (!command.trim().isEmpty()) {
                         System.out.println("Unknown command. Available: 'list', 'delete', 'exit'");
                    }

                } catch (IOException e) {
                    System.err.println("Error reading from console: " + e.getMessage());
                    break;
                }
            }
        }

        private void printPlaylist() {
            synchronized (playlistManager) {
                List<File> playlist = playlistManager.getPlaylist();
                if (playlist.isEmpty()) {
                    System.out.println("Playlist is empty.");
                    return;
                }
                System.out.println("\n--- Current Playlist ---");
                for (int i = 0; i < playlist.size(); i++) {
                    boolean isPlaying = (i == playlistManager.getCurrentSongIndex() && isRadioActive);
                    System.out.printf("  [%d] %s %s\n", i, playlist.get(i).getName(), isPlaying ? "<- NOW PLAYING" : "");
                }
                System.out.println("------------------------");
            }
        }

        private void handleDelete(BufferedReader reader) throws IOException {
             printPlaylist();
             if (!playlistManager.hasSongs()) return;

             System.out.print("Enter the index of the song to delete: ");
             try {
                String indexStr = reader.readLine();
                if (indexStr == null) return;
                int index = Integer.parseInt(indexStr.trim());
                playlistManager.deleteSong(index);
             } catch (NumberFormatException e) {
                System.out.println("Invalid number format. Please enter a valid index.");
             }
        }
    }


    /* ---------------------------------------------------------- *
     *  YT-DLP DOWNLOADER
     * ---------------------------------------------------------- */
    private static void downloadAndAddToPlaylist(String url) {
        new Thread(() -> {
            try {
                System.out.println("📥 Downloading: " + url);
                ProcessBuilder pb = new ProcessBuilder("python", "download.py", url);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String ln; 
                    while ((ln = r.readLine()) != null) {
                        System.out.println(ln);
                    }
                }
                
                if (p.waitFor() == 0) {
                    System.out.println("✅ Download completed successfully");
                    playlistManager.scanDirectory();
                    broadcastPlaylistUpdate();
                    
                    // Send success message to clients
                    broadcast("{\"type\":\"DOWNLOAD_COMPLETE\",\"payload\":{\"url\":\"" + url + "\"}}");
                } else {
                    System.out.println("❌ Download failed, exit code: " + p.exitValue());
                    broadcast("{\"type\":\"DOWNLOAD_ERROR\",\"payload\":{\"url\":\"" + url + "\"}}");
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("❌ Download error: " + e.getMessage());
                e.printStackTrace();
                broadcast("{\"type\":\"DOWNLOAD_ERROR\",\"payload\":{\"url\":\"" + url + "\"}}");
            }
        }).start();
    }
}

