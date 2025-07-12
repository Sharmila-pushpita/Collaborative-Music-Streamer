import sys
import subprocess
import os
import shutil

def download_audio(youtube_url):
    """
    Downloads audio from a YouTube URL using yt-dlp.

    Args:
        youtube_url: The URL of the YouTube video.
    """
    try:
        # Use the local yt-dlp executable in the backend directory
        yt_dlp_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yt-dlp")
        
        # Make sure it's executable
        os.chmod(yt_dlp_path, 0o755)
        
        # Find ffmpeg path
        ffmpeg_path = shutil.which("ffmpeg")
        if not ffmpeg_path:
            print("Warning: ffmpeg not found in PATH. Will try using default location.")
            ffmpeg_path = "/usr/bin/ffmpeg"  # Standard location on Ubuntu
        
        # Create downloads directory if it doesn't exist
        download_dir = "downloaded_audios"
        os.makedirs(download_dir, exist_ok=True)
        
        command = [
            yt_dlp_path,
            '-x',
            '--audio-format', 'mp3',
            '--ffmpeg-location', ffmpeg_path,
            '-o', 'downloaded_audios/%(title)s.%(ext)s',
            youtube_url
        ]
        print(f"Executing command: {' '.join(command)}")
        result = subprocess.run(command, check=True, capture_output=True, text=True)
        print("Download successful!")
        print(result.stdout)
    except FileNotFoundError:
        print("Error: yt-dlp is not installed or not in your PATH.")
        print("Please install yt-dlp: https://github.com/yt-dlp/yt-dlp")
    except subprocess.CalledProcessError as e:
        print(f"Error downloading audio: {e}")
        print(e.stderr)

if __name__ == '__main__':
    if len(sys.argv) > 1:
        url = sys.argv[1]
        download_audio(url)
    else:
        print("Usage: python download.py <youtube_url>")
