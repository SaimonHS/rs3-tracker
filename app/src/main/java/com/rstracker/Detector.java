package com.rstracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** On-device person / face finder (Google MediaPipe). Use from one thread only. */
final class Detector {
    final boolean face;
    private ObjectDetector people;
    private FaceDetector faces;
    private long lastTs;

    Detector(Context ctx, boolean face) {
        this.face = face;
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(face ? "face.tflite" : "person.tflite")
                .setDelegate(Delegate.CPU)
                .build();
        if (face) {
            faces = FaceDetector.createFromOptions(ctx, FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setMinDetectionConfidence(0.5f)
                    .build());
        } else {
            people = ObjectDetector.createFromOptions(ctx, ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.VIDEO)
                    .setScoreThreshold(0.35f)
                    .setMaxResults(6)
                    .setCategoryAllowlist(Collections.singletonList("person"))
                    .build());
        }
    }

    /** Boxes are 0..1 of the bitmap. */
    List<Follow.Box> detect(Bitmap bmp, long now) {
        long ts = Math.max(now, lastTs + 1);
        lastTs = ts;
        MPImage img = new BitmapImageBuilder(bmp).build();
        List<Detection> found = face
                ? faces.detectForVideo(img, ts).detections()
                : people.detectForVideo(img, ts).detections();
        float w = bmp.getWidth(), h = bmp.getHeight();
        List<Follow.Box> out = new ArrayList<>();
        for (Detection d : found) {
            RectF r = d.boundingBox();
            float score = d.categories().isEmpty() ? 0f : d.categories().get(0).score();
            out.add(new Follow.Box(r.left / w, r.top / h, r.width() / w, r.height() / h, score));
        }
        return out;
    }

    void close() {
        try {
            if (people != null) people.close();
            if (faces != null) faces.close();
        } catch (Exception ignored) {
        }
    }
}
