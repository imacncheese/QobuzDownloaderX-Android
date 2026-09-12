"""
Emit a minimal but structurally valid silent MPEG-1 Layer III file.

JAudioTagger only parses frame headers to tag a file, so a stream of correct
headers with zeroed payloads is enough for the tagger to accept it, and it keeps
the fixture tiny enough to commit.

Header bytes FF FB 90 00:
  0xFF 0xFB  MPEG-1, Layer III, no CRC
  0x90       bitrate index 9 (128 kbps), sample rate index 0 (44100 Hz), no padding
  0x00       stereo, no emphasis
Frame size = floor(144 * 128000 / 44100) = 417 bytes = 4 header + 413 payload.

Used by the on-device tagger tests: MP3 lyrics go into an ID3 frame, and the
frame class has to match the tag's ID3 version, which is exactly the kind of
thing a JVM test cannot check because JAudioTagger cannot grow an ID3 tag on
Windows at all.
"""
import sys

FRAME = bytes([0xFF, 0xFB, 0x90, 0x00]) + bytes(413)
FRAMES = 200


def main(path: str) -> None:
    with open(path, "wb") as f:
        for _ in range(FRAMES):
            f.write(FRAME)
    print(f"wrote {path} frames={FRAMES} bytes={FRAMES * len(FRAME)}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "silence.mp3")
