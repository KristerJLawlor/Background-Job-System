package com.krister.avatar.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.imageio.ImageIO;

public class DiscordImageResizer {

    private DiscordImageResizer() {}

    // Target width and height for Discord avatars are 128×128 pixels.
    private static final int DISCORD_IMAGE_DIMENSION = 128;

    public static BufferedImage downloadAndResize(String imageUrl) throws IOException {
        BufferedImage originalImage = downloadImage(imageUrl);
        return resizeImage(originalImage, DISCORD_IMAGE_DIMENSION, DISCORD_IMAGE_DIMENSION);
    }

    // Downloads raw bytes without decoding them into a BufferedImage. Keeping bytes raw
    // lets the worker inspect the magic header to detect animated GIFs before committing
    // to a processing path (animated vs static).
    public static byte[] downloadRaw(String imageUrl) throws IOException {
        try {
            URI uri = new URI(imageUrl.trim());
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            // Some CDNs reject requests without a User-Agent header.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid image URL: " + imageUrl, e);
        }
    }

    public static BufferedImage downloadImage(String imageUrl) throws IOException {
        byte[] raw = downloadRaw(imageUrl);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
        if (image == null) {
            throw new IOException("URL did not return an image");
        }
        return image;
    }

    // Smart-crops then resizes. The crop happens first so the resize only works on the
    // region of interest — if we resized first, the face would be too small for the
    // DNN to detect reliably.
    public static BufferedImage resizeImage(
            BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage croppedImage = SmartCropper.smartCrop(originalImage);
        return resizeOnly(croppedImage, targetWidth, targetHeight);
    }

    // Resizes an already-cropped image without applying smart crop again.
    // Used by animated GIF processing to resize individual frames after a shared crop.
    public static BufferedImage resizeOnly(BufferedImage img, int targetWidth, int targetHeight) {
        int w = img.getWidth();
        int h = img.getHeight();

        // Step-down scaling: halve dimensions repeatedly until we're close to the target.
        // Scaling directly from 1000px → 128px in one step loses significant detail.
        // Halving each step (bilinear) keeps more information and the final bicubic pass
        // sharpens the result. This is a classic "mipmap-style" quality trick.
        while (w / 2 >= targetWidth && h / 2 >= targetHeight) {
            w /= 2;
            h /= 2;
            BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tmp.createGraphics();
            // Bilinear interpolation: fast, acceptable quality for intermediate steps.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            img = tmp;
        }

        // Final step: bicubic interpolation produces smoother results for the last
        // resize, at the cost of being slower than bilinear.
        BufferedImage finalImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = finalImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(img, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();
        return finalImage;
    }

    public static BufferedImage centerCrop(BufferedImage img) {
        // Take the largest square that fits within the image, centered.
        int size = Math.min(img.getWidth(), img.getHeight());
        int x = (img.getWidth() - size) / 2;
        int y = (img.getHeight() - size) / 2;
        return img.getSubimage(x, y, size, size);
    }

    // Helper to save a BufferedImage to disk as PNG — useful for local testing and validation.
    public static void saveImage(BufferedImage img, File outputFile) throws IOException {
        ImageIO.write(img, "png", outputFile);
    }

}