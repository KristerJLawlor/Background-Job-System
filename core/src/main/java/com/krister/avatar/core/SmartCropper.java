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

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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

// Uses an OpenCV DNN (Deep Neural Network) face detector to find the most prominent face
// in an image and crop around it, producing a better avatar than a simple center crop.
//
// The model is "SSD ResNet-10" trained on faces:
//   SSD = Single Shot MultiBox Detector — detects objects in one forward pass (fast).
//   ResNet = Residual Network — a CNN architecture that uses skip connections to allow
//            very deep networks to train without vanishing gradients.
//   The model was trained in the Caffe framework; it lives in core/src/main/resources/dnn/.
public class SmartCropper {

    private SmartCropper() {}

    private static final Logger log = LoggerFactory.getLogger(SmartCropper.class);
    // Low threshold accommodates partially-angled or distant faces; the model is still
    // localising correctly at confidences well below the 0.5 typical for frontal shots.
    private static final float CONFIDENCE_THRESHOLD = 0.1f;
    // The model was trained with images pre-processed by subtracting these BGR mean values
    // from every pixel. We must apply the same normalization at inference time or the
    // model's predictions will be wrong (it was trained expecting zero-mean input).
    private static final Scalar MEAN = new Scalar(104.0, 177.0, 123.0, 0.0);

    // OpenCV's Net class is NOT thread-safe — two threads calling net.forward() simultaneously
    // would corrupt internal state. ThreadLocal gives each thread its own Net instance,
    // so the worker pool's multiple threads can all run inference concurrently.
    private static final ThreadLocal<Net> DETECTOR =
            ThreadLocal.withInitial(SmartCropper::createNet);

    private static Net createNet() {
        try {
            // The model files are bundled in the JAR as classpath resources. OpenCV's
            // readNetFromCaffe requires file paths, not streams, so we extract them
            // to temp files on disk. deleteOnExit() cleans them up when the JVM shuts down.
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

    // Crops the image around the largest detected face with padding.
    // Falls back to a center crop if no face is found.
    public static BufferedImage smartCrop(BufferedImage img) {
        Rectangle rect = detectCropRect(img);
        return img.getSubimage(rect.x, rect.y, rect.width, rect.height);
    }

    // Returns the crop rectangle without applying it. Used by animated GIF processing
    // to determine a single crop region from the first frame and apply it to all frames,
    // so the subject doesn't drift between frames after cropping.
    public static Rectangle detectCropRect(BufferedImage img) {
        // Pre-downsample large images before passing to OpenCV. The DNN resizes its input
        // to 300×300 internally anyway, so detection accuracy is unaffected. Running the
        // Mat conversion on a 600px image instead of a 3000px one significantly reduces
        // memory allocation and CPU time for the conversion and blobFromImage steps.
        // The DNN output coordinates are normalized (0–1), so we still pass the original
        // image dimensions to findBestDetection — the result is in original pixel space.
        BufferedImage forDetection = downsampleForDetection(img);

        try (Java2DFrameConverter j2d = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat toMat = new OpenCVFrameConverter.ToMat()) {

            // Convert Java's BufferedImage → JavaCV Frame → OpenCV Mat (the native image format).
            Frame frame = j2d.convert(forDetection);
            Mat mat = toMat.convert(frame);
            if (mat == null || mat.empty()) return centerCropRect(img);

            // The model expects 3-channel BGR input. BGRA (4-channel, has alpha) images
            // must be converted first.
            Mat bgr = new Mat();
            if (mat.channels() == 4) {
                cvtColor(mat, bgr, COLOR_BGRA2BGR);
            } else {
                bgr = mat;
            }

            // blobFromImage resizes the image to 300×300 and subtracts the mean — the
            // exact preprocessing the model expects. The output "blob" is a 4D tensor
            // in the shape [batch, channels, height, width].
            Mat blob = blobFromImage(bgr, 1.0, new Size(300, 300), MEAN, false, false, CV_32F);
            Net net = DETECTOR.get();
            net.setInput(blob);
            // Forward pass: runs inference through all DNN layers and returns detections.
            // Output shape: [1, 1, N, 7] — each of the N rows is:
            //   [image_id, class_id, confidence, x1, y1, x2, y2] (coordinates normalized 0–1)
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

            // Coordinates are normalized (0.0–1.0); multiply by image dimensions to get pixels.
            int x1 = Math.max(0, (int) (buf.get(i * 7 + 3) * imgW));
            int y1 = Math.max(0, (int) (buf.get(i * 7 + 4) * imgH));
            int x2 = Math.min(imgW, (int) (buf.get(i * 7 + 5) * imgW));
            int y2 = Math.min(imgH, (int) (buf.get(i * 7 + 6) * imgH));

            // Pick the largest face by area — if multiple faces are detected, the biggest
            // one is most likely the subject of the avatar.
            int area = (x2 - x1) * (y2 - y1);
            if (area > bestArea) {
                bestArea = area;
                best = new Rectangle(x1, y1, x2 - x1, y2 - y1);
            }
        }

        log.debug("DNN face detection: candidates={} detected={}", n, best != null);
        return best;
    }

    // Caps the image at 600px on the longest side before DNN processing.
    // 600px gives the DNN plenty of detail to detect faces — going larger just wastes CPU.
    private static final int MAX_DETECTION_DIM = 600;

    private static BufferedImage downsampleForDetection(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= MAX_DETECTION_DIM && h <= MAX_DETECTION_DIM) return img;

        float scale = (float) MAX_DETECTION_DIM / Math.max(w, h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);

        BufferedImage small = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newW, newH, null);
        g.dispose();
        return small;
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

        // Math.max/min clamping keeps the rectangle inside the image bounds.
        int x = Math.max(0, Math.min(cx - size / 2, img.getWidth() - size));
        int y = Math.max(0, Math.min(cy - size / 2, img.getHeight() - size));

        return new Rectangle(x, y, size, size);
    }
}
