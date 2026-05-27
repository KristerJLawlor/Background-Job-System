package com.krister.avatar.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SmartCropperTest {

    static boolean openCvAvailable;

    @BeforeAll
    static void checkNatives() {
        try {
            SmartCropper.smartCrop(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
            openCvAvailable = true;
        } catch (Throwable ignored) {
            openCvAvailable = false;
        }
    }

    // --- centerCrop (pure Java, always run) ---

    @Test
    void centerCrop_widerImage_squaresToShorterDimension() {
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = DiscordImageResizer.centerCrop(img);
        assertThat(result.getWidth()).isEqualTo(200);
        assertThat(result.getHeight()).isEqualTo(200);
    }

    @Test
    void centerCrop_tallerImage_squaresToShorterDimension() {
        BufferedImage img = new BufferedImage(100, 400, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = DiscordImageResizer.centerCrop(img);
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(100);
    }

    @Test
    void centerCrop_squareImage_returnsSameSize() {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = DiscordImageResizer.centerCrop(img);
        assertThat(result.getWidth()).isEqualTo(256);
        assertThat(result.getHeight()).isEqualTo(256);
    }

    // --- smartCrop (requires OpenCV natives, skipped if unavailable) ---

    @Test
    void smartCrop_blankWideImage_fallsBackToCenterCrop() {
        assumeTrue(openCvAvailable, "OpenCV native libraries unavailable in this environment");
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = SmartCropper.smartCrop(img);
        assertThat(result.getWidth()).isEqualTo(result.getHeight());
        assertThat(result.getWidth()).isEqualTo(200);
    }

    @Test
    void smartCrop_blankTallImage_fallsBackToCenterCrop() {
        assumeTrue(openCvAvailable, "OpenCV native libraries unavailable in this environment");
        BufferedImage img = new BufferedImage(150, 300, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = SmartCropper.smartCrop(img);
        assertThat(result.getWidth()).isEqualTo(result.getHeight());
        assertThat(result.getWidth()).isEqualTo(150);
    }

    @Test
    void smartCrop_blankSquareImage_returnsSquare() {
        assumeTrue(openCvAvailable, "OpenCV native libraries unavailable in this environment");
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = SmartCropper.smartCrop(img);
        assertThat(result.getWidth()).isEqualTo(result.getHeight());
    }

    @Test
    void resizeImage_outputIsTargetDimensions() {
        assumeTrue(openCvAvailable, "OpenCV native libraries unavailable in this environment");
        BufferedImage img = new BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = DiscordImageResizer.resizeImage(img, 128, 128);
        assertThat(result.getWidth()).isEqualTo(128);
        assertThat(result.getHeight()).isEqualTo(128);
    }
}
