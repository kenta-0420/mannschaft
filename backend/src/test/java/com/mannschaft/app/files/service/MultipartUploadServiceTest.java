package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.R2StorageService.PresignedPartUrl;
import com.mannschaft.app.files.dto.CompleteMultipartRequest;
import com.mannschaft.app.files.dto.CompleteMultipartResponse;
import com.mannschaft.app.files.dto.PartUrlRequest;
import com.mannschaft.app.files.dto.PartUrlResponse;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.entity.MultipartUploadSessionEntity;
import com.mannschaft.app.files.repository.MultipartUploadSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * {@link MultipartUploadService} の単体テスト。
 * Mockito で R2StorageService と Repository をモックして
 * ビジネスロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MultipartUploadService 単体テスト")
class MultipartUploadServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private MultipartUploadSessionRepository sessionRepository;

    @InjectMocks
    private MultipartUploadService service;

    /** テスト用固定ユーザー ID */
    private static final Long USER_ID = 1L;

    /** テスト用固定 Upload ID */
    private static final String UPLOAD_ID = "test-upload-id-abc123";

    // ==================== startUpload ====================

    @Nested
    @DisplayName("startUpload")
    class StartUpload {

        @Test
        @DisplayName("正常系_VIDEO_MP4_ブログプレフィックス")
        void 正常系_VIDEO_MP4_ブログプレフィックス() {
            // given
            StartMultipartUploadRequest req = new StartMultipartUploadRequest(
                    null, "test-video.mp4", "video/mp4",
                    200 * 1024 * 1024L, 10, 20 * 1024 * 1024L, "blog/");
            given(r2StorageService.createMultipartUpload(anyString(), anyString()))
                    .willReturn(UPLOAD_ID);
            given(sessionRepository.save(any(MultipartUploadSessionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            StartMultipartUploadResponse result = service.startUpload(USER_ID, req);

            // then
            assertThat(result.getUploadId()).isEqualTo(UPLOAD_ID);
            assertThat(result.getFileKey()).startsWith("blog/");
            assertThat(result.getFileKey()).endsWith(".mp4");
            assertThat(result.getPartCount()).isEqualTo(10);
            assertThat(result.getPartSize()).isEqualTo(20 * 1024 * 1024L);
        }

        @Test
        @DisplayName("正常系_デフォルトプレフィックスはfiles/になる")
        void 正常系_デフォルトプレフィックスはfiles_になる() {
            // given
            StartMultipartUploadRequest req = new StartMultipartUploadRequest(
                    null, "archive.zip", "application/zip",
                    100 * 1024 * 1024L, 5, 20 * 1024 * 1024L, null);
            given(r2StorageService.createMultipartUpload(anyString(), anyString()))
                    .willReturn(UPLOAD_ID);
            given(sessionRepository.save(any(MultipartUploadSessionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            StartMultipartUploadResponse result = service.startUpload(USER_ID, req);

            // then
            assertThat(result.getFileKey()).startsWith("files/");
        }

        @Test
        @DisplayName("異常系_contentType不正_BAD_REQUEST")
        void 異常系_contentType不正_BAD_REQUEST() {
            // given
            StartMultipartUploadRequest req = new StartMultipartUploadRequest(
                    null, "document.pdf", "application/pdf",
                    10 * 1024 * 1024L, 1, 10 * 1024 * 1024L, "files/");

            // when / then
            assertThatThrownBy(() -> service.startUpload(USER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(BAD_REQUEST));
            then(r2StorageService).should(never()).createMultipartUpload(anyString(), anyString());
        }

        @Test
        @DisplayName("異常系_fileSize超過5TB_BAD_REQUEST")
        void 異常系_fileSize超過5TB_BAD_REQUEST() {
            // given: 5TB + 1バイト
            long oversizedFileSize = 5_497_558_138_880L + 1;
            StartMultipartUploadRequest req = new StartMultipartUploadRequest(
                    null, "huge.mp4", "video/mp4",
                    oversizedFileSize, 10000, 5_497_558L, "timeline/");

            // when / then
            assertThatThrownBy(() -> service.startUpload(USER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(BAD_REQUEST));
            then(r2StorageService).should(never()).createMultipartUpload(anyString(), anyString());
        }

        @Test
        @DisplayName("異常系_targetPrefix不正_BAD_REQUEST")
        void 異常系_targetPrefix不正_BAD_REQUEST() {
            // given
            StartMultipartUploadRequest req = new StartMultipartUploadRequest(
                    null, "video.mp4", "video/mp4",
                    100 * 1024 * 1024L, 5, 20 * 1024 * 1024L, "malicious/");

            // when / then
            assertThatThrownBy(() -> service.startUpload(USER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(BAD_REQUEST));
            then(r2StorageService).should(never()).createMultipartUpload(anyString(), anyString());
        }
    }

    // ==================== getPartUrls ====================

    @Nested
    @DisplayName("getPartUrls")
    class GetPartUrls {

        @Test
        @DisplayName("正常系_パートURL発行成功")
        void 正常系_パートURL発行成功() {
            // given
            MultipartUploadSessionEntity session = buildInProgressSession();
            PartUrlRequest req = new PartUrlRequest("timeline/uuid.mp4", List.of(1, 2, 3));
            given(sessionRepository.findByUploadId(UPLOAD_ID)).willReturn(Optional.of(session));
            given(r2StorageService.createPresignedPartUrls(anyString(), anyString(), anyList(), any()))
                    .willReturn(List.of(
                            new PresignedPartUrl(1, "https://r2.example.com/part1"),
                            new PresignedPartUrl(2, "https://r2.example.com/part2"),
                            new PresignedPartUrl(3, "https://r2.example.com/part3")));

            // when
            PartUrlResponse result = service.getPartUrls(UPLOAD_ID, USER_ID, req);

            // then
            assertThat(result.getPartUrls()).hasSize(3);
            assertThat(result.getExpiresIn()).isEqualTo(600);
            assertThat(result.getPartUrls().get(0).partNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系_セッション未発見_NOT_FOUND")
        void 異常系_セッション未発見_NOT_FOUND() {
            // given
            PartUrlRequest req = new PartUrlRequest("files/uuid.mp4", List.of(1));
            given(sessionRepository.findByUploadId(UPLOAD_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getPartUrls(UPLOAD_ID, USER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(NOT_FOUND));
        }
    }

    // ==================== completeUpload ====================

    @Nested
    @DisplayName("completeUpload")
    class CompleteUpload {

        @Test
        @DisplayName("正常系_アップロード完了")
        void 正常系_アップロード完了() {
            // given
            MultipartUploadSessionEntity session = buildInProgressSession();
            CompleteMultipartRequest req = new CompleteMultipartRequest(
                    "timeline/uuid.mp4",
                    List.of(
                            new CompleteMultipartRequest.PartEtag(1, "etag-001"),
                            new CompleteMultipartRequest.PartEtag(2, "etag-002")));
            given(sessionRepository.findByUploadId(UPLOAD_ID)).willReturn(Optional.of(session));
            given(r2StorageService.getObjectSize("timeline/uuid.mp4")).willReturn(200 * 1024 * 1024L);
            given(sessionRepository.save(any(MultipartUploadSessionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            CompleteMultipartResponse result = service.completeUpload(UPLOAD_ID, USER_ID, req);

            // then
            assertThat(result.getFileKey()).isEqualTo("timeline/uuid.mp4");
            assertThat(result.getFileSize()).isEqualTo(200 * 1024 * 1024L);
            then(r2StorageService).should().completeMultipartUpload(
                    anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("異常系_セッションがCOMPLETED状態_CONFLICT")
        void 異常系_セッションがCOMPLETED状態_CONFLICT() {
            // given
            MultipartUploadSessionEntity session = buildSessionWithStatus("COMPLETED");
            CompleteMultipartRequest req = new CompleteMultipartRequest(
                    "files/uuid.mp4", List.of(new CompleteMultipartRequest.PartEtag(1, "etag-001")));
            given(sessionRepository.findByUploadId(UPLOAD_ID)).willReturn(Optional.of(session));

            // when / then
            assertThatThrownBy(() -> service.completeUpload(UPLOAD_ID, USER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(CONFLICT));
        }
    }

    // ==================== abortUpload ====================

    @Nested
    @DisplayName("abortUpload")
    class AbortUpload {

        @Test
        @DisplayName("正常系_中断成功")
        void 正常系_中断成功() {
            // given
            MultipartUploadSessionEntity session = buildInProgressSession();
            given(sessionRepository.findByUploadId(UPLOAD_ID)).willReturn(Optional.of(session));
            given(sessionRepository.save(any(MultipartUploadSessionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            service.abortUpload(UPLOAD_ID, USER_ID);

            // then
            then(r2StorageService).should().abortMultipartUpload(session.getR2Key(), UPLOAD_ID);
        }
    }

    // ==================== ヘルパーメソッド ====================

    /** IN_PROGRESS 状態のセッションエンティティを生成する */
    private MultipartUploadSessionEntity buildInProgressSession() {
        return buildSessionWithStatus("IN_PROGRESS");
    }

    /** 指定ステータスのセッションエンティティを生成する */
    private MultipartUploadSessionEntity buildSessionWithStatus(String status) {
        return MultipartUploadSessionEntity.builder()
                .uploadId(UPLOAD_ID)
                .r2Key("timeline/uuid.mp4")
                .feature("timeline")
                .scopeType("PERSONAL")
                .scopeId(USER_ID)
                .uploaderId(USER_ID)
                .contentType("video/mp4")
                .status(status)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
    }
}
