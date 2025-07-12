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

    private static final List<ClientHandler>           clients          = Collections.synchronizedList(new ArrayList<>());
    private static final PlaylistManager               playlistManager  = new PlaylistManager(AUDIO_DIR);
    private static final ExecutorService               clientExecutor   = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService      scheduler        = Executors.newScheduledThreadPool(1);

    private static       boolean isPlaying      = false;
    private static       double  currentVolume  = 1.0;
    private static       double  currentTime    = 0.0;  // seconds
    private static       double  totalDuration  = 0.0;  // seconds

    /* ---------------------------------------------------------- *
     *  MAIN
     * ---------------------------------------------------------- */
    public static void main(String[] args) throws IOException {

        /* Ensure audio folder exists */
        File audioDir = new File(AUDIO_DIR);
        if (!audioDir.exists()) audioDir.mkdirs();

        /* TCP server for commands */
        new Thread(new TCPServer()).start();

        /* UDP broadcaster for raw PCM */
        new Thread(new UDPStreamBroadcaster()).start();

        /* Periodic playback-state broadcasts (every 0.5 s) */
        scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && !clients.isEmpty() && totalDuration > 0) {
                currentTime += 0.5;
                if (currentTime >= totalDuration) {
                    currentTime = 0;
                    playlistManager.moveToNextTrack();
                    broadcastPlaylistUpdate();
                }
            }
            broadcastPlaybackState();
        }, 0, 500, TimeUnit.MILLISECONDS);

        System.out.println("Server is running…");
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
                    System.out.println("New client: " + s.getInetAddress());
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
                        sendMessage(playlistManager.getPlaylistStateJson());
                        sendMessage(getPlaybackStateJson());
                    } else if (line.startsWith("DOWNLOAD")) {
                        downloadAndAddToPlaylist(line.substring(9).trim());
                    } else if (line.equals("PLAY")) {
                        isPlaying = true;
                    } else if (line.equals("PAUSE")) {
                        isPlaying = false;
                    } else if (line.equals("SKIP")) {
                        currentTime = 0;
                        playlistManager.moveToNextTrack();
                        broadcastPlaylistUpdate();
                    } else if (line.startsWith("VOLUME")) {
                        try {
                            currentVolume = Math.max(0, Math.min(1,
                                    Double.parseDouble(line.split(" ")[1])));
                        } catch (NumberFormatException ignored) { }
                    }
                    broadcastPlaybackState();
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) { }
                System.out.println("Client " + addr + " disconnected.");
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

        PlaylistManager(String d) { dir = new File(d); scanDirectory(); }

        synchronized void scanDirectory() {
            list.clear();
            File[] files = dir.listFiles((f,n)->n.endsWith(".mp3"));
            if (files != null) Collections.addAll(list, files);
            if (!list.isEmpty()) updateCurrentTrackDuration();
            System.out.println("Playlist: " + list.size() + " tracks.");
        }

        synchronized File getCurrentTrack() { return list.isEmpty() ? null : list.get(idx); }

        synchronized void moveToNextTrack() {
            if (!list.isEmpty()) {
                idx = (idx + 1) % list.size();
                updateCurrentTrackDuration();
            }
        }

        private void updateCurrentTrackDuration() {  // dummy 3-min placeholder
            totalDuration = 180.0;
            currentTime   = 0.0;
        }

        synchronized String getPlaylistStateJson() {
            StringBuilder queue = new StringBuilder();
            for (File f : list) queue.append(String.format("{\"title\":\"%s\"},", f.getName()));
            if (queue.length() > 0) queue.setLength(queue.length()-1);
            String now = list.isEmpty() ? "null" :
                String.format("{\"title\":\"%s\",\"duration\":%.1f}",
                              list.get(idx).getName(), totalDuration);
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

            while (true) {
                /* Wait until there's something to play */
                if (!isPlaying || clients.isEmpty()) {
                    sleep(100);
                    continue;
                }

                File song = playlistManager.getCurrentTrack();
                if (song == null) { sleep(500); continue; }

                /* --------- Start ffmpeg process for this track ---------- */
                Process ffmpeg;
                try {
                    ffmpeg = new ProcessBuilder(
                            "ffmpeg",
                            "-loglevel", "quiet",
                            "-ss", String.valueOf((int)currentTime),   // seek to currentTime
                            "-i",  song.getAbsolutePath(),
                            "-f",  "s16le",
                            "-acodec", "pcm_s16le",
                            "-ac", "2",
                            "-ar", String.valueOf(SAMPLE_RATE),
                            "-"
                    ).start();
                } catch (IOException e) {
                    e.printStackTrace();
                    sleep(1000);
                    continue;
                }

                try (InputStream pcm = ffmpeg.getInputStream()) {

                    int n;
                    while (isPlaying && (n = pcm.read(buffer)) != -1) {

                        /* Apply volume in-place */
                        applyVolume(buffer, n, currentVolume);

                        /* Build packet: 8 B seq, 8 B timestamp, 1 B flags, 2 B len */
                        long timestamp = System.currentTimeMillis();

                        ByteArrayOutputStream baos = new ByteArrayOutputStream(n + 19);
                        DataOutputStream dos = new DataOutputStream(baos);
                        dos.writeLong(seq++);
                        dos.writeLong(timestamp);
                        dos.writeByte(0);        // flags
                        dos.writeShort(n);
                        dos.write(buffer, 0, n);

                        byte[] pkt = baos.toByteArray();

                        /* Send to every subscribed client */
                        synchronized (clients) {
                            for (ClientHandler c : clients) {
                                if (c.getUdpPort() > 0) {
                                    DatagramPacket dp = new DatagramPacket(
                                            pkt, pkt.length,
                                            c.getAddr(),
                                            c.getUdpPort()
                                    );
                                    udp.send(dp);
                                }
                            }
                        }

                        /* Pace: bytes / BYTES_PER_SEC  →  sleep(ms) */
                        long ms = Math.max(1, (n * 1000L) / BYTES_PER_SEC);
                        sleep(ms);

                        currentTime += (double)n / BYTES_PER_SEC;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    ffmpeg.destroy();
                                }
                if (isPlaying) {
                    try {                               // handle checked IOException
                        sendEndOfStreamPacket();
                    } catch (IOException e) {
                        e.printStackTrace();            // or log + continue
                    }
                    playlistManager.moveToNextTrack();
                    broadcastPlaylistUpdate();
                    currentTime = 0.0;
                }

            }
        }

        private void sendEndOfStreamPacket() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(19);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(-1);                       // seq = -1 signals EOS
            dos.writeLong(System.currentTimeMillis());
            dos.writeByte(0x02);                     // EOS flag
            dos.writeShort(0);                       // len = 0
            byte[] pkt = baos.toByteArray();

            synchronized (clients) {
                for (ClientHandler c : clients) {
                    if (c.getUdpPort() > 0) {
                        udp.send(new DatagramPacket(pkt, pkt.length,
                                c.getAddr(), c.getUdpPort()));
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
    private static void broadcastPlaylistUpdate() { broadcast(playlistManager.getPlaylistStateJson()); }

    private static void broadcastPlaybackState()  { broadcast(getPlaybackStateJson()); }

    private static void broadcast(String msg) {
        synchronized (clients) { for (ClientHandler c : clients) c.sendMessage(msg); }
    }

    private static String getPlaybackStateJson() {
        return String.format(
            "{\"type\":\"PLAYBACK_STATE\",\"payload\":{\"playing\":%b,\"volume\":%.2f," +
            "\"progress\":%.2f,\"currentTime\":%.1f,\"duration\":%.1f}}",
            isPlaying, currentVolume,
            totalDuration > 0 ? currentTime / totalDuration : 0,
            currentTime, totalDuration
        );
    }

    /* ---------------------------------------------------------- *
     *  YT-DLP DOWNLOADER (unchanged)
     * ---------------------------------------------------------- */
    private static void downloadAndAddToPlaylist(String url) {
        try {
            System.out.println("Downloading: " + url);
            ProcessBuilder pb = new ProcessBuilder("python", "download.py", url);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ln; while ((ln = r.readLine()) != null) System.out.println(ln);
            }
            if (p.waitFor() == 0) {
                playlistManager.scanDirectory();
                broadcastPlaylistUpdate();
                if (playlistManager.list.size() == 1 && !isPlaying) isPlaying = true;
            } else {
                System.out.println("Download failed, exit=" + p.exitValue());
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
