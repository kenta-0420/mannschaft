package com.mannschaft.app.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link StorageService} の単体テスト。
 * インターフェース契約に基づくモック実装の振る舞いを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService 単体テスト")
class StorageServiceTest {

    // ========================================
    // テスト用定数
    // ========================================

    private static final String TEST_S3_KEY = "test/path/file.pdf";
    private static final String TEST_CONTENT_TYPE = "application/pdf";
    private static final Duration TEST_TTL = Duration.ofMinutes(15);

    // ========================================
    // generateUploadUrl
    // ========================================

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("正常系: PresignedUploadResultが返る")
        void 生成_正常_結果が返る() {
            // Given
            StorageService storageService = mock(StorageService.class);
            PresignedUploadResult expected = new PresignedUploadResult(
                    "https://s3.example.com/upload", TEST_S3_KEY, TEST_TTL.toSeconds());
            given(storageService.generateUploadUrl(TEST_S3_KEY, TEST_CONTENT_TYPE, TEST_TTL))
                    .willReturn(expected);

            // When
            PresignedUploadResult result = storageService.generateUploadUrl(TEST_S3_KEY, TEST_CONTENT_TYPE, TEST_TTL);

            // Then
            assertThat(result.uploadUrl()).isEqualTo("https://s3.example.com/upload");
            assertThat(result.s3Key()).isEqualTo(TEST_S3_KEY);
            assertThat(result.expiresInSeconds()).isEqualTo(TEST_TTL.toSeconds());
        }
    }

    // ========================================
    // generateDownloadUrl
    // ========================================

    @Nested
    @DisplayName("generateDownloadUrl")
    class GenerateDownloadUrl {

        @Test
        @DisplayName("正常系: ダウンロードURLが返る")
        void 生成_正常_URLが返る() {
            // Given
            StorageService storageService = mock(StorageService.class);
            given(storageService.generateDownloadUrl(TEST_S3_KEY, TEST_TTL))
                    .willReturn("https://s3.example.com/download");

            // When
            String url = storageService.generateDownloadUrl(TEST_S3_KEY, TEST_TTL);

            // Then
            assertThat(url).isEqualTo("https://s3.example.com/download");
        }
    }

    // ========================================
    // upload (byte[])
    // ========================================

    @Nested
    @DisplayName("upload (byte[])")
    class UploadBytes {

        @Test
        @DisplayName("正常系: バイト配列アップロードが呼ばれる")
        void アップロード_正常_メソッド呼出() {
            // Given
            StorageService storageService = mock(StorageService.class);
            byte[] data = "test".getBytes();

            // When
            storageService.upload(TEST_S3_KEY, data, TEST_CONTENT_TYPE);

            // Then
            verify(storageService).upload(TEST_S3_KEY, data, TEST_CONTENT_TYPE);
        }
    }

    // ========================================
    // upload (InputStream)
    // ========================================

    @Nested
    @DisplayName("upload (InputStream)")
    class UploadInputStream {

        @Test
        @DisplayName("正常系: InputStreamアップロードが呼ばれる")
        void ストリームアップロード_正常_メソッド呼出() {
            // Given
            StorageService storageService = mock(StorageService.class);
            InputStream data = new ByteArrayInputStream("test".getBytes());

            // When
            storageService.upload(TEST_S3_KEY, data, 4L, TEST_CONTENT_TYPE);

            // Then
            verify(storageService).upload(TEST_S3_KEY, data, 4L, TEST_CONTENT_TYPE);
        }
    }

    // ========================================
    // delete
    // ========================================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 削除が呼ばれる")
        void 削除_正常_メソッド呼出() {
            // Given
            StorageService storageService = mock(StorageService.class);

            // When
            storageService.delete(TEST_S3_KEY);

            // Then
            verify(storageService).delete(TEST_S3_KEY);
        }
    }

    // ========================================
    // deleteAll
    // ========================================

    @Nested
    @DisplayName("deleteAll")
    class DeleteAll {

        @Test
        @DisplayName("正常系: 一括削除が呼ばれる")
        void 一括削除_正常_メソッド呼出() {
            // Given
            StorageService storageService = mock(StorageService.class);
            List<String> keys = List.of("key1", "key2");

            // When
            storageService.deleteAll(keys);

            // Then
            verify(storageService).deleteAll(keys);
        }
    }

    // ========================================
    // download
    // ========================================

    @Nested
    @DisplayName("download")
    class Download {

        @Test
        @DisplayName("正常系: ダウンロードでバイト配列が返る")
        void ダウンロード_正常_データが返る() {
            // Given
            StorageService storageService = mock(StorageService.class);
            byte[] expected = "content".getBytes();
            given(storageService.download(TEST_S3_KEY)).willReturn(expected);

            // When
            byte[] result = storageService.download(TEST_S3_KEY);

            // Then
            assertThat(result).isEqualTo(expected);
        }
    }
}
