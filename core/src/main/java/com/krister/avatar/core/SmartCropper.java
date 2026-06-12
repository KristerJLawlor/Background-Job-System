package com.krister.avatar.core;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromCaffe;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

public class SmartCropper {

    private SmartCropper() {}

    private static final Logger log = LoggerFactory.getLogger(SmartCropper.class);
    // Low threshold accommodates partially-angled or distant faces; the model is still
    // localising correctly at confidences well below the 0.5 typical for frontal shots.
    private static final float CONFIDENCE_THRESHOLD = 0.1f;
    // Mean BGR values used when the SSD ResNet face detector was trained
    private static final Scalar MEAN = new Scalar(104.0, 177.0, 123.0, 0.0);

    // Net is not thread-safe — each worker thread gets its own instance.
    private static final ThreadLocal<Net> DETECTOR =
            ThreadLocal.withInitial(SmartCropper::createNet);

    private static Net createNet() {
        try {
            File prototxt = extractResource("/dnn/deploy.prototxt", "deploy", ".prototxt");
            File model = extractResource("/dnn/res10_300x300_ssd_iter_140000.caffemodel", "res10", ".caffemodel");
            Net net = readNetFromCaffe(prototxt.getAbsolutePath(), model.getAbsolutePath());
            if (net.empty()) throw new IllegalStateException("Failed to load DNN face detector model");
            return net;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static File extractResource(String resource, String prefix, String suffix) throws IOException {
        try (InputStream is = SmartCropper.class.getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("Resource not found on classpath: " + resource);
            File tmp = File.createTempFile(prefix, suffix);
            tmp.deleteOnExit();
            Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /**
     * Crops the image around the largest detected face with padding.
     * Falls back to a center crop if no face is found.
     */
    public static BufferedImage smartCrop(BufferedImage img) {
        Rectangle rect = detectCropRect(img);
        return img.getSubimage(rect.x, rect.y, rect.width, rect.height);
    }

    /**
     * Returns the crop rectangle without applying it. Used by animated GIF processing
     * to determine a single crop region from the first frame and apply it to all frames.
     */
    public static Rectangle detectCropRect(BufferedImage img) {
        try (Java2DFrameConverter j2d = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat toMat = new OpenCVFrameConverter.ToMat()) {

            Frame frame = j2d.convert(img);
            Mat mat = toMat.convert(frame);
            if (mat == null || mat.empty()) return centerCropRect(img);

            Mat bgr = new Mat();
            if (mat.channels() == 4) {
                cvtColor(mat, bgr, COLOR_BGRA2BGR);
            } else {
                bgr = mat;
            }

            Mat blob = blobFromImage(bgr, 1.0, new Size(300, 300), MEAN, false, false, CV_32F);
            Net net = DETECTOR.get();
            net.setInput(blob);
            // Output shape: [1, 1, N, 7] — each row is [_, _, confidence, x1, y1, x2, y2] (normalized 0–1)
            Mat detections = net.forward("detection_out");
            Mat flat = detections.reshape(1, (int) (detections.total() / 7));

            Rectangle best = findBestDetection(flat, img.getWidth(), img.getHeight());
            if (best == null) {
                log.debug("No face detected above threshold={} — falling back to center crop", CONFIDENCE_THRESHOLD);
            }
            return best != null ? faceCropRect(img, best) : centerCropRect(img);
        }
    }

    private static Rectangle findBestDetection(Mat flat, int imgW, int imgH) {
        FloatBuffer buf = flat.getFloatBuffer();
        int n = flat.rows();
        Rectangle best = null;
        int bestArea = 0;

        for (int i = 0; i < n; i++) {
            float confidence = buf.get(i * 7 + 2);
            if (confidence < CONFIDENCE_THRESHOLD) continue;

            int x1 = Math.max(0, (int) (buf.get(i * 7 + 3) * imgW));
            int y1 = Math.max(0, (int) (buf.get(i * 7 + 4) * imgH));
            int x2 = Math.min(imgW, (int) (buf.get(i * 7 + 5) * imgW));
            int y2 = Math.min(imgH, (int) (buf.get(i * 7 + 6) * imgH));

            int area = (x2 - x1) * (y2 - y1);
            if (area > bestArea) {
                bestArea = area;
                best = new Rectangle(x1, y1, x2 - x1, y2 - y1);
            }
        }

        log.debug("DNN face detection: candidates={} detected={}", n, best != null);
        return best;
    }

    private static Rectangle centerCropRect(BufferedImage img) {
        int size = Math.min(img.getWidth(), img.getHeight());
        int x = (img.getWidth() - size) / 2;
        int y = (img.getHeight() - size) / 2;
        return new Rectangle(x, y, size, size);
    }

    private static Rectangle faceCropRect(BufferedImage img, Rectangle face) {
        // Pad generously around the face so the avatar includes head and shoulders
        int padding = (int) (face.width * 0.6);
        // Clamp crop size to the smallest image dimension
        int size = Math.min(face.width + padding * 2, Math.min(img.getWidth(), img.getHeight()));

        // Center horizontally on the face; shift crop up slightly to include the top of the head
        int cx = face.x + face.width / 2;
        int cy = face.y + face.height / 2 - (int) (face.height * 0.1);

        int x = Math.max(0, Math.min(cx - size / 2, img.getWidth() - size));
        int y = Math.max(0, Math.min(cy - size / 2, img.getHeight() - size));

        return new Rectangle(x, y, size, size);
    }
}
