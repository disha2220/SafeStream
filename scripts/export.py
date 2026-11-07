from ultralytics import YOLO

# Load a base YOLOv8 nano model (or your trained face model weights .pt file)
model = YOLO("yolov8n.pt") 

# Export it to TFLite format for mobile
model.export(
    format="tflite",
    imgsz=320,        # Must match INPUT_SIZE = 320 in YoloDetector.kt
    int8=True,        # Compresses for mobile NPU/CPU acceleration
    simplify=True
)