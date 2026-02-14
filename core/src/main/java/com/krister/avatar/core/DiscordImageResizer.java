package com.krister.avatar.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.imageio.ImageIO;

public class DiscordImageResizer {

    
    //Private constructor to prevent instantiation
    private DiscordImageResizer() {}
    
    //Target width and height for Discord avatars are 128x128 pixels
    private static final int DISCORD_IMAGE_DIMENSION = 128;

    public static BufferedImage downloadAndResize(String imageUrl) throws IOException, URISyntaxException {
        //Call downloadImage and resizeImage methods to get the final processed image
        BufferedImage originalImage = downloadImage(imageUrl);
        return resizeImage(originalImage, DISCORD_IMAGE_DIMENSION, DISCORD_IMAGE_DIMENSION);
    }

    public static BufferedImage downloadImage(String imageUrl) throws IOException {

        try {
            URI uri = new URI(imageUrl.trim()); //Trim whitespace from URL
            URL url = uri.toURL();  //Convert URI to URL

            //Open a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();    //Make configurable HTTP connection object
            connection.setRequestMethod("GET"); 
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();    //send the request and get response code
            if (responseCode != 200) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            //Download the image from given URL without resizing
            //We use bufferedimage so we can manipulate the image before returning it
            BufferedImage image = ImageIO.read(connection.getInputStream());

            if (image == null) {
                throw new IllegalArgumentException("URL did not return an image");
            }

            return image;

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid image URL: " + imageUrl, e);
        }
    }


    public static BufferedImage resizeImage(
        BufferedImage originalImage, int targetWidth, int targetHeight) {

        //First center crop the image to make it square, since Discord avatars are square
        //Plus, this prevents distortion when resizing non-square images
        BufferedImage croppedImage = centerCrop(originalImage);

        //Perform a multi-step downscaling to maintain quality (reduce aliasing) as we reach our target size
        int w = croppedImage.getWidth();
        int h = croppedImage.getHeight();
        BufferedImage img = croppedImage;

        //Reduce size by half until we are at or just below target size
        while (w / 2 >= targetWidth && h / 2 >= targetHeight) {
            w /= 2;
            h /= 2;

            BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

            Graphics2D g = tmp.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();

            img = tmp;
    }

    //Final resize to target dimensions using high-quality bicubic interpolation
    BufferedImage finalImage = 
        new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB); //Use ARGB to support transparency

    Graphics2D g2d = finalImage.createGraphics();

    g2d.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, 
        RenderingHints.VALUE_INTERPOLATION_BICUBIC);

    g2d.setRenderingHint(
        RenderingHints.KEY_RENDERING, 
        RenderingHints.VALUE_RENDER_QUALITY);

    g2d.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING, 
        RenderingHints.VALUE_ANTIALIAS_ON);

    g2d.drawImage(img, 0, 0, targetWidth, targetHeight, null);
    g2d.dispose();

    return finalImage;
    }

    public static BufferedImage centerCrop(BufferedImage img) {
        //Determine the smallest x or y dimension. This will be the max size of the square crop's sides
        int size = Math.min(img.getWidth(), img.getHeight());

        int x = (img.getWidth() - size) / 2;
        int y = (img.getHeight() - size) / 2;

        return img.getSubimage(x, y, size, size);
    }

    //Helper method to save to disk (wont be used in the main code but useful for testing)
    public static void saveImage(BufferedImage img, File outputFile) throws IOException {
        ImageIO.write(img, "png", outputFile);
    }

}