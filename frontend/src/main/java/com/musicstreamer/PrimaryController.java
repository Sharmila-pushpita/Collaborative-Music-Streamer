package com.musicstreamer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;


public class PrimaryController {

    @FXML
    private TextField youtubeUrlField;

    @FXML
    private Label nowPlayingLabel;

    @FXML
    private ListView<String> playlistView;
    
    @FXML
    private Button playStopButton;
    
    @FXML
    private Slider volumeSlider;
    
    @FXML
    private Label volumeLabel;
    
    @FXML
    private Label connectionStatusLabel;
    
    @FXML
    private Label playlistCountLabel;
    
    @FXML
    private Button addSongButton;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AudioStreamReceiver audioReceiver;
    private boolean isConnected = false;
    private boolean isServerConnected = false;
    private ScheduledExecutorService executorService;
    
    // Playlist tracking for song index mapping
    private JSONArray currentPlaylist = new JSONArray();
    private volatile int lastKnownSongIndex = -1;

    @FXML
    public void initialize() {
        // Initialize UI elements
        volumeSlider.setValue(80);
        volumeLabel.setText("80%");
        updateConnectionStatus(ConnectionState.DISCONNECTED);
        nowPlayingLabel.setText("Waiting for stream...");
        playStopButton.setText("► Connect");
        playStopButton.getStyleClass().add("play-button");
        playStopButton.setDisable(false);
        updatePlaylistCount(0);
        
        // Setup audio receiver
        audioReceiver = new AudioStreamReceiver(5555);
        
        // Add volume slider listener for local volume control
        volumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            handleVolumeChange(newValue.doubleValue());
        });
        
        // Start network connections in background thread
        new Thread(this::connectToServer).start();
        
        // Start the executor service for periodic UI updates
        executorService = Executors.newSingleThreadScheduledExecutor();
        
        // Monitor song index changes from audio receiver
        executorService.scheduleWithFixedDelay(this::checkSongIndexChanges, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, PLAYING
    }

    private void updateConnectionStatus(ConnectionState state) {
        Platform.runLater(() -> {
            // Clear all status classes
            connectionStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected", "status-connecting");
            
            switch (state) {
                case DISCONNECTED:
                    connectionStatusLabel.setText("● Disconnected");
                    connectionStatusLabel.getStyleClass().add("status-disconnected");
                    break;
                case CONNECTING:
                    connectionStatusLabel.setText("● Connecting...");
                    connectionStatusLabel.getStyleClass().add("status-connecting");
                    break;
                case CONNECTED:
                    connectionStatusLabel.setText("● Connected");
                    connectionStatusLabel.getStyleClass().add("status-connecting");
                    break;
                case PLAYING:
                    connectionStatusLabel.setText("● Playing");
                    connectionStatusLabel.getStyleClass().add("status-connected");
                    break;
            }
        });
    }

    private void updatePlaylistCount(int count) {
        Platform.runLater(() -> {
            playlistCountLabel.setText(count + " song" + (count == 1 ? "" : "s"));
        });
    }

    private void connectToServer() {
        updateConnectionStatus(ConnectionState.CONNECTING);
        
        try {
            socket = new Socket("localhost", 9090);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            isServerConnected = true;
            Platform.runLater(() -> {
                playStopButton.setDisable(false);
                addSongButton.setDisable(false);
            });
            updateConnectionStatus(ConnectionState.CONNECTED);
            
            // Subscribe to UDP stream
            out.println("SUBSCRIBE 5555");
            
            // Start listening for server messages
            startListening();
            
        } catch (IOException e) {
            System.err.println("Could not connect to server: " + e.getMessage());
            Platform.runLater(() -> {
                playStopButton.setDisable(true);
                addSongButton.setDisable(true);
            });
            updateConnectionStatus(ConnectionState.DISCONNECTED);
        }
    }

    private void startListening() {
        new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    handleServerMessage(line);
                }
            } catch (IOException e) {
                System.err.println("Connection to server lost: " + e.getMessage());
                Platform.runLater(() -> {
                    isServerConnected = false;
                    playStopButton.setDisable(true);
                    addSongButton.setDisable(true);
                });
                updateConnectionStatus(ConnectionState.DISCONNECTED);
            }
        }).start();
    }

    @FXML
    private void addSong() {
        String url = youtubeUrlField.getText();
        if (url == null || url.trim().isEmpty()) {
            showAlert("Error", "URL cannot be empty.", AlertType.ERROR);
            return;
        }

        // Send URL directly to server (no validation)
        out.println("DOWNLOAD " + url);
        youtubeUrlField.clear();
    }
    
    @FXML
    private void togglePlayStop() {
        if (!isServerConnected) {
            return;
        }
        
        if (isConnected) {
            // Disconnect from radio stream
            audioReceiver.stop();
            isConnected = false;
            lastKnownSongIndex = -1; // Reset song index tracking
            playStopButton.setText("► Connect");
            playStopButton.getStyleClass().removeAll("play-button", "stop-button");
            playStopButton.getStyleClass().add("play-button");
            updateConnectionStatus(ConnectionState.CONNECTED);
            nowPlayingLabel.setText("Disconnected from stream");
        } else {
            // Connect to radio stream
            audioReceiver.start();
            isConnected = true;
            playStopButton.setText("■ Disconnect");
            playStopButton.getStyleClass().removeAll("play-button", "stop-button");
            playStopButton.getStyleClass().add("stop-button");
            updateConnectionStatus(ConnectionState.PLAYING);
            
            // Set initial state - will be updated when first packet arrives
            nowPlayingLabel.setText("Connecting to stream...");
            
            // Request current playing info
            out.println("STATUS");
        }
    }
    
    private void handleVolumeChange(double volume) {
        // Local volume control (not server-side)
        volumeLabel.setText(String.format("%.0f%%", volume));
        
        // Apply volume to audio receiver
        if (audioReceiver != null) {
            audioReceiver.setVolume((float) (volume / 100.0));
        }
    }
    
    private void checkSongIndexChanges() {
        if (!isConnected || audioReceiver == null) {
            return;
        }
        
        // Check if song index has changed from audio receiver
        int currentSongIndex = audioReceiver.getCurrentSongIndex();
        if (currentSongIndex >= 0 && currentSongIndex != lastKnownSongIndex) {
            lastKnownSongIndex = currentSongIndex;
            updateNowPlayingFromSongIndex(currentSongIndex);
        }
    }
    
    private void updateNowPlayingFromSongIndex(int songIndex) {
        Platform.runLater(() -> {
            try {
                if (songIndex >= 0 && songIndex < currentPlaylist.length()) {
                    JSONObject song = currentPlaylist.getJSONObject(songIndex);
                    String songTitle = song.getString("title");
                    nowPlayingLabel.setText("♫ " + songTitle);
                    System.out.println("Updated now playing from packet song index: " + songTitle);
                    
                    // Also update the playlist display to show the correct "Now Playing" indicator
                    refreshPlaylistDisplay();
                } else {
                    nowPlayingLabel.setText("Playing unknown song (index: " + songIndex + ")");
                }
            } catch (JSONException e) {
                System.err.println("Error updating now playing from song index: " + e.getMessage());
            }
        });
    }
    
    private void refreshPlaylistDisplay() {
        playlistView.getItems().clear();
        for (int i = 0; i < currentPlaylist.length(); i++) {
            try {
                JSONObject song = currentPlaylist.getJSONObject(i);
                String songTitle = song.getString("title");
                
                // Show current playing song based on actual song index from packets
                if (isConnected && i == lastKnownSongIndex) {
                    playlistView.getItems().add("♫ " + songTitle + " • Now Playing");
                } else {
                    playlistView.getItems().add("♪ " + songTitle);
                }
            } catch (JSONException e) {
                System.err.println("Error processing playlist item: " + e.getMessage());
            }
        }
        updatePlaylistCount(currentPlaylist.length());
    }

    private void handleServerMessage(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            
            if (json.getString("type").equals("PLAYLIST_UPDATE")) {
                JSONObject payload = json.getJSONObject("payload");
                
                // Update now playing only when not connected to stream
                // When connected, the nowPlayingLabel is controlled by packet-based song index updates
                if (!isConnected) {
                    JSONObject nowPlaying = payload.optJSONObject("now_playing");
                    Platform.runLater(() -> {
                        if (nowPlaying != null && !nowPlaying.equals(JSONObject.NULL)) {
                            String title = nowPlaying.getString("title");
                            nowPlayingLabel.setText("♫ " + title);
                        } else {
                            nowPlayingLabel.setText("No songs in playlist");
                        }
                    });
                }
                
                // Update playlist
                JSONArray queue = payload.getJSONArray("queue");
                currentPlaylist = queue; // Store for song index mapping
                Platform.runLater(() -> {
                    refreshPlaylistDisplay();
                });
            } else if (json.getString("type").equals("PLAYBACK_STATE")) {
                JSONObject payload = json.getJSONObject("payload");
                
                // Only update radio status - don't update nowPlayingLabel from server broadcasts
                // The nowPlayingLabel is now controlled by packet-based song index updates
                if (payload.has("radioActive")) {
                    boolean radioActive = payload.getBoolean("radioActive");
                    if (!radioActive && isConnected) {
                        Platform.runLater(() -> {
                            nowPlayingLabel.setText("Radio is currently offline");
                        });
                    }
                }
            } else if (json.getString("type").equals("DOWNLOAD_COMPLETE")) {
                // Success message
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("✓ Song added");
                    connectionStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected", "status-connecting");
                    connectionStatusLabel.getStyleClass().add("status-connected");
                    
                    // Reset status after 2 seconds
                    executorService.schedule(() -> {
                        Platform.runLater(() -> {
                            if (isServerConnected) {
                                updateConnectionStatus(isConnected ? ConnectionState.PLAYING : ConnectionState.CONNECTED);
                            }
                        });
                    }, 2, java.util.concurrent.TimeUnit.SECONDS);
                });
            } else if (json.getString("type").equals("DOWNLOAD_ERROR")) {
                // Error adding song
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("✗ Failed to add song");
                    connectionStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected", "status-connecting");
                    connectionStatusLabel.getStyleClass().add("status-disconnected");
                    
                    // Reset status after 3 seconds
                    executorService.schedule(() -> {
                        Platform.runLater(() -> {
                            if (isServerConnected) {
                                updateConnectionStatus(isConnected ? ConnectionState.PLAYING : ConnectionState.CONNECTED);
                            }
                        });
                    }, 3, java.util.concurrent.TimeUnit.SECONDS);
                });
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

    private void showAlert(String title, String content, AlertType alertType) {
        Platform.runLater(() -> {
            Alert alert = new Alert(alertType);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}
