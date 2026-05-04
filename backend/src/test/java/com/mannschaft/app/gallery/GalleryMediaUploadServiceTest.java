package com.mannschaft.app.gallery;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.gallery.dto.MediaUploadUrlRequest;
import com.mannschaft.app.gallery.dto.MediaUploadUrlResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.service.GalleryMediaUploadService;
import com.mannschaft.app.gallery.service.PhotoAlbumService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * {@link GalleryMediaUploadService} の単体テスト。
 *
 * <p>F13 Phase 4-δ: クォータ超過テスト・checkQuota 呼び出し検証を追加。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GalleryMediaUploadService 単体テスト")
class GalleryMediaUploadServiceTest {

    @Mock private R2StorageService r2StorageService;
    @Mock private PhotoAlbumRepository albumRepository;
    @Mock private GalleryMapper galleryMapper;
    @Mock private StorageQuotaService storageQuotaService;

    private PhotoAlbumService albumService;
    private GalleryMediaUploadService service;

    private static final Long ALBUM_ID = 1L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        albumService = new PhotoAlbumService(albumRepository, galleryMapper);
        service = new GalleryMediaUploadService(r2StorageService, albumService, storageQuotaService);
    }

    @Nested
    @DisplayName("generateUploadUrl - PHOTO")
    class GenerateUploadUrlPhoto {

        @Test
        @DisplayName("正常系: PHOTO (image/jpeg) で R2Key 形式が正しい")
        void 写真_jpeg_R2Key形式() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "gallery/TEAM/10/album-1/photo-uuid.jpg", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", null);
            MediaUploadUrlResponse response = service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            String capturedKey = keyCaptor.getValue();
            assertThat(capturedKey).matches("gallery/TEAM/10/album-1/photo-[a-f0-9\\-]+\\.jpg");
            assertThat(response.getExpiresInSeconds()).isEqualTo(600L); // 10分
        }

        @Test
        @DisplayName("正常系: PHOTO (image/png) で拡張子が png")
        void 写真_png_拡張子() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/png", null);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            assertThat(keyCaptor.getValue()).endsWith(".png");
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープのアルバム")
        void 組織スコープ_R2Key形式() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .organizationId(20L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", null);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            assertThat(keyCaptor.getValue()).startsWith("gallery/ORGANIZATION/20/album-1/photo-");
        }
    }

    @Nested
    @DisplayName("generateUploadUrl - VIDEO")
    class GenerateUploadUrlVideo {

        @Test
        @DisplayName("正常系: VIDEO (video/mp4) で R2Key 形式・TTL 15分")
        void 動画_mp4_R2Key形式とTTL() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "gallery/TEAM/10/album-1/video-uuid.mp4", 900L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("VIDEO", "video/mp4", null);
            MediaUploadUrlResponse response = service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            String capturedKey = keyCaptor.getValue();
            assertThat(capturedKey).matches("gallery/TEAM/10/album-1/video-[a-f0-9\\-]+\\.mp4");
            assertThat(response.getExpiresInSeconds()).isEqualTo(900L); // 15分
        }

        @Test
        @DisplayName("正常系: VIDEO (video/webm) で拡張子が webm")
        void 動画_webm_拡張子() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 900L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("VIDEO", "video/webm", null);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            assertThat(keyCaptor.getValue()).endsWith(".webm");
        }

        @Test
        @DisplayName("正常系: VIDEO (video/quicktime) で拡張子が mov")
        void 動画_quicktime_拡張子() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 900L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("VIDEO", "video/quicktime", null);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            assertThat(keyCaptor.getValue()).endsWith(".mov");
        }
    }

    @Nested
    @DisplayName("generateUploadUrl - バリデーション")
    class Validation {

        @Test
        @DisplayName("異常系: PHOTO に非対応MIME (video/mp4) で GALLERY_012 例外")
        void 写真_非対応MIME_例外() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "video/mp4", null);

            assertThatThrownBy(() -> service.generateUploadUrl(ALBUM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_012"));
        }

        @Test
        @DisplayName("異常系: VIDEO に非対応MIME (image/jpeg) で GALLERY_012 例外")
        void 動画_非対応MIME_例外() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("VIDEO", "image/jpeg", null);

            assertThatThrownBy(() -> service.generateUploadUrl(ALBUM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_012"));
        }

        @Test
        @DisplayName("異常系: 非対応MIME (application/pdf) で GALLERY_012 例外")
        void 非対応MIME_pdf_例外() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "application/pdf", null);

            assertThatThrownBy(() -> service.generateUploadUrl(ALBUM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_012"));
        }
    }

    // ==================== F13 Phase 4-δ クォータ統合テスト ====================

    @Nested
    @DisplayName("F13 Phase 4-δ: StorageQuotaService 統合テスト")
    class StorageQuotaIntegration {

        @Test
        @DisplayName("正常系: fileSize あり → checkQuota が呼ばれる（TEAM スコープ）")
        void fileSize有り_checkQuota呼び出し確認_TEAMスコープ() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", 1024L * 1024);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            then(storageQuotaService).should().checkQuota(
                    eq(StorageScopeType.TEAM), eq(10L), eq(1024L * 1024));
        }

        @Test
        @DisplayName("正常系: fileSize あり → checkQuota が呼ばれる（ORGANIZATION スコープ）")
        void fileSize有り_checkQuota呼び出し確認_ORGANIZATIONスコープ() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .organizationId(20L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", 2048L * 1024);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            then(storageQuotaService).should().checkQuota(
                    eq(StorageScopeType.ORGANIZATION), eq(20L), eq(2048L * 1024));
        }

        @Test
        @DisplayName("正常系: fileSize null → checkQuota が呼ばれない（後方互換）")
        void fileSize_null_checkQuota呼ばれない() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            given(r2StorageService.generateUploadUrl(keyCaptor.capture(), anyString(), any()))
                    .willReturn(new PresignedUploadResult("https://r2.example.com/upload", "", 600L));

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", null);
            service.generateUploadUrl(ALBUM_ID, request, USER_ID);

            then(storageQuotaService).should(org.mockito.Mockito.never()).checkQuota(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("異常系: クォータ超過 → GALLERY_014 例外スロー")
        void クォータ超過_GALLERY_014例外() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder()
                    .teamId(10L).title("test").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            willThrow(new StorageQuotaExceededException(StorageScopeType.TEAM, 10L, 1024L * 1024, 9L * 1024 * 1024 * 1024, 10L * 1024 * 1024 * 1024))
                    .given(storageQuotaService).checkQuota(any(), anyLong(), anyLong());

            MediaUploadUrlRequest request = new MediaUploadUrlRequest("PHOTO", "image/jpeg", 1024L * 1024);

            assertThatThrownBy(() -> service.generateUploadUrl(ALBUM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_014"));
        }
    }
}
