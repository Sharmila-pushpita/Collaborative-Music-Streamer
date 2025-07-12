package com.musicstreamer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PrimaryController {

    @FXML
    private TextField youtubeUrlField;

    @FXML
    private Label nowPlayingLabel;

    @FXML
    private ListView<String> playlistView;
    
    @FXML
    private Button playPauseButton;
    
    @FXML
    private Slider volumeSlider;
    
    @FXML
    private ProgressBar progressBar;
    
    @FXML
    private Label timeLabel;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AudioStreamReceiver audioReceiver;
    private final int UDP_PORT = 54321;
    private boolean isPlaying = false;
    private ScheduledExecutorService executorService;

    @FXML
    public void initialize() {
        // Initialize UI elements
        volumeSlider.setValue(100);
        progressBar.setProgress(0);
        timeLabel.setText("0:00 / 0:00");
        
        // Setup audio receiver
        audioReceiver = new AudioStreamReceiver(UDP_PORT);
        
        // Add volume slider listener
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            handleVolumeChange();
        });
        
        // Start network connections in background thread
        new Thread(this::connectToServer).start();
        
        // Start the executor service for periodic UI updates
        executorService = Executors.newSingleThreadScheduledExecutor();
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 9090);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send SUBSCRIBE command with our UDP port
            out.println("SUBSCRIBE " + UDP_PORT);
            
            // Start audio receiver
            audioReceiver.start();

            // Listen for server responses
            String serverResponse;
            while ((serverResponse = in.readLine()) != null) {
                final String response = serverResponse;
                Platform.runLater(() -> updateUI(response));
            }
        } catch (IOException e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                nowPlayingLabel.setText("Disconnected from server.");
                playPauseButton.setDisable(true);
            });
        } finally {
            if (audioReceiver != null) {
                audioReceiver.stop();
            }
            if (executorService != null) {
                executorService.shutdown();
            }
        }
    }

    @FXML
    private void download() {
        String url = youtubeUrlField.getText();
        if (url != null && !url.isEmpty()) {
            out.println("DOWNLOAD " + url);
            youtubeUrlField.clear();
        }
    }
    
    @FXML
    private void togglePlayPause() {
        if (isPlaying) {
            out.println("PAUSE");
            playPauseButton.setText("Play");
        } else {
            out.println("PLAY");
            playPauseButton.setText("Pause");
        }
        isPlaying = !isPlaying;
    }
    
    @FXML
    private void skipTrack() {
        out.println("SKIP");
    }
    
    private void handleVolumeChange() {
        double volume = volumeSlider.getValue() / 100.0;
        // Send volume command to server for global volume control
        if (out != null) {
            out.println("VOLUME " + volume);
        }
    }

    private void updateUI(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            
            if (json.getString("type").equals("PLAYLIST_UPDATE")) {
                JSONObject payload = json.getJSONObject("payload");
                
                // Update now playing
                JSONObject nowPlaying = payload.optJSONObject("now_playing");
                if (nowPlaying != null && !nowPlaying.equals(JSONObject.NULL)) {
                    String title = nowPlaying.getString("title");
                    double duration = nowPlaying.optDouble("duration", 0);
                    nowPlayingLabel.setText(title);
                    
                    // Format duration as mm:ss
                    int minutes = (int) (duration / 60);
                    int seconds = (int) (duration % 60);
                    timeLabel.setText("0:00 / " + String.format("%d:%02d", minutes, seconds));
                } else {
                    nowPlayingLabel.setText("-");
                    timeLabel.setText("0:00 / 0:00");
                }
                
                // Update playlist
                JSONArray queue = payload.getJSONArray("queue");
                playlistView.getItems().clear();
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject song = queue.getJSONObject(i);
                    playlistView.getItems().add(song.getString("title"));
                }
                
                // Enable/disable controls based on playlist state
                boolean hasContent = queue.length() > 0;
                playPauseButton.setDisable(!hasContent);
            } else if (json.getString("type").equals("PLAYBACK_STATE")) {
                JSONObject payload = json.getJSONObject("payload");
                boolean playing = payload.getBoolean("playing");
                double progress = payload.getDouble("progress");
                
                isPlaying = playing;
                playPauseButton.setText(playing ? "Pause" : "Play");
                progressBar.setProgress(progress);
                
                // Update time label with current position
                if (payload.has("currentTime") && payload.has("duration")) {
                    double currentTime = payload.getDouble("currentTime");
                    double duration = payload.getDouble("duration");
                    
                    // Format as mm:ss
                    int currentMinutes = (int) (currentTime / 60);
                    int currentSeconds = (int) (currentTime % 60);
                    int totalMinutes = (int) (duration / 60);
                    int totalSeconds = (int) (duration % 60);
                    
                    timeLabel.setText(
                        String.format("%d:%02d / %d:%02d", 
                            currentMinutes, currentSeconds, 
                            totalMinutes, totalSeconds)
                    );
                }
            }
        } catch (JSONException e) {
            System.out.println("Error parsing server response: " + jsonResponse);
            e.printStackTrace();
        }
    }
    
    public void shutdown() {
        if (audioReceiver != null) {
            audioReceiver.stop();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
