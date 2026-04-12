package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link TimelineVideoAttachmentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineVideoAttachmentService 単体テスト")
class TimelineVideoAttachmentServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @InjectMocks
    private TimelineVideoAttachmentService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("mp4_正常系_R2Keyの形式が正しい")
        void mp4_正常系_R2Keyの形式が正しい() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/mp4", "TEAM", 10L);
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
        }

        @Test
        @DisplayName("webm_正常系_拡張子がwebmになる")
        void webm_正常系_拡張子がwebmになる() {
            // given
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/webm", "ORGANIZATION", 5L);
            given(r2StorageService.generateUploadUrl(anyString(), eq("video/webm"), any(Duration.class)))
                    .willAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        return new PresignedUploadResult("https://example.com/upload", key, 900L);
                    });

            // when
            VideoUploadUrlResponse result = service.generateUploadUrl(req, USER_ID);

            // then
            assertThat(result.getFileKey()).endsWith(".webm");
        }

        @Test
        @DisplayName("quicktime_正常系_拡張子がmovになる")
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
    }
}
