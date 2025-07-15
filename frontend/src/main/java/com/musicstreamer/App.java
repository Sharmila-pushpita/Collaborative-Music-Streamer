
package com.musicstreamer;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

/**
 * JavaFX App
 */
public class App extends Application {

    private static Scene scene;
    private static PrimaryController controller;
    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws IOException {
        primaryStage = stage;
        
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource("primary.fxml"));
        Parent root = fxmlLoader.load();
        controller = fxmlLoader.getController();
        
        scene = new Scene(root, 1000, 800);
        stage.setScene(scene);
        stage.setTitle("♪ Collaborative Music Radio");
        stage.setResizable(true);
        stage.setMinWidth(900);
        stage.setMinHeight(700);
        
        // Configure fullscreen behavior
        setupFullscreenMode(stage);
        
        // Handle window close event
        stage.setOnCloseRequest(event -> {
            if (controller != null) {
                controller.shutdown();
            }
        });
        
        // Start in fullscreen mode
        stage.setFullScreen(true);
        stage.show();
    }

    private void setupFullscreenMode(Stage stage) {
        // Configure fullscreen settings
        stage.setFullScreenExitHint("Press ESC to exit fullscreen • Press F11 to toggle fullscreen");
        stage.setFullScreenExitKeyCombination(KeyCombination.valueOf("ESC"));
        
        // Add keyboard shortcuts
        scene.setOnKeyPressed(event -> {
            // F11 to toggle fullscreen
            if (event.getCode() == KeyCode.F11) {
                toggleFullscreen();
                event.consume();
            }
            // Alt+Enter to toggle fullscreen (alternative)
            else if (event.getCode() == KeyCode.ENTER && event.isAltDown()) {
                toggleFullscreen();
                event.consume();
            }
            // ESC to exit fullscreen (custom behavior)
            else if (event.getCode() == KeyCode.ESCAPE && stage.isFullScreen()) {
                stage.setFullScreen(false);
                event.consume();
            }
        });
        
        // Handle fullscreen state changes
        stage.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> {
            if (isFullScreen) {
                System.out.println("🎵 Radio entered fullscreen mode");
                // Optional: Hide system cursor after a delay in fullscreen
                // scene.setCursor(Cursor.NONE);
            } else {
                System.out.println("🎵 Radio exited fullscreen mode");
                // scene.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private void toggleFullscreen() {
        if (primaryStage != null) {
            primaryStage.setFullScreen(!primaryStage.isFullScreen());
        }
    }

    public static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }
}
