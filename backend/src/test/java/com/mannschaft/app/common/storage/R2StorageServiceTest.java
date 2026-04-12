package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link R2StorageService} の単体テスト。
 * R2 Pre-signed URL 生成、アップロード、ダウンロード、削除操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("R2StorageService 単体テスト")
class R2StorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private StorageProperties storageProperties;

    @InjectMocks
    private R2StorageService r2StorageService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final String TEST_BUCKET = "mannschaft-storage";
    private static final String TEST_R2_KEY = "test/path/file.pdf";
    private static final String TEST_CONTENT_TYPE = "application/pdf";
    private static final Duration TEST_TTL = Duration.ofMinutes(15);

    // ========================================
    // generateUploadUrl
    // ========================================

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("正常系: Pre-signed upload URLが返る")
        void 生成_正常_URLが返る() throws Exception {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
            given(presignedRequest.url()).willReturn(new URI("https://r2.example.com/upload").toURL());
            given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(presignedRequest);

            // When
            PresignedUploadResult result = r2StorageService.generateUploadUrl(TEST_R2_KEY, TEST_CONTENT_TYPE, TEST_TTL);

            // Then
            assertThat(result.uploadUrl()).isEqualTo("https://r2.example.com/upload");
            assertThat(result.s3Key()).isEqualTo(TEST_R2_KEY);
            assertThat(result.expiresInSeconds()).isEqualTo(TEST_TTL.toSeconds());
        }

        @Test
        @DisplayName("異常系: Presigner例外でSTORAGE_004例外")
        void 生成_例外発生_STORAGE004例外() {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                    .willThrow(new RuntimeException("presign error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.generateUploadUrl(TEST_R2_KEY, TEST_CONTENT_TYPE, TEST_TTL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_004"));
        }
    }

    // ========================================
    // generateDownloadUrl
    // ========================================

    @Nested
    @DisplayName("generateDownloadUrl")
    class GenerateDownloadUrl {

        @Test
        @DisplayName("正常系: Pre-signed download URLが返る")
        void 生成_正常_URLが返る() throws Exception {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
            given(presignedRequest.url()).willReturn(new URI("https://r2.example.com/download").toURL());
            given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).willReturn(presignedRequest);

            // When
            String url = r2StorageService.generateDownloadUrl(TEST_R2_KEY, TEST_TTL);

            // Then
            assertThat(url).isEqualTo("https://r2.example.com/download");
        }

        @Test
        @DisplayName("異常系: Presigner例外でSTORAGE_004例外")
        void 生成_例外発生_STORAGE004例外() {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                    .willThrow(new RuntimeException("presign error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.generateDownloadUrl(TEST_R2_KEY, TEST_TTL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_004"));
        }
    }

    // ========================================
    // upload (byte[])
    // ========================================

    @Nested
    @DisplayName("upload (byte[])")
    class UploadBytes {

        @Test
        @DisplayName("正常系: バイト配列アップロード成功")
        void アップロード_正常_成功() {
            // Given
            byte[] data = "test content".getBytes();
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            // When
            r2StorageService.upload(TEST_R2_KEY, data, TEST_CONTENT_TYPE);

            // Then
            verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("異常系: R2例外でSTORAGE_001例外")
        void アップロード_例外発生_STORAGE001例外() {
            // Given
            byte[] data = "test content".getBytes();
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willThrow(new RuntimeException("upload error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.upload(TEST_R2_KEY, data, TEST_CONTENT_TYPE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_001"));
        }
    }

    // ========================================
    // upload (InputStream)
    // ========================================

    @Nested
    @DisplayName("upload (InputStream)")
    class UploadInputStream {

        @Test
        @DisplayName("正常系: InputStreamアップロード成功")
        void ストリームアップロード_正常_成功() {
            // Given
            InputStream data = new ByteArrayInputStream("test content".getBytes());
            long contentLength = 12L;
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willReturn(PutObjectResponse.builder().build());

            // When
            r2StorageService.upload(TEST_R2_KEY, data, contentLength, TEST_CONTENT_TYPE);

            // Then
            verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("異常系: R2例外でSTORAGE_001例外")
        void ストリームアップロード_例外発生_STORAGE001例外() {
            // Given
            InputStream data = new ByteArrayInputStream("test content".getBytes());
            long contentLength = 12L;
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .willThrow(new RuntimeException("upload error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.upload(TEST_R2_KEY, data, contentLength, TEST_CONTENT_TYPE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_001"));
        }
    }

    // ========================================
    // delete
    // ========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 削除成功")
        void 削除_正常_成功() {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .willReturn(DeleteObjectResponse.builder().build());

            // When
            r2StorageService.delete(TEST_R2_KEY);

            // Then
            verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("異常系: R2例外でSTORAGE_003例外")
        void 削除_例外発生_STORAGE003例外() {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .willThrow(new RuntimeException("delete error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.delete(TEST_R2_KEY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_003"));
        }
    }

    // ========================================
    // deleteAll
    // ========================================

    @Nested
    @DisplayName("deleteAll")
    class DeleteAll {

        @Test
        @DisplayName("正常系: 一括削除成功")
        void 一括削除_正常_成功() {
            // Given
            List<String> keys = List.of("key1", "key2", "key3");
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                    .willReturn(DeleteObjectsResponse.builder().build());

            // When
            r2StorageService.deleteAll(keys);

            // Then
            verify(s3Client).deleteObjects(any(DeleteObjectsRequest.class));
        }

        @Test
        @DisplayName("境界値: nullリストで何もしない")
        void 一括削除_nullリスト_何もしない() {
            // When
            r2StorageService.deleteAll(null);

            // Then
            verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
        }

        @Test
        @DisplayName("境界値: 空リストで何もしない")
        void 一括削除_空リスト_何もしない() {
            // When
            r2StorageService.deleteAll(Collections.emptyList());

            // Then
            verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
        }

        @Test
        @DisplayName("異常系: R2例外でSTORAGE_003例外")
        void 一括削除_例外発生_STORAGE003例外() {
            // Given
            List<String> keys = List.of("key1", "key2");
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.deleteObjects(any(DeleteObjectsRequest.class)))
                    .willThrow(new RuntimeException("batch delete error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.deleteAll(keys))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_003"));
        }
    }

    // ========================================
    // download
    // ========================================

    @Nested
    @DisplayName("download")
    class Download {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("正常系: ダウンロード成功でバイト配列が返る")
        void ダウンロード_正常_データが返る() {
            // Given
            byte[] expectedData = "downloaded content".getBytes();
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
            given(responseBytes.asByteArray()).willReturn(expectedData);
            given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(responseBytes);

            // When
            byte[] result = r2StorageService.download(TEST_R2_KEY);

            // Then
            assertThat(result).isEqualTo(expectedData);
        }

        @Test
        @DisplayName("異常系: R2例外でSTORAGE_002例外")
        void ダウンロード_例外発生_STORAGE002例外() {
            // Given
            given(storageProperties.getBucket()).willReturn(TEST_BUCKET);
            given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                    .willThrow(new RuntimeException("download error"));

            // When / Then
            assertThatThrownBy(() -> r2StorageService.download(TEST_R2_KEY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("STORAGE_002"));
        }
    }
}
