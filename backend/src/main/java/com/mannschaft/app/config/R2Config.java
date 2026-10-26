package com.mannschaft.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.net.URI;
import java.time.Duration;

/**
 * Cloudflare R2 / AWS SES クライアント設定。
 * R2 は S3 互換 API を使用するため S3 SDK でアクセスする。
 * endpoint が指定されている場合は R2 専用エンドポイント（または LocalStack）に接続する。
 */
@Configuration
public class R2Config {

    /**
     * AWS SDK クライアント共通のオーバーライド設定。
     * リトライポリシー（STANDARD モード: 最大3回）と、1回あたり30秒・全体90秒の
     * タイムアウトを設定し、AWS/Cloudflare 側の無応答による無限ハングアップを防ぐ。
     */
    private ClientOverrideConfiguration awsClientOverrideConfig() {
        return ClientOverrideConfiguration.builder()
                .retryPolicy(RetryPolicy.forRetryMode(RetryMode.STANDARD))
                .apiCallAttemptTimeout(Duration.ofSeconds(30))
                .apiCallTimeout(Duration.ofSeconds(90))
                .build();
    }

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
                .region(Region.of("auto"))
                .overrideConfiguration(awsClientOverrideConfig());
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
     *
     * <p>path-style（forcePathStyle）を有効にする理由:
     * presigner がデフォルトの仮想ホスト形式で動作すると、署名 URL のホストが
     * {@code <bucket>.<endpoint>}（例: {@code mannschaft-storage.localhost:9000}）になり、
     * ブラウザが名前解決できないためアップロードが不能になる。
     * S3Client 側が既に {@code forcePathStyle(true)} であるため、presigner も揃えて
     * {@code http://<endpoint>/<bucket>/<key>} 形式の URL を生成する。
     * 本番 Cloudflare R2 は path-style に対応しているため互換性に問題はない。</p>
     */
    @Bean
    public S3Presigner s3Presigner(
            @Value("${mannschaft.storage.access-key:}") String accessKeyId,
            @Value("${mannschaft.storage.secret-key:}") String secretAccessKey,
            @Value("${mannschaft.storage.endpoint:}") String endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of("auto"));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
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
        var builder = SesV2Client.builder()
                .region(Region.of(sesRegion))
                .overrideConfiguration(awsClientOverrideConfig());
        if (!sesEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sesEndpoint));
        }
        return builder.build();
    }
}
