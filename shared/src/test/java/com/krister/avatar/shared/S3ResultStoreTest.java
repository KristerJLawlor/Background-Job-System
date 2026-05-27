package com.krister.avatar.shared;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3ResultStoreTest {

    @Mock S3Client s3Client;

    S3ResultStore store;

    @BeforeEach
    void setUp() {
        store = new S3ResultStore(s3Client);
        ReflectionTestUtils.setField(store, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(store, "resultExpiryDays", 1);
    }

    @Test
    void initBucket_bucketExists_appliesLifecyclePolicyWithoutCreating() {
        store.initBucket();

        verify(s3Client).headBucket(any(HeadBucketRequest.class));
        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
        verify(s3Client).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void initBucket_bucketMissing_createsBucketThenAppliesLifecyclePolicy() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.class);

        store.initBucket();

        verify(s3Client).createBucket(any(CreateBucketRequest.class));
        verify(s3Client).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void initBucket_lifecycleRule_targetsResultsPrefixWithConfiguredExpiry() {
        var captor = ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);

        store.initBucket();

        verify(s3Client).putBucketLifecycleConfiguration(captor.capture());
        var rule = captor.getValue().lifecycleConfiguration().rules().get(0);
        assertThat(rule.filter().prefix()).isEqualTo("results/");
        assertThat(rule.expiration().days()).isEqualTo(1);
    }
}
