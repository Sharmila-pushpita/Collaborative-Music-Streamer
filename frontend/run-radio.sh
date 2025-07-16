#!/bin/bash

echo "🎵 Starting Collaborative Music Radio..."
echo "📻 The app will launch in fullscreen mode"
echo "💡 Press ESC to exit fullscreen, F11 to toggle"
echo ""

# Navigate to the script directory
cd "$(dirname "$0")"

# Run the application
mvn clean javafx:run

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Error starting the radio app"
    echo "🔧 Make sure Maven is installed and in your PATH"
    echo "📋 Check that Java 11+ is installed"
    read -p "Press Enter to continue..."
fi 