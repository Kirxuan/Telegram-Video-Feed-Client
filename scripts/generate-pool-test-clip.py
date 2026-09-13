"""Generate the account-free Media3 instrumentation fixture (OpenCV, MPEG-4, no audio)."""
from pathlib import Path
import cv2
import numpy as np

path = Path(__file__).resolve().parents[1] / "player/src/androidTest/assets/pool-proof.mp4"
path.parent.mkdir(parents=True, exist_ok=True)
writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 24, (160, 96))
if not writer.isOpened():
    raise RuntimeError("MPEG-4 encoder unavailable")
for i in range(72):
    frame = np.zeros((96, 160, 3), dtype=np.uint8)
    frame[:] = (i * 3 % 256, 128, 32)
    frame[24:72, i % 112:i % 112 + 48] = (255, 220, 80)
    writer.write(frame)
writer.release()
print(path)
