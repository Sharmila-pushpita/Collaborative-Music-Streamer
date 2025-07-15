@echo off
title Collaborative Music Radio Launcher
echo.
echo 🎵 Starting Collaborative Music Radio...
echo 📻 The app will launch in fullscreen mode
echo 💡 Press ESC to exit fullscreen, F11 to toggle
echo.

cd /d "%~dp0"
call mvn clean javafx:run

if %errorlevel% neq 0 (
    echo.
    echo ❌ Error starting the radio app
    echo 🔧 Make sure Maven is installed and in your PATH
    echo 📋 Check that Java 11+ is installed
    pause
) 