package com.krister.avatar.core;

import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.ImageReader;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Handles animated GIF processing: reads all frames, crops them consistently,
// resizes each to 128×128, and re-encodes them as an animated GIF.
//
// GIF internals crash course:
// - An animated GIF is a sequence of frames, each with a delay time (in centiseconds).
// - Frames can be partial patches over a "canvas", not necessarily full images.
// - Each frame has a "disposal method" that tells the renderer what to do with the canvas
//   before drawing the next frame (keep it, clear it, etc.).
// - The Netscape Application Extension is a non-standard block that most browsers
//   recognize as an instruction to loop the animation (loop count 0 = infinite).
public class AnimatedGifProcessor {

    private AnimatedGifProcessor() {}

    private static final int TARGET_SIZE = 128;

    // GIF files start with a 6-byte magic header: "GIF87a" or "GIF89a".
    // Checking the header is faster than decoding the entire file just to count frames.
    public static boolean isAnimatedGif(byte[] data) throws IOException {
        if (data.length < 6) return false;
        String magic = new String(data, 0, 6);
        if (!magic.equals("GIF87a") && !magic.equals("GIF89a")) return false;

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false);
                return reader.getNumImages(true) > 1;
            } finally {
                reader.dispose();
            }
        }
    }

    public static byte[] process(byte[] inputData) throws IOException {
        List<GifFrame> frames = readCompositedFrames(inputData);
        if (frames.isEmpty()) throw new IOException("GIF contains no readable frames");

        // Detect crop region from the first composited frame — same rect applied to all frames
        // so the subject doesn't shift between frames.
        Rectangle cropRect = SmartCropper.detectCropRect(frames.get(0).image());

        List<GifFrame> processed = new ArrayList<>(frames.size());
        for (GifFrame frame : frames) {
            BufferedImage cropped = frame.image().getSubimage(
                    cropRect.x, cropRect.y, cropRect.width, cropRect.height);
            BufferedImage resized = DiscordImageResizer.resizeOnly(cropped, TARGET_SIZE, TARGET_SIZE);
            processed.add(new GifFrame(resized, frame.delayCs()));
        }

        return writeAnimatedGif(processed);
    }

    // Reads each raw GIF frame and composites it onto a shared canvas, producing the
    // "full" image a viewer would see at each point in the animation. Without this step,
    // raw frames can be partial updates (e.g. only the region that changed), which would
    // crop incorrectly if treated as standalone images.
    private static List<GifFrame> readCompositedFrames(byte[] data) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis, false);
            int numFrames = reader.getNumImages(true);

            // Canvas starts fully transparent; each frame is composited onto it.
            int canvasW = reader.getWidth(0);
            int canvasH = reader.getHeight(0);
            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            clearRect(canvas, 0, 0, canvasW, canvasH);

            List<GifFrame> frames = new ArrayList<>(numFrames);

            for (int i = 0; i < numFrames; i++) {
                // IIOMetadata carries per-frame GIF metadata as an XML-like tree.
                // We need: delay time (how long to show this frame) and disposal method.
                IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(i)
                        .getAsTree("javax_imageio_gif_image_1.0");

                int frameX = 0, frameY = 0, delayCs = 10;
                String disposal = "doNotDispose";

                IIOMetadataNode gce = findNode(root, "GraphicControlExtension");
                if (gce != null) {
                    String d = gce.getAttribute("delayTime");
                    if (d != null && !d.isEmpty()) delayCs = Integer.parseInt(d);
                    String dm = gce.getAttribute("disposalMethod");
                    if (dm != null && !dm.isEmpty()) disposal = dm;
                }

                // Frames can be positioned at an offset within the canvas (partial updates).
                IIOMetadataNode desc = findNode(root, "ImageDescriptor");
                if (desc != null) {
                    frameX = parseAttr(desc, "imageLeftPosition");
                    frameY = parseAttr(desc, "imageTopPosition");
                }

                BufferedImage rawFrame = reader.read(i);

                // Composite this frame onto the canvas at its declared position.
                Graphics2D gc = canvas.createGraphics();
                gc.drawImage(rawFrame, frameX, frameY, null);
                gc.dispose();

                // Snapshot the composited state — this is what the output frame should look like.
                frames.add(new GifFrame(deepCopy(canvas), Math.max(delayCs, 2)));

                // Apply the disposal method to prepare the canvas for the next frame.
                // "restoreToBackgroundColor" clears the region this frame occupied.
                // "doNotDispose" (most common) leaves the canvas as-is.
                // "restoreToPrevious" is rare and complex; treating it as doNotDispose is a
                // safe approximation that avoids ghosting in the common case.
                if ("restoreToBackgroundColor".equals(disposal)) {
                    clearRect(canvas, frameX, frameY, rawFrame.getWidth(), rawFrame.getHeight());
                }
            }

            reader.dispose();
            return frames;
        }
    }

    private static byte[] writeAnimatedGif(List<GifFrame> frames) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            writer.setOutput(ios);

            // prepareWriteSequence/writeToSequence/endWriteSequence is the ImageIO API for
            // writing a multi-frame (animated) file rather than a single static image.
            // GIFWritableStreamMetadata.mergeNativeTree only accepts Version,
            // LogicalScreenDescriptor, and GlobalColorTable — ApplicationExtensions
            // is not supported there. The Netscape loop extension must go in the
            // first frame's image metadata instead.
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                GifFrame frame = frames.get(i);
                IIOMetadata frameMeta = buildFrameMetadata(writer, frame.delayCs(), i == 0);
                writer.writeToSequence(
                        new IIOImage(frame.image(), null, frameMeta),
                        writer.getDefaultWriteParam());
            }

            writer.endWriteSequence();
            writer.dispose();
        }
        return baos.toByteArray();
    }

    // Builds per-frame metadata including delay time and (for the first frame) the
    // Netscape Application Extension that tells browsers to loop the GIF infinitely.
    // GIFWritableImageMetadata.mergeNativeTree supports ApplicationExtensions, so the
    // Netscape infinite-loop extension is embedded in the first frame's metadata.
    private static IIOMetadata buildFrameMetadata(ImageWriter writer, int delayCs,
                                                  boolean firstFrame) throws IOException {
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
        IIOMetadata meta = writer.getDefaultImageMetadata(type, writer.getDefaultWriteParam());

        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = findOrCreateNode(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "doNotDispose");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        // delayTime is in centiseconds (1/100 of a second): e.g. 10 = 100ms per frame.
        gce.setAttribute("delayTime", String.valueOf(delayCs));
        gce.setAttribute("transparentColorIndex", "0");

        if (firstFrame) {
            // The Netscape 2.0 extension is a de-facto standard (not part of the GIF spec)
            // that all major browsers honour to loop animated GIFs.
            IIOMetadataNode appExtensions = findOrCreateNode(root, "ApplicationExtensions");
            IIOMetadataNode appExt = new IIOMetadataNode("ApplicationExtension");
            appExt.setAttribute("applicationID", "NETSCAPE");
            appExt.setAttribute("authenticationCode", "2.0");
            // Sub-block: ID byte (0x01) + 2-byte little-endian loop count (0x00 0x00 = infinite)
            appExt.setUserObject(new byte[]{0x1, 0x0, 0x0});
            appExtensions.appendChild(appExt);
        }

        meta.setFromTree(format, root);
        return meta;
    }

    // AlphaComposite.CLEAR fills the region with fully transparent pixels, effectively
    // erasing it. This is used to implement the "restoreToBackgroundColor" disposal method.
    private static void clearRect(BufferedImage img, int x, int y, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR));
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    // Returns an independent copy of the image. Without this, all "snapshots" in the frames
    // list would share the same underlying pixel array — mutating the canvas for the next
    // frame would retroactively change every previous frame we already stored.
    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static IIOMetadataNode findNode(Node root, String name) {
        if (root.getNodeName().equals(name)) return (IIOMetadataNode) root;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            IIOMetadataNode found = findNode(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static IIOMetadataNode findOrCreateNode(IIOMetadataNode root, String name) {
        IIOMetadataNode node = findNode(root, name);
        if (node == null) {
            node = new IIOMetadataNode(name);
            root.appendChild(node);
        }
        return node;
    }

    private static int parseAttr(IIOMetadataNode node, String attr) {
        String val = node.getAttribute(attr);
        if (val == null || val.isEmpty()) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private record GifFrame(BufferedImage image, int delayCs) {}
}
