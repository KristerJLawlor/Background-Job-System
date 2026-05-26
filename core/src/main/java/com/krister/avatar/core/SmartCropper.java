package com.krister.avatar.core;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

public class SmartCropper {

    private SmartCropper() {}

    // CascadeClassifier is not thread-safe — each worker thread gets its own instance.
    private static final ThreadLocal<CascadeClassifier> DETECTOR =
            ThreadLocal.withInitial(SmartCropper::createClassifier);

    private static CascadeClassifier createClassifier() {
        try (InputStream is = SmartCropper.class.getResourceAsStream(
                "/cascades/haarcascade_frontalface_default.xml")) {
            if (is == null) throw new IllegalStateException("Haar cascade not found on classpath");
            File tmp = File.createTempFile("haarcascade", ".xml");
            tmp.deleteOnExit();
            Files.copy(is, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            CascadeClassifier classifier = new CascadeClassifier(tmp.getAbsolutePath());
            if (classifier.empty()) throw new IllegalStateException("Failed to load Haar cascade");
            return classifier;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Crops the image around the largest detected face with padding.
     * Falls back to a center crop if no face is found.
     */
    public static BufferedImage smartCrop(BufferedImage img) {
        try (Java2DFrameConverter j2d = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat toMat = new OpenCVFrameConverter.ToMat()) {

            Frame frame = j2d.convert(img);
            Mat mat = toMat.convert(frame);
            if (mat == null || mat.empty()) return DiscordImageResizer.centerCrop(img);

            Mat gray = new Mat();
            // BufferedImage can be 3-channel (BGR) or 4-channel (BGRA) after conversion
            int colorCode = (mat.channels() == 4) ? COLOR_BGRA2GRAY : COLOR_BGR2GRAY;
            cvtColor(mat, gray, colorCode);
            // Equalise histogram to improve detection under uneven lighting
            equalizeHist(gray, gray);

            RectVector faces = new RectVector();
            int minDim = Math.max(20, Math.min(img.getWidth(), img.getHeight()) / 8);
            DETECTOR.get().detectMultiScale(gray, faces, 1.1, 3, 0,
                    new Size(minDim, minDim), new Size());

            if (faces.empty()) return DiscordImageResizer.centerCrop(img);

            return cropAroundFace(img, largestFace(faces));
        }
    }

    private static Rect largestFace(RectVector faces) {
        Rect best = faces.get(0);
        for (long i = 1; i < faces.size(); i++) {
            Rect r = faces.get(i);
            if ((long) r.width() * r.height() > (long) best.width() * best.height()) best = r;
        }
        return best;
    }

    private static BufferedImage cropAroundFace(BufferedImage img, Rect face) {
        // Pad generously around the face so the avatar includes head and shoulders
        int padding = (int) (face.width() * 0.6);
        // Clamp crop size to the smallest image dimension
        int size = Math.min(face.width() + padding * 2, Math.min(img.getWidth(), img.getHeight()));

        // Center horizontally on the face; shift crop up slightly to include the top of the head
        int cx = face.x() + face.width() / 2;
        int cy = face.y() + face.height() / 2 - (int) (face.height() * 0.1);

        int x = Math.max(0, Math.min(cx - size / 2, img.getWidth() - size));
        int y = Math.max(0, Math.min(cy - size / 2, img.getHeight() - size));

        return img.getSubimage(x, y, size, size);
    }
}
