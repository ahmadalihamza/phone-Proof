# Cosmetic analysis

No trained PhoneProof weights were supplied. The defect classifier returns UNAVAILABLE without predictions. Real OpenCV image QC and manual twelve-view review work independently.

Optional worker: install a separately pinned/evaluated Ultralytics/PyTorch environment, mount weights read-only, set MODEL_MANIFEST, and run `celery -A app.worker.celery worker --loglevel=info`. Manifest fields: weights (relative file), sha256, version, validated (true only after actual validation), classes (all twelve defect classes from media.py), threshold (calibrated).

The worker checks the actual model classes and checksum, records model version and confidence, retries three times and persists failure state. Findings are advisory and never modify raw functional evidence. Phone detection, perspective rectification and region segmentation require separately trained/validated components and are not claimed implemented.

Dataset split must separate physical phones across train/validation/test sets. Evaluate per-class precision, recall, mAP, confidence calibration and performance across lighting/device colors. Promote only after human evaluation. Generic YOLO object weights are not a PhoneProof defect model.
