import sys
import subprocess
import os

def download_audio(youtube_url):
    """
    Downloads audio from a YouTube URL using yt-dlp.

    Args:
        youtube_url: The URL of the YouTube video.
    """
    try:
        # Create downloads directory if it doesn't exist
        download_dir = "downloaded_audios"
        os.makedirs(download_dir, exist_ok=True)
        
        # Build command - use yt-dlp directly from PATH
        command = [
            'yt-dlp',
            '-x',  # Extract audio only
            '--audio-format', 'mp3',
            '--audio-quality', '0',  # Best quality
            '-o', 'downloaded_audios/%(title)s.%(ext)s',
            '--no-playlist',  # Only download single video, not playlist
            '--embed-metadata',  # Embed metadata
            youtube_url
        ]
        
        print(f"Downloading: {youtube_url}")
        print(f"Command: {' '.join(command)}")
        
        # Run the command
        result = subprocess.run(command, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
        print("[SUCCESS] Download completed successfully!")
        if result.stdout:
            print("Output:")
            print(result.stdout)
            
    except FileNotFoundError as e:
        print(f"[ERROR] yt-dlp not found in PATH: {e}")
        print("Please install yt-dlp: pip install yt-dlp")
        sys.exit(1)
        
    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Download failed (exit code {e.returncode})")
        if e.stderr:
            print("Error details:")
            print(e.stderr)
        if e.stdout:
            print("Output:")
            print(e.stdout)
        sys.exit(1)
        
    except Exception as e:
        print(f"[ERROR] Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == '__main__':
    if len(sys.argv) > 1:
        url = sys.argv[1]
        download_audio(url)
    else:
        print("Usage: python download.py <youtube_url>")
        sys.exit(1)
