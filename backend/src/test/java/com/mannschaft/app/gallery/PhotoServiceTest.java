package com.mannschaft.app.gallery;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.dto.UploadPhotosRequest;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.repository.PhotoRepository;
import com.mannschaft.app.gallery.service.PhotoAlbumService;
import com.mannschaft.app.gallery.service.PhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoService 単体テスト")
class PhotoServiceTest {

    @Mock private PhotoRepository photoRepository;
    @Mock private PhotoAlbumRepository albumRepository;
    @Mock private GalleryMapper galleryMapper;
    @Mock private R2StorageService r2StorageService;
    @Mock private DomainEventPublisher eventPublisher;

    private PhotoAlbumService albumService;
    private PhotoService service;

    @BeforeEach
    void setUp() {
        albumService = new PhotoAlbumService(albumRepository, galleryMapper);
        service = new PhotoService(photoRepository, albumRepository, albumService,
                galleryMapper, r2StorageService, eventPublisher);
    }

    private static final Long ALBUM_ID = 1L;
    private static final Long PHOTO_ID = 10L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("uploadPhotos")
    class UploadPhotos {

        @Test
        @DisplayName("異常系: バッチ上限超過でGALLERY_010例外")
        void アップロード_バッチ上限超過_例外() {
            List<UploadPhotosRequest.PhotoItem> items = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                items.add(new UploadPhotosRequest.PhotoItem(
                        "key" + i, "file" + i + ".jpg", 1000L, "image/jpeg", null, null, null));
            }
            UploadPhotosRequest request = new UploadPhotosRequest(items);

            assertThatThrownBy(() -> service.uploadPhotos(ALBUM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_010"));
        }

        @Test
        @DisplayName("異常系: ファイルサイズ超過でGALLERY_005例外")
        void アップロード_サイズ超過_例外() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder().teamId(1L).title("a").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            UploadPhotosRequest.PhotoItem item = new UploadPhotosRequest.PhotoItem(
                    "key1", "file.jpg", 101L * 1024 * 1024, "image/jpeg", null, null, null); // 101MB
            UploadPhotosRequest request = new UploadPhotosRequest(List.of(item));

            assertThatThrownBy(() -> service.uploadPhotos(ALBUM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_005"));
        }

        @Test
        @DisplayName("正常系: PHOTO タイプでエンティティ保存される")
        void アップロード_写真_正常() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder().teamId(1L).title("a").photoCount(0).build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));
            given(albumRepository.sumPhotoCountByTeamId(1L)).willReturn(0);

            PhotoEntity savedEntity = PhotoEntity.builder()
                    .albumId(ALBUM_ID).r2Key("gallery/TEAM/1/album-1/photo-uuid.jpg")
                    .originalFilename("test.jpg").mediaType(GalleryMediaType.PHOTO)
                    .processingStatus(GalleryProcessingStatus.READY).build();
            given(photoRepository.save(any(PhotoEntity.class))).willReturn(savedEntity);

            UploadPhotosRequest.PhotoItem item = new UploadPhotosRequest.PhotoItem(
                    "gallery/TEAM/1/album-1/photo-uuid.jpg", "test.jpg", 1024L, "image/jpeg",
                    null, "PHOTO", null);
            UploadPhotosRequest request = new UploadPhotosRequest(List.of(item));

            var response = service.uploadPhotos(ALBUM_ID, USER_ID, request);

            assertThat(response.getUploadedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: VIDEO タイプでストレージ確認後保存される")
        void アップロード_動画_正常() {
            PhotoAlbumEntity album = PhotoAlbumEntity.builder().teamId(1L).title("a").photoCount(0).build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));
            given(albumRepository.sumPhotoCountByTeamId(1L)).willReturn(0);
            given(r2StorageService.objectExists("gallery/TEAM/1/album-1/video-uuid.mp4")).willReturn(true);

            PhotoEntity savedEntity = PhotoEntity.builder()
                    .albumId(ALBUM_ID).r2Key("gallery/TEAM/1/album-1/video-uuid.mp4")
                    .originalFilename("test.mp4").mediaType(GalleryMediaType.VIDEO)
                    .processingStatus(GalleryProcessingStatus.PENDING).build();
            given(photoRepository.save(any(PhotoEntity.class))).willReturn(savedEntity);

            UploadPhotosRequest.PhotoItem item = new UploadPhotosRequest.PhotoItem(
                    "gallery/TEAM/1/album-1/video-uuid.mp4", "test.mp4", 50 * 1024 * 1024L,
                    "video/mp4", null, "VIDEO", null);
            UploadPhotosRequest request = new UploadPhotosRequest(List.of(item));

            var response = service.uploadPhotos(ALBUM_ID, USER_ID, request);

            assertThat(response.getUploadedCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("deletePhoto")
    class DeletePhoto {
        @Test
        @DisplayName("正常系: 写真が削除されイベントが発行される")
        void 削除_正常_イベント発行() {
            PhotoEntity entity = PhotoEntity.builder()
                    .albumId(ALBUM_ID).r2Key("gallery/TEAM/1/album-1/photo-uuid.jpg")
                    .originalFilename("test.jpg").build();
            given(photoRepository.findById(PHOTO_ID)).willReturn(Optional.of(entity));
            given(albumRepository.findById(ALBUM_ID)).willReturn(
                    Optional.of(PhotoAlbumEntity.builder().teamId(1L).title("a").photoCount(1).build()));

            service.deletePhoto(PHOTO_ID);

            verify(photoRepository).delete(entity);
            verify(eventPublisher).publish(any(com.mannschaft.app.common.event.DomainEvent.class));
        }

        @Test
        @DisplayName("異常系: 写真不在でGALLERY_002例外")
        void 削除_不在_例外() {
            given(photoRepository.findById(PHOTO_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deletePhoto(PHOTO_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_002"));
        }
    }

    @Nested
    @DisplayName("getPhotoDownloadUrl")
    class GetPhotoDownloadUrl {
        @Test
        @DisplayName("異常系: ダウンロード不許可でGALLERY_004例外")
        void ダウンロード_不許可_例外() {
            PhotoEntity entity = PhotoEntity.builder()
                    .albumId(ALBUM_ID).r2Key("gallery/TEAM/1/album-1/photo-uuid.jpg")
                    .originalFilename("t.jpg").build();
            given(photoRepository.findById(PHOTO_ID)).willReturn(Optional.of(entity));
            PhotoAlbumEntity album = PhotoAlbumEntity.builder().teamId(1L).title("a").allowDownload(false).build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(album));

            assertThatThrownBy(() -> service.getPhotoDownloadUrl(PHOTO_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_004"));
        }
    }
}
