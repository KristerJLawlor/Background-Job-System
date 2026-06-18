package com.krister.avatar.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

// @Configuration marks this class as a source of Spring beans. Spring reads it at startup
// and calls each @Bean method once, storing the result as a singleton that other classes
// can receive via constructor injection.
@Configuration
public class AwsConfig {

    // @Value injects a value from application.properties or an environment variable at startup.
    // The ":}" suffix means "use empty string as the default if the variable is not set".
    @Value("${aws.s3.region}")
    private String region;

    @Value("${aws.s3.endpoint-override:}")
    private String endpointOverride;

    @Value("${aws.access-key-id:test}")
    private String accessKeyId;

    @Value("${aws.secret-access-key:test}")
    private String secretAccessKey;

    // @Bean registers the returned S3Client as a singleton in Spring's application context.
    // Any class that declares an S3Client constructor parameter will receive this exact instance —
    // Spring wires it automatically with no manual plumbing needed (this is dependency injection).
    @Bean
    public S3Client s3Client() {
        // StaticCredentialsProvider hard-codes the key pair into the client.
        // For real AWS you'd prefer IAM instance roles (no long-lived keys), but
        // Cloudflare R2 (and LocalStack) only support access-key-style auth.
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));

        if (!endpointOverride.isBlank()) {
            // endpointOverride redirects all S3 API calls to a different host:
            // - locally → http://localstack:4566 (LocalStack running in Docker)
            // - production → https://<account-id>.r2.cloudflarestorage.com (Cloudflare R2)
            // pathStyleAccessEnabled is required by both: real AWS uses virtual-hosted style
            // (bucket.s3.amazonaws.com), but LocalStack and R2 expect path style (host/bucket).
            builder.endpointOverride(URI.create(endpointOverride))
                   .serviceConfiguration(S3Configuration.builder()
                           .pathStyleAccessEnabled(true)
                           .build());
        }

        return builder.build();
    }
}
