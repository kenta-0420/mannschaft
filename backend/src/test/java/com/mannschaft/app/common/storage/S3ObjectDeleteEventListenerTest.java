package com.mannschaft.app.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link S3ObjectDeleteEventListener} の単体テスト。
 * S3オブジェクト削除イベント処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3ObjectDeleteEventListener 単体テスト")
class S3ObjectDeleteEventListenerTest {

    @Mock
    private StorageService storageService;

    @InjectMocks
    private S3ObjectDeleteEventListener listener;

    // ========================================
    // handleS3Delete
    // ========================================

    @Nested
    @DisplayName("handleS3Delete")
    class HandleS3Delete {

        @Test
        @DisplayName("正常系: S3オブジェクトが削除される")
        void handleS3Delete_正常_S3削除() {
            // Given
            List<String> s3Keys = List.of("uploads/file1.jpg", "uploads/file2.jpg");
            S3ObjectDeleteEvent event = new S3ObjectDeleteEvent(s3Keys);

            // When
            listener.handleS3Delete(event);

            // Then
            verify(storageService).deleteAll(s3Keys);
        }

        @Test
        @DisplayName("正常系: 単一キーの削除でもdeleteAllが呼ばれる")
        void handleS3Delete_単一キー_削除される() {
            // Given
            List<String> s3Keys = List.of("uploads/single-file.pdf");
            S3ObjectDeleteEvent event = new S3ObjectDeleteEvent(s3Keys);

            // When
            listener.handleS3Delete(event);

            // Then
            verify(storageService).deleteAll(s3Keys);
        }

        @Test
        @DisplayName("異常系: 削除失敗時でも例外をキャッチしてWARNログのみ")
        void handleS3Delete_削除失敗_例外キャッチ() {
            // Given
            List<String> s3Keys = List.of("uploads/file1.jpg");
            S3ObjectDeleteEvent event = new S3ObjectDeleteEvent(s3Keys);
            doThrow(new RuntimeException("S3接続エラー")).when(storageService).deleteAll(s3Keys);

            // When（例外がスローされないことを確認）
            listener.handleS3Delete(event);

            // Then
            verify(storageService).deleteAll(s3Keys);
        }

        @Test
        @DisplayName("正常系: 空のキーリストでもdeleteAllが呼ばれる")
        void handleS3Delete_空キーリスト_削除呼ばれる() {
            // Given
            List<String> s3Keys = List.of();
            S3ObjectDeleteEvent event = new S3ObjectDeleteEvent(s3Keys);

            // When
            listener.handleS3Delete(event);

            // Then
            verify(storageService).deleteAll(s3Keys);
        }
    }
}
