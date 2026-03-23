package com.mannschaft.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.net.URI;

/**
 * AWS クライアント設定（S3 + SES）。endpoint が指定されている場合は LocalStack 向けに path-style を有効化する。
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(
            @Value("${mannschaft.storage.region}") String region,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(
            @Value("${mannschaft.storage.region}") String region,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public SesV2Client sesV2Client(
            @Value("${mannschaft.storage.region}") String region,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        var builder = SesV2Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
