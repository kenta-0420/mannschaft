package com.mannschaft.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.net.URI;

/**
 * Cloudflare R2 / AWS SES クライアント設定。
 * R2 は S3 互換 API を使用するため S3 SDK でアクセスする。
 * endpoint が指定されている場合は R2 専用エンドポイント（または LocalStack）に接続する。
 */
@Configuration
public class R2Config {

    /**
     * R2 用 S3Client Bean。
     * R2 は region 不要だが SDK の制約上 "auto" を指定する。
     */
    @Bean
    public S3Client s3Client(
            @Value("${mannschaft.storage.access-key:}") String accessKeyId,
            @Value("${mannschaft.storage.secret-key:}") String secretAccessKey,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of("auto"));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);
        }
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return builder.build();
    }

    /**
     * R2 用 S3Presigner Bean。
     * Pre-signed URL 生成に使用する。
     */
    @Bean
    public S3Presigner s3Presigner(
            @Value("${mannschaft.storage.access-key:}") String accessKeyId,
            @Value("${mannschaft.storage.secret-key:}") String secretAccessKey,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of("auto"));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return builder.build();
    }

    /**
     * SES v2 クライアント Bean。メール送信に使用する。
     * R2 移行後もメール送信は AWS SES を継続利用するため、
     * SES 専用リージョン設定を別途行う。
     */
    @Bean
    public SesV2Client sesV2Client(
            @Value("${mannschaft.ses.region:ap-northeast-1}") String sesRegion,
            @Value("${mannschaft.ses.endpoint:}") String sesEndpoint) {
        var builder = SesV2Client.builder().region(Region.of(sesRegion));
        if (!sesEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sesEndpoint));
        }
        return builder.build();
    }
}
