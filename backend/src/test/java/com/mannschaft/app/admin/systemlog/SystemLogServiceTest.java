package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.common.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SystemLogService} の単体テスト。
 * S3Client と S3Presigner をモックして R2 操作をシミュレートする。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SystemLogService 単体テスト")
class SystemLogServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private StorageProperties storageProperties;

    private SystemLogPiiMasker piiMasker;
    private SystemLogService systemLogService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        piiMasker = new SystemLogPiiMasker();
        given(storageProperties.getBucket()).willReturn("test-bucket");
    }

    private SystemLogService createService(String logPath) {
        return new SystemLogService(s3Client, s3Presigner, storageProperties, piiMasker, logPath);
    }

    // ==================== uploadSlowQueryLog ====================

    @Nested
    @DisplayName("uploadSlowQueryLog")
    class UploadSlowQueryLogTest {

        @Test
        @DisplayName("ログファイルが存在しない場合はスキップする")
        void skip_whenFileNotExists() {
            systemLogService = createService("/nonexistent/path/slow.log");
            systemLogService.uploadSlowQueryLog(LocalDate.of(2026, 5, 8));

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("対象日付のエントリが 0 件の場合はスキップする")
        void skip_whenNoEntryForDate() throws IOException {
            Path logFile = tempDir.resolve("slow.log");
            // 異なる日付のエントリのみ
            Files.writeString(logFile, """
                    # Time: 2026-05-07T01:00:00
                    # User@Host: mannschaft[]
                    SELECT 1;
                    """);
            systemLogService = createService(logFile.toString());
            systemLogService.uploadSlowQueryLog(LocalDate.of(2026, 5, 8));

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("対象日付のエントリを抽出して R2 にアップロードする")
        void upload_targetDateEntries() throws IOException {
            Path logFile = tempDir.resolve("slow.log");
            Files.writeString(logFile, """
                    # Time: 2026-05-07T01:00:00
                    # User@Host: mannschaft[]
                    SELECT 1;
                    # Time: 2026-05-08T01:00:00
                    # User@Host: mannschaft[]
                    SELECT 2;
                    """);
            systemLogService = createService(logFile.toString());
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            systemLogService.uploadSlowQueryLog(LocalDate.of(2026, 5, 8));

            ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

            PutObjectRequest putRequest = requestCaptor.getValue();
            assertThat(putRequest.key()).isEqualTo("logs/slow-query/2026-05-08.log");
            assertThat(putRequest.bucket()).isEqualTo("test-bucket");
        }

        @Test
        @DisplayName("PIIマスキングが適用される")
        void piiMasking_appliedOnUpload() throws IOException {
            Path logFile = tempDir.resolve("slow.log");
            Files.writeString(logFile, """
                    # Time: 2026-05-08T01:00:00
                    UPDATE users SET email='user@example.com' WHERE id=1;
                    """);
            systemLogService = createService(logFile.toString());
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            systemLogService.uploadSlowQueryLog(LocalDate.of(2026, 5, 8));

            ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            // アップロードされた内容にメールアドレスが含まれていないことを確認
            byte[] uploadedBytes = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
            String uploadedContent = new String(uploadedBytes, StandardCharsets.UTF_8);
            assertThat(uploadedContent).doesNotContain("user@example.com");
            assertThat(uploadedContent).contains("email='***'");
        }
    }

    // ==================== flushSsrErrors ====================

    @Nested
    @DisplayName("flushSsrErrors")
    class FlushSsrErrorsTest {

        @Test
        @DisplayName("バッファが空の場合はスキップする")
        void skip_whenBufferEmpty() {
            systemLogService = createService("./dummy.log");
            systemLogService.flushSsrErrors(LocalDate.of(2026, 5, 8));

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("既存ファイルがない場合は新規アップロードする")
        void upload_whenFileNotExists() {
            systemLogService = createService("./dummy.log");
            systemLogService.appendSsrError("{\"level\":\"error\",\"message\":\"test\"}");

            given(s3Client.headObject(any(HeadObjectRequest.class)))
                    .willThrow(NoSuchKeyException.builder().message("Not Found").build());
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            systemLogService.flushSsrErrors(LocalDate.of(2026, 5, 8));

            ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
            assertThat(requestCaptor.getValue().key()).isEqualTo("logs/ssr-error/2026-05-08.jsonl");
        }

        @Test
        @DisplayName("既存ファイルがある場合は追記する")
        void append_whenFileExists() {
            systemLogService = createService("./dummy.log");
            systemLogService.appendSsrError("{\"level\":\"error\",\"message\":\"new error\"}");

            String existingContent = "{\"level\":\"warn\",\"message\":\"old warn\"}\n";
            given(s3Client.headObject(any(HeadObjectRequest.class)))
                    .willReturn(HeadObjectResponse.builder().build());
            given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                    .willReturn(ResponseBytes.fromByteArray(
                            GetObjectResponse.builder().build(),
                            existingContent.getBytes(StandardCharsets.UTF_8)));
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            systemLogService.flushSsrErrors(LocalDate.of(2026, 5, 8));

            ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploadedBytes;
            try {
                uploadedBytes = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            String uploadedContent = new String(uploadedBytes, StandardCharsets.UTF_8);
            // 既存内容と新規行が両方含まれていることを確認
            assertThat(uploadedContent).contains("old warn");
            assertThat(uploadedContent).contains("new error");
        }
    }

    // ==================== listLogFiles ====================

    @Nested
    @DisplayName("listLogFiles")
    class ListLogFilesTest {

        @BeforeEach
        void setupPresigner() throws Exception {
            PresignedGetObjectRequest presignedRequest = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
            given(presignedRequest.url()).willReturn(new URL("https://example.com/signed-url"));
            given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).willReturn(presignedRequest);
        }

        @Test
        @DisplayName("slow-query 指定で スロークエリログのみ取得する")
        void listSlowQueryOnly() {
            systemLogService = createService("./dummy.log");
            S3Object s3Obj = S3Object.builder()
                    .key("logs/slow-query/2026-05-08.log")
                    .size(1024L)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willReturn(ListObjectsV2Response.builder().contents(s3Obj).build());

            List<SystemLogFileResponse> result = systemLogService.listLogFiles("slow-query", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).type()).isEqualTo("slow-query");
            assertThat(result.get(0).date()).isEqualTo("2026-05-08");
            assertThat(result.get(0).sizeBytes()).isEqualTo(1024L);
        }

        @Test
        @DisplayName("ssr-error 指定で SSR エラーログのみ取得する")
        void listSsrErrorOnly() {
            systemLogService = createService("./dummy.log");
            S3Object s3Obj = S3Object.builder()
                    .key("logs/ssr-error/2026-05-08.jsonl")
                    .size(512L)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willReturn(ListObjectsV2Response.builder().contents(s3Obj).build());

            List<SystemLogFileResponse> result = systemLogService.listLogFiles("ssr-error", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).type()).isEqualTo("ssr-error");
        }

        @Test
        @DisplayName("type が null の場合は両方のプレフィックスを検索する")
        void listBothTypes_whenTypeIsNull() {
            systemLogService = createService("./dummy.log");
            S3Object slowObj = S3Object.builder().key("logs/slow-query/2026-05-08.log").size(100L).build();
            S3Object ssrObj = S3Object.builder().key("logs/ssr-error/2026-05-08.jsonl").size(200L).build();

            // 2回呼ばれる（slow-query プレフィックス + ssr-error プレフィックス）
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willReturn(ListObjectsV2Response.builder().contents(slowObj).build())
                    .willReturn(ListObjectsV2Response.builder().contents(ssrObj).build());

            List<SystemLogFileResponse> result = systemLogService.listLogFiles(null, null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("日付フィルタを指定すると一致するファイルのみ返す")
        void filterByDate() {
            systemLogService = createService("./dummy.log");
            S3Object obj1 = S3Object.builder().key("logs/slow-query/2026-05-08.log").size(100L).build();
            S3Object obj2 = S3Object.builder().key("logs/slow-query/2026-05-07.log").size(200L).build();

            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willReturn(ListObjectsV2Response.builder().contents(obj1, obj2).build());

            List<SystemLogFileResponse> result = systemLogService.listLogFiles("slow-query", LocalDate.of(2026, 5, 8));

            // 2026-05-08 のファイルのみ返る
            assertThat(result).hasSize(1);
            assertThat(result.get(0).date()).isEqualTo("2026-05-08");
        }
    }
}
