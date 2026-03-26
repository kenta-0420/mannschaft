package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.directmail.dto.DirectMailImageUploadResponse;
import com.mannschaft.app.directmail.entity.DirectMailImageUploadEntity;
import com.mannschaft.app.directmail.repository.DirectMailImageUploadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DirectMailImageService} の単体テスト。
 * ダイレクトメール画像アップロードのバリデーション・S3アップロード・DB保存ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectMailImageService 単体テスト")
class DirectMailImageServiceTest {

    @Mock
    private DirectMailImageUploadRepository imageUploadRepository;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private DirectMailImageService directMailImageService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String TEST_SCOPE_TYPE = "TEAM";
    private static final Long TEST_SCOPE_ID = 1L;
    private static final Long TEST_USER_ID = 100L;
    private static final String TEST_FILE_NAME = "test-image.png";
    private static final String TEST_CONTENT_TYPE = "image/png";
    private static final long VALID_FILE_SIZE = 1024L; // 1KB
    private static final long EXCEEDED_FILE_SIZE = 6 * 1024 * 1024L; // 6MB

    private MultipartFile createMockFile(String contentType, long size, String fileName) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        given(file.getContentType()).willReturn(contentType);
        given(file.getSize()).willReturn(size);
        given(file.getOriginalFilename()).willReturn(fileName);
        given(file.getBytes()).willReturn(new byte[(int) Math.min(size, 1024)]);
        return file;
    }

    private DirectMailImageUploadEntity createSavedEntity() {
        return DirectMailImageUploadEntity.builder()
                .id(1L)
                .s3Key("direct-mail/team/1/images/uuid_test-image.png")
                .fileName(TEST_FILE_NAME)
                .fileSize((int) VALID_FILE_SIZE)
                .contentType(TEST_CONTENT_TYPE)
                .uploadedBy(TEST_USER_ID)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // uploadImage
    // ========================================

    @Nested
    @DisplayName("uploadImage")
    class UploadImage {

        @Test
        @DisplayName("正常系: 画像アップロード成功でレスポンスが返る")
        void アップロード_正常_レスポンスが返る() throws IOException {
            // Given
            MultipartFile file = createMockFile(TEST_CONTENT_TYPE, VALID_FILE_SIZE, TEST_FILE_NAME);
            DirectMailImageUploadEntity savedEntity = createSavedEntity();
            given(imageUploadRepository.save(any(DirectMailImageUploadEntity.class))).willReturn(savedEntity);
            given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                    .willReturn("https://s3.example.com/download/image.png");

            // When
            DirectMailImageUploadResponse response = directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getFileName()).isEqualTo(TEST_FILE_NAME);
            assertThat(response.getContentType()).isEqualTo(TEST_CONTENT_TYPE);
            assertThat(response.getImageUrl()).isEqualTo("https://s3.example.com/download/image.png");
            verify(storageService).upload(anyString(), any(byte[].class), eq(TEST_CONTENT_TYPE));
            verify(imageUploadRepository).save(any(DirectMailImageUploadEntity.class));
        }

        @Test
        @DisplayName("異常系: ファイルサイズ超過でDM_008例外")
        void アップロード_サイズ超過_DM008例外() throws IOException {
            // Given
            MultipartFile file = mock(MultipartFile.class);
            given(file.getSize()).willReturn(EXCEEDED_FILE_SIZE);

            // When / Then
            assertThatThrownBy(() -> directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_008"));
            verify(storageService, never()).upload(anyString(), any(byte[].class), anyString());
        }

        @Test
        @DisplayName("境界値: ちょうど5MBのファイルはアップロード成功")
        void アップロード_ちょうど5MB_成功() throws IOException {
            // Given
            long exactMaxSize = 5 * 1024 * 1024L;
            MultipartFile file = createMockFile(TEST_CONTENT_TYPE, exactMaxSize, TEST_FILE_NAME);
            DirectMailImageUploadEntity savedEntity = createSavedEntity();
            given(imageUploadRepository.save(any(DirectMailImageUploadEntity.class))).willReturn(savedEntity);
            given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                    .willReturn("https://s3.example.com/download");

            // When
            DirectMailImageUploadResponse response = directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file);

            // Then
            assertThat(response).isNotNull();
            verify(storageService).upload(anyString(), any(byte[].class), eq(TEST_CONTENT_TYPE));
        }

        @Test
        @DisplayName("境界値: 5MB+1バイトでDM_008例外")
        void アップロード_5MBプラス1_DM008例外() {
            // Given
            long overMaxSize = 5 * 1024 * 1024L + 1;
            MultipartFile file = mock(MultipartFile.class);
            given(file.getSize()).willReturn(overMaxSize);

            // When / Then
            assertThatThrownBy(() -> directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_008"));
        }

        @Test
        @DisplayName("異常系: 許可されていないContent-TypeでDM_009例外")
        void アップロード_不正ContentType_DM009例外() {
            // Given
            MultipartFile file = mock(MultipartFile.class);
            given(file.getSize()).willReturn(VALID_FILE_SIZE);
            given(file.getContentType()).willReturn("application/pdf");

            // When / Then
            assertThatThrownBy(() -> directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_009"));
            verify(storageService, never()).upload(anyString(), any(byte[].class), anyString());
        }

        @Test
        @DisplayName("正常系: image/jpegは許可される")
        void アップロード_JPEG_許可される() throws IOException {
            // Given
            MultipartFile file = createMockFile("image/jpeg", VALID_FILE_SIZE, "photo.jpg");
            DirectMailImageUploadEntity savedEntity = createSavedEntity();
            given(imageUploadRepository.save(any(DirectMailImageUploadEntity.class))).willReturn(savedEntity);
            given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                    .willReturn("https://s3.example.com/download");

            // When
            DirectMailImageUploadResponse response = directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("正常系: image/gifは許可される")
        void アップロード_GIF_許可される() throws IOException {
            // Given
            MultipartFile file = createMockFile("image/gif", VALID_FILE_SIZE, "animation.gif");
            DirectMailImageUploadEntity savedEntity = createSavedEntity();
            given(imageUploadRepository.save(any(DirectMailImageUploadEntity.class))).willReturn(savedEntity);
            given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                    .willReturn("https://s3.example.com/download");

            // When
            DirectMailImageUploadResponse response = directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("正常系: image/webpは許可される")
        void アップロード_WEBP_許可される() throws IOException {
            // Given
            MultipartFile file = createMockFile("image/webp", VALID_FILE_SIZE, "image.webp");
            DirectMailImageUploadEntity savedEntity = createSavedEntity();
            given(imageUploadRepository.save(any(DirectMailImageUploadEntity.class))).willReturn(savedEntity);
            given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                    .willReturn("https://s3.example.com/download");

            // When
            DirectMailImageUploadResponse response = directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file);

            // Then
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("異常系: nullのContent-Typeで例外がスローされる")
        void アップロード_nullContentType_例外() {
            // Given
            MultipartFile file = mock(MultipartFile.class);
            given(file.getSize()).willReturn(VALID_FILE_SIZE);
            given(file.getContentType()).willReturn(null);

            // When / Then — Set.of().contains(null) throws NullPointerException
            assertThatThrownBy(() -> directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("異常系: IOException発生でDM_008例外")
        void アップロード_IOException_DM008例外() throws IOException {
            // Given
            MultipartFile file = mock(MultipartFile.class);
            given(file.getSize()).willReturn(VALID_FILE_SIZE);
            given(file.getContentType()).willReturn(TEST_CONTENT_TYPE);
            given(file.getOriginalFilename()).willReturn(TEST_FILE_NAME);
            given(file.getBytes()).willThrow(new IOException("read error"));

            // When / Then
            assertThatThrownBy(() -> directMailImageService.uploadImage(
                    TEST_SCOPE_TYPE, TEST_SCOPE_ID, TEST_USER_ID, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DM_008"));
        }
    }
}
