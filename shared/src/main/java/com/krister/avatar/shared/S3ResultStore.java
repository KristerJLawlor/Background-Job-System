package com.krister.avatar.shared;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3ResultStore {

    private static final Logger log = LoggerFactory.getLogger(S3ResultStore.class);

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${job.result.s3-expiry-days:1}")
    private int resultExpiryDays;

    public S3ResultStore(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @PostConstruct
    void initBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("Created S3 bucket bucket={}", bucketName);
        }
        s3Client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest.builder()
                        .bucket(bucketName)
                        .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                                .rules(LifecycleRule.builder()
                                        .id("expire-unclaimed-results")
                                        .status(ExpirationStatus.ENABLED)
                                        .filter(LifecycleRuleFilter.builder()
                                                .prefix("results/")
                                                .build())
                                        .expiration(LifecycleExpiration.builder()
                                                .days(resultExpiryDays)
                                                .build())
                                        .build())
                                .build())
                        .build());
        log.info("S3 lifecycle configured bucket={} expiryDays={}", bucketName, resultExpiryDays);
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
