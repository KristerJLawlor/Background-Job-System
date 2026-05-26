package com.krister.avatar.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3ResultStore {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    public S3ResultStore(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void storeResult(String jobId, byte[] pngBytes) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key("results/" + jobId + ".png")
                        .contentType("image/png")
                        .build(),
                RequestBody.fromBytes(pngBytes));
    }

    // Returns null if the result has already been claimed or doesn't exist.
    public byte[] claimResult(String jobId) {
        String key = "results/" + jobId + ".png";
        try {
            byte[] bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build()
            ).asByteArray();
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
            return bytes;
        } catch (NoSuchKeyException e) {
            return null;
        }
    }
}
