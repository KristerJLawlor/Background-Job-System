package com.krister.avatar.shared;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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

    // @PostConstruct runs once after Spring has finished constructing and injecting this bean.
    // It's the right place for one-time initialization that needs injected values (like bucketName)
    // which aren't available in the constructor yet. Using headBucket to check existence and only
    // calling createBucket on NoSuchBucketException makes this idempotent — safe to call on every
    // startup whether or not the bucket already exists.
    @PostConstruct
    void initBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("Created S3 bucket bucket={}", bucketName);
        }

        // S3 lifecycle rules are server-side policies that automatically delete objects
        // matching a key prefix after N days. This is the primary cost-control mechanism:
        // even if nobody claims a result, S3 will clean it up without any code running.
        // Two rules cover the two key prefixes used in this service:
        //   results/{jobId} — processed output waiting to be downloaded
        //   uploads/{jobId} — raw bytes uploaded by the user, waiting for the worker
        // Wrapped in try-catch because setting lifecycle rules requires Admin Read & Write
        // permission on the R2 token. The app can still function without it — the daily
        // quota counter is the primary cost guard. Log a clear warning so the operator
        // knows to either grant the permission or configure the rules manually in the dashboard.
        try {
            s3Client.putBucketLifecycleConfiguration(
                    PutBucketLifecycleConfigurationRequest.builder()
                            .bucket(bucketName)
                            .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                                    .rules(
                                            LifecycleRule.builder()
                                                    .id("expire-unclaimed-results")
                                                    .status(ExpirationStatus.ENABLED)
                                                    .filter(LifecycleRuleFilter.builder().prefix("results/").build())
                                                    .expiration(LifecycleExpiration.builder().days(resultExpiryDays).build())
                                                    .build(),
                                            LifecycleRule.builder()
                                                    .id("expire-stale-uploads")
                                                    .status(ExpirationStatus.ENABLED)
                                                    .filter(LifecycleRuleFilter.builder().prefix("uploads/").build())
                                                    .expiration(LifecycleExpiration.builder().days(1).build())
                                                    .build())
                                    .build())
                            .build());
            log.info("S3 lifecycle configured bucket={} expiryDays={}", bucketName, resultExpiryDays);
        } catch (Exception e) {
            log.warn("Could not configure S3 lifecycle rules (token may lack Admin Read & Write permission) — " +
                    "configure expiry rules manually in the R2 dashboard to protect storage budget. Error: {}", e.getMessage());
        }
    }

    public void storeResult(String jobId, ProcessingResult result) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key("results/" + jobId)
                        .contentType(result.contentType())
                        .build(),
                RequestBody.fromBytes(result.data()));
    }

    public void storeUpload(String jobId, byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key("uploads/" + jobId)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
    }

    public byte[] downloadUpload(String jobId) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucketName).key("uploads/" + jobId).build()
        ).asByteArray();
    }

    public void deleteUpload(String jobId) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucketName).key("uploads/" + jobId).build());
    }

    // Results are one-shot: download and delete in the same call. This is a read-then-delete
    // pattern — not atomically guaranteed by S3 (two simultaneous callers could both read
    // before either deletes), but acceptable here because the job status check in the controller
    // provides a first gate, and double-download of the same image is a harmless edge case.
    // Returns null if the result has already been claimed or doesn't exist.
    public ProcessingResult claimResult(String jobId) {
        String key = "results/" + jobId;
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build());
            String contentType = response.response().contentType();
            byte[] bytes = response.asByteArray();
            s3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
            return new ProcessingResult(bytes, contentType != null ? contentType : "image/png");
        } catch (NoSuchKeyException e) {
            return null;
        }
    }
}
