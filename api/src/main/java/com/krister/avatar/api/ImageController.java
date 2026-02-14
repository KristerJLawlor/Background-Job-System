package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.krister.avatar.core.DiscordImageResizer; //Import the core module to use the image resizing functionality

@RestController //Marks this class as a REST controller to handle HTTP requests
@RequestMapping("/api") //Base path for all endpoints in this controller
public class ImageController {

    @PostMapping("/resize") //Defines a POST endpoint at /api/resize to handle image resizing requests
    public ResponseEntity<byte[]> resizeImage(@RequestParam String url) {
        try {
            //Callback to the core module to download and resize the image
            BufferedImage resized = DiscordImageResizer.downloadAndResize(url);

            //Convert BufferedImage to byte array
            ByteArrayOutputStream byteOutputArray = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", byteOutputArray);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(byteOutputArray.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
