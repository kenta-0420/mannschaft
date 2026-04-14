package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogMediaUploadUrlRequest;
import com.mannschaft.app.cms.dto.BlogMediaUploadUrlResponse;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.service.MultipartUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * {@link BlogMediaService} の単体テスト。
 * Mockito で R2StorageService / MultipartUploadService / Repository をモックして
 * ビジネスロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogMediaService 単体テスト")
class BlogMediaServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private MultipartUploadService multipartUploadService;

    @Mock
    private BlogMediaUploadRepository blogMediaUploadRepository;

    @InjectMocks
    private BlogMediaService blogMediaService;

    /** テスト用固定ユーザー ID */
    private static final Long UPLOADER_ID = 1L;

    /** テスト用記事 ID */
    private static final Long BLOG_POST_ID = 100L;

    // ==================== generateUploadUrl (IMAGE) ====================

    @Nested
    @DisplayName("generateUploadUrl IMAGE")
    class GenerateUploadUrlImage {

        @Test
        @DisplayName("正常系_IMAGE_Presigned URL発行成功")
        void 正常系_IMAGE_Presigned_URL発行成功() {
            // given
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "IMAGE", "image/jpeg", 1024L * 1024, "TEAM", 10L, BLOG_POST_ID);

            given(blogMediaUploadRepository.countByBlogPostIdAndMediaType(BLOG_POST_ID, "IMAGE"))
                    .willReturn(0);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any(Duration.class)))
                    .willReturn(new PresignedUploadResult(
                            "https://r2.example.com/presigned-put-url", "blog/TEAM/10/uuid.jpg", 600L));
            given(blogMediaUploadRepository.save(any(BlogMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        BlogMediaUploadEntity entity = inv.getArgument(0);
                        // ID 付きエンティティを再構築して返す
                        return BlogMediaUploadEntity.builder()
                                .blogPostId(entity.getBlogPostId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .s3Key(entity.getS3Key())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            BlogMediaUploadUrlResponse result = blogMediaService.generateUploadUrl(UPLOADER_ID, req);

            // then
            assertThat(result.getMediaType()).isEqualTo("IMAGE");
            assertThat(result.getUploadUrl()).isEqualTo("https://r2.example.com/presigned-put-url");
            assertThat(result.getExpiresIn()).isEqualTo(600);
            assertThat(result.getFileKey()).contains("blog/TEAM/10/");
            assertThat(result.getUploadId()).isNull();
            assertThat(result.getPartSize()).isNull();
            then(r2StorageService).should().generateUploadUrl(anyString(), eq("image/jpeg"), any(Duration.class));
            then(blogMediaUploadRepository).should().save(any(BlogMediaUploadEntity.class));
        }

        @Test
        @DisplayName("異常系_IMAGE_contentType不正_BAD_REQUEST")
        void 異常系_IMAGE_contentType不正_BAD_REQUEST() {
            // given
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "IMAGE", "image/bmp", 1024L, "TEAM", 10L, null);

            // when / then
            assertThatThrownBy(() -> blogMediaService.generateUploadUrl(UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
            then(blogMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("異常系_IMAGE_サイズ超過100MB_BAD_REQUEST")
        void 異常系_IMAGE_サイズ超過100MB_BAD_REQUEST() {
            // given: 100MB + 1バイト
            long oversizedFileSize = 100L * 1024 * 1024 + 1;
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "IMAGE", "image/jpeg", oversizedFileSize, "TEAM", 10L, null);

            // when / then
            assertThatThrownBy(() -> blogMediaService.generateUploadUrl(UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("異常系_IMAGE_記事あたり上限超過_UNPROCESSABLE_ENTITY")
        void 異常系_IMAGE_記事あたり上限超過_UNPROCESSABLE_ENTITY() {
            // given: 既に30枚アップロード済み（上限）
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "IMAGE", "image/jpeg", 1024L, "TEAM", 10L, BLOG_POST_ID);
            given(blogMediaUploadRepository.countByBlogPostIdAndMediaType(BLOG_POST_ID, "IMAGE"))
                    .willReturn(30);

            // when / then
            assertThatThrownBy(() -> blogMediaService.generateUploadUrl(UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }
    }

    // ==================== generateUploadUrl (VIDEO) ====================

    @Nested
    @DisplayName("generateUploadUrl VIDEO")
    class GenerateUploadUrlVideo {

        @Test
        @DisplayName("正常系_VIDEO_Multipart開始成功")
        void 正常系_VIDEO_Multipart開始成功() {
            // given
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "VIDEO", "video/mp4", 200L * 1024 * 1024, "ORGANIZATION", 5L, BLOG_POST_ID);

            given(blogMediaUploadRepository.countByBlogPostIdAndMediaType(BLOG_POST_ID, "VIDEO"))
                    .willReturn(0);
            given(multipartUploadService.startUpload(eq(UPLOADER_ID), any(StartMultipartUploadRequest.class)))
                    .willReturn(new StartMultipartUploadResponse(
                            "test-multipart-upload-id", "blog/ORGANIZATION/5/uuid.mp4", 1, 10L * 1024 * 1024));
            given(blogMediaUploadRepository.save(any(BlogMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        BlogMediaUploadEntity entity = inv.getArgument(0);
                        return BlogMediaUploadEntity.builder()
                                .blogPostId(entity.getBlogPostId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .s3Key(entity.getS3Key())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            BlogMediaUploadUrlResponse result = blogMediaService.generateUploadUrl(UPLOADER_ID, req);

            // then
            assertThat(result.getMediaType()).isEqualTo("VIDEO");
            assertThat(result.getUploadId()).isEqualTo("test-multipart-upload-id");
            assertThat(result.getPartSize()).isEqualTo(10L * 1024 * 1024);
            assertThat(result.getFileKey()).isEqualTo("blog/ORGANIZATION/5/uuid.mp4");
            assertThat(result.getUploadUrl()).isNull();
            assertThat(result.getExpiresIn()).isNull();
            then(multipartUploadService).should().startUpload(eq(UPLOADER_ID), any(StartMultipartUploadRequest.class));
            then(blogMediaUploadRepository).should().save(any(BlogMediaUploadEntity.class));
        }

        @Test
        @DisplayName("異常系_VIDEO_contentType不正_BAD_REQUEST")
        void 異常系_VIDEO_contentType不正_BAD_REQUEST() {
            // given
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "VIDEO", "video/avi", 1024L, "PERSONAL", 1L, null);

            // when / then
            assertThatThrownBy(() -> blogMediaService.generateUploadUrl(UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(multipartUploadService).should(never()).startUpload(anyLong(), any());
        }

        @Test
        @DisplayName("異常系_VIDEO_サイズ超過1GB_BAD_REQUEST")
        void 異常系_VIDEO_サイズ超過1GB_BAD_REQUEST() {
            // given: 1GB + 1バイト
            long oversizedFileSize = 1024L * 1024 * 1024 + 1;
            BlogMediaUploadUrlRequest req = new BlogMediaUploadUrlRequest(
                    "VIDEO", "video/mp4", oversizedFileSize, "TEAM", 10L, null);

            // when / then
            assertThatThrownBy(() -> blogMediaService.generateUploadUrl(UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(multipartUploadService).should(never()).startUpload(anyLong(), any());
        }
    }

    // ==================== cleanupOrphanMedia ====================

    @Nested
    @DisplayName("cleanupOrphanMedia")
    class CleanupOrphanMedia {

        @Test
        @DisplayName("正常系_72時間超過の孤立メディアを削除")
        void 正常系_72時間超過の孤立メディアを削除() {
            // given: 孤立した IMAGE と VIDEO が各1件
            BlogMediaUploadEntity orphanImage = BlogMediaUploadEntity.builder()
                    .uploaderId(UPLOADER_ID)
                    .mediaType("IMAGE")
                    .s3Key("blog/TEAM/10/orphan-image.jpg")
                    .fileSize(1024L)
                    .contentType("image/jpeg")
                    .processingStatus("READY")
                    .build();

            BlogMediaUploadEntity orphanVideo = BlogMediaUploadEntity.builder()
                    .uploaderId(UPLOADER_ID)
                    .mediaType("VIDEO")
                    .s3Key("blog/TEAM/10/orphan-video.mp4")
                    .fileSize(1024L * 1024)
                    .contentType("video/mp4")
                    .processingStatus("PENDING")
                    .thumbnailR2Key("blog/TEAM/10/orphan-video-thumb.jpg")
                    .build();

            given(blogMediaUploadRepository.findByBlogPostIdIsNullAndCreatedAtBefore(any(LocalDateTime.class)))
                    .willReturn(List.of(orphanImage, orphanVideo));

            // when
            blogMediaService.cleanupOrphanMedia();

            // then: R2 からオブジェクト削除（VIDEO はサムネイルも含む）
            then(r2StorageService).should().delete("blog/TEAM/10/orphan-image.jpg");
            then(r2StorageService).should().delete("blog/TEAM/10/orphan-video.mp4");
            then(r2StorageService).should().delete("blog/TEAM/10/orphan-video-thumb.jpg");
            // DB から一括削除
            then(blogMediaUploadRepository).should().deleteAll(List.of(orphanImage, orphanVideo));
        }
    }
}
