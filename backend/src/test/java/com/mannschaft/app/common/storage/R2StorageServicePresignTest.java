package com.mannschaft.app.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link R2StorageService#generateUploadUrl} の presign 挙動検証。
 *
 * <p>実 {@link S3Presigner} を MinIO 互換エンドポイント設定で構築し、
 * 以下の2点を確認する:
 * <ol>
 *   <li>path-style URL（バケットがサブドメインでなくパスに現れる）が生成される</li>
 *   <li>署名ヘッダー（X-Amz-SignedHeaders）に {@code cache-control} が含まれない</li>
 * </ol>
 *
 * <p>実ネットワーク疎通は不要（presign は署名計算のみでリクエストを送らない）。
 * Testcontainers 不使用の純ユニットテスト。
 */
@DisplayName("R2StorageService presign 挙動テスト")
class R2StorageServicePresignTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_KEY = "avatars/user-123/avatar.jpg";
    private static final String TEST_CONTENT_TYPE = "image/jpeg";
    private static final Duration TEST_TTL = Duration.ofMinutes(15);

    private R2StorageService r2StorageService;

    @BeforeEach
    void setUp() {
        // ダミー資格情報（実際の通信は行わないため任意の値でよい）
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("minioadmin", "minioadmin"));

        // S3Presigner を path-style で構築（R2Config.s3Presigner Bean と同等の設定）
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(ENDPOINT))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .credentialsProvider(credentials)
                .build();

        // S3Client も path-style（generateUploadUrl テストでは使わないが注入が必要）
        S3Client s3Client = S3Client.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(ENDPOINT))
                .forcePathStyle(true)
                .credentialsProvider(credentials)
                .build();

        StorageProperties props = new StorageProperties(TEST_BUCKET, 900, 3600);

        r2StorageService = new R2StorageService(s3Client, presigner, props);
    }

    @Test
    @DisplayName("generateUploadUrl — path-style URL（バケット名がパスに含まれる）が生成される")
    void generateUploadUrl_pathStyleUrl_バケットがパスに現れる() {
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                TEST_KEY, TEST_CONTENT_TYPE, TEST_TTL);

        // path-style: http://localhost:9000/<bucket>/<key>
        // 仮想ホスト形式: http://<bucket>.localhost:9000/<key>  ← これはNG
        assertThat(result.uploadUrl())
                .as("path-style URL の確認: バケット名がホストでなくパスに現れること")
                .contains(ENDPOINT + "/" + TEST_BUCKET + "/");
    }

    @Test
    @DisplayName("generateUploadUrl — 署名ヘッダーに cache-control が含まれない")
    void generateUploadUrl_signedHeaders_cacheControlを含まない() {
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                TEST_KEY, TEST_CONTENT_TYPE, TEST_TTL);

        // X-Amz-SignedHeaders クエリパラメータを取り出して検証
        // 例: https://...?X-Amz-SignedHeaders=content-type%3Bhost
        String url = result.uploadUrl();
        int signedHeadersIdx = url.indexOf("X-Amz-SignedHeaders=");
        assertThat(signedHeadersIdx)
                .as("署名 URL に X-Amz-SignedHeaders パラメータが存在すること")
                .isGreaterThan(0);

        // X-Amz-SignedHeaders の値部分（次の & または文字列末尾まで）を抽出
        int valueStart = signedHeadersIdx + "X-Amz-SignedHeaders=".length();
        int valueEnd = url.indexOf('&', valueStart);
        String signedHeaders = valueEnd < 0
                ? url.substring(valueStart)
                : url.substring(valueStart, valueEnd);

        // URL デコード（%3B → ;）
        String decoded = java.net.URLDecoder.decode(signedHeaders, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(decoded)
                .as("アップロード用 presign の署名ヘッダーに cache-control が含まれないこと: " + decoded)
                .doesNotContain("cache-control");
    }
}
