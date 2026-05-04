package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
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
 * {@link TimelineVideoAttachmentService} の単体テスト。
 *
 * <p>F13 Phase 4-γ: presign 時のクォータチェックと resolveScope ロジックも検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineVideoAttachmentService 単体テスト")
class TimelineVideoAttachmentServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private StorageQuotaService storageQuotaService;

    @InjectMocks
    private TimelineVideoAttachmentService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 5L;

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("mp4_正常系_R2Keyの形式が正しい")
        void mp4_正常系_R2Keyの形式が正しい() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/mp4", "TEAM", TEAM_ID);
            given(r2StorageService.generateUploadUrl(anyString(), eq("video/mp4"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            VideoUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getUploadUrl()).isEqualTo("https://example.com/upload");
            assertThat(result.getFileKey()).startsWith("timeline/TEAM/10/tmp/");
            assertThat(result.getFileKey()).endsWith(".mp4");
            assertThat(result.getExpiresInSeconds()).isEqualTo(900L);
            // F13 Phase 4-γ: checkQuota が TEAM スコープで呼ばれる
            then(storageQuotaService).should().checkQuota(StorageScopeType.TEAM, TEAM_ID, 0L);
        }

        @Test
        @DisplayName("webm_正常系_拡張子がwebmになる")
        void webm_正常系_拡張子がwebmになる() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/webm", "ORGANIZATION", ORG_ID);
            given(r2StorageService.generateUploadUrl(anyString(), eq("video/webm"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            VideoUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getFileKey()).endsWith(".webm");
            // F13 Phase 4-γ: checkQuota が ORGANIZATION スコープで呼ばれる
            then(storageQuotaService).should().checkQuota(StorageScopeType.ORGANIZATION, ORG_ID, 0L);
        }

        @Test
        @DisplayName("quicktime_正常系_拡張子がmovになる（PUBLIC スコープは PERSONAL フォールバック）")
        void quicktime_正常系_拡張子がmovになる() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/quicktime", "PUBLIC", null);
            given(r2StorageService.generateUploadUrl(anyString(), eq("video/quicktime"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            VideoUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getFileKey()).startsWith("timeline/PUBLIC/0/tmp/");
            assertThat(result.getFileKey()).endsWith(".mov");
            // F13 Phase 4-γ: PUBLIC はフォールバックで PERSONAL スコープ
            then(storageQuotaService).should().checkQuota(StorageScopeType.PERSONAL, USER_ID, 0L);
        }

        @Test
        @DisplayName("非対応MIME_例外が発生する")
        void 非対応MIME_例外が発生する() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/avi", "TEAM", 1L);

            // when / then
            assertThatThrownBy(() -> service.generateUploadUrl(req, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非対応 MIME タイプ");
        }

        @Test
        @DisplayName("F13_クォータ超過: 409 がスローされ R2 Presigned URL は発行されない")
        void F13_クォータ超過_409() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/mp4", "TEAM", TEAM_ID);
            willThrow(new StorageQuotaExceededException(
                    StorageScopeType.TEAM, TEAM_ID, 0L,
                    5L * 1024 * 1024 * 1024L, 5L * 1024 * 1024 * 1024L))
                    .given(storageQuotaService)
                    .checkQuota(eq(StorageScopeType.TEAM), eq(TEAM_ID), anyLong());

            // when / then
            assertThatThrownBy(() -> service.generateUploadUrl(req, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
            // R2 Presigned URL は発行されない
            then(r2StorageService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("resolveScope")
    class ResolveScopeTest {

        @Test
        @DisplayName("TEAM スコープ判定")
        void resolveScope_TEAM() {
            TimelineVideoAttachmentService.ScopeResolution scope =
                    service.resolveScope("TEAM", TEAM_ID, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ判定")
        void resolveScope_ORGANIZATION() {
            TimelineVideoAttachmentService.ScopeResolution scope =
                    service.resolveScope("ORGANIZATION", ORG_ID, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.ORGANIZATION);
            assertThat(scope.scopeId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("PUBLIC → PERSONAL フォールバック")
        void resolveScope_PUBLIC_PERSONAL() {
            TimelineVideoAttachmentService.ScopeResolution scope =
                    service.resolveScope("PUBLIC", 0L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("不明なスコープ → PERSONAL フォールバック")
        void resolveScope_UNKNOWN_PERSONAL() {
            TimelineVideoAttachmentService.ScopeResolution scope =
                    service.resolveScope("UNKNOWN_SCOPE", 0L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }
    }
}
