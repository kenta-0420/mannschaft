package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.dto.ImageUploadUrlRequest;
import com.mannschaft.app.timeline.dto.ImageUploadUrlResponse;
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
 * {@link TimelineImageAttachmentService} の単体テスト。
 *
 * <p>presign 時のクォータチェックと resolveScope ロジックも検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineImageAttachmentService 単体テスト")
class TimelineImageAttachmentServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private StorageQuotaService storageQuotaService;

    @Mock
    private TimelineAttachmentAccessGuard accessGuard;

    @InjectMocks
    private TimelineImageAttachmentService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 5L;

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("jpeg_正常系_R2Keyの形式が正しい")
        void jpeg_正常系_R2Keyの形式が正しい() {
            // given
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/jpeg", "TEAM", TEAM_ID);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            ImageUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getUploadUrl()).isEqualTo("https://example.com/upload");
            assertThat(result.getFileKey()).startsWith("timeline/TEAM/10/images/");
            assertThat(result.getFileKey()).endsWith(".jpg");
            assertThat(result.getExpiresInSeconds()).isEqualTo(900);
            // checkQuota が TEAM スコープで呼ばれる
            then(storageQuotaService).should().checkQuota(StorageScopeType.TEAM, TEAM_ID, 0L);
        }

        @Test
        @DisplayName("png_正常系_拡張子がpngになる")
        void png_正常系_拡張子がpngになる() {
            // given
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/png", "ORGANIZATION", ORG_ID);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/png"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            ImageUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getFileKey()).endsWith(".png");
            // checkQuota が ORGANIZATION スコープで呼ばれる
            then(storageQuotaService).should().checkQuota(StorageScopeType.ORGANIZATION, ORG_ID, 0L);
        }

        @Test
        @DisplayName("webp_正常系_PUBLIC_スコープはPERSONALフォールバック")
        void webp_正常系_PUBLICスコープはPERSONALフォールバック() {
            // given
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/webp", "PUBLIC", null);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/webp"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            ImageUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getFileKey()).startsWith("timeline/PUBLIC/0/images/");
            assertThat(result.getFileKey()).endsWith(".webp");
            // PUBLIC はフォールバックで PERSONAL スコープ
            then(storageQuotaService).should().checkQuota(StorageScopeType.PERSONAL, USER_ID, 0L);
        }

        @Test
        @DisplayName("非画像MIME_例外が発生する")
        void 非画像MIME_例外が発生する() {
            // given
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("video/mp4", "TEAM", TEAM_ID);

            // when / then
            assertThatThrownBy(() -> service.generateUploadUrl(req, USER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            // R2 Presigned URL は発行されない
            then(r2StorageService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("クォータ超過_409がスローされR2PresignedURLは発行されない")
        void クォータ超過_409() {
            // given
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/jpeg", "TEAM", TEAM_ID);
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
            TimelineImageAttachmentService.ScopeResolution scope =
                    service.resolveScope("TEAM", TEAM_ID, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ判定")
        void resolveScope_ORGANIZATION() {
            TimelineImageAttachmentService.ScopeResolution scope =
                    service.resolveScope("ORGANIZATION", ORG_ID, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.ORGANIZATION);
            assertThat(scope.scopeId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("PUBLIC → PERSONAL フォールバック")
        void resolveScope_PUBLIC_PERSONAL() {
            TimelineImageAttachmentService.ScopeResolution scope =
                    service.resolveScope("PUBLIC", 0L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("不明なスコープ → PERSONAL フォールバック")
        void resolveScope_UNKNOWN_PERSONAL() {
            TimelineImageAttachmentService.ScopeResolution scope =
                    service.resolveScope("UNKNOWN_SCOPE", 0L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }
    }
}
