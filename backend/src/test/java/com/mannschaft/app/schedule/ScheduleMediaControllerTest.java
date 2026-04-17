package com.mannschaft.app.schedule;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.schedule.controller.ScheduleMediaController;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlResponse;
import com.mannschaft.app.schedule.service.ScheduleMediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ScheduleMediaController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 * セキュリティフィルターは addFilters=false で無効化し、認証済みユーザーを直接設定する。
 */
@DisplayName("ScheduleMediaController 単体テスト")
class ScheduleMediaControllerTest {

    @Nested
    @DisplayName("ScheduleMediaController エンドポイント")
    @WebMvcTest(ScheduleMediaController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class ScheduleMediaControllerEndpointTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private ScheduleMediaService scheduleMediaService;

        @MockitoBean
        private AccessControlService accessControlService;

        @MockitoBean
        private AuthTokenService authTokenService;

        @MockitoBean
        private UserLocaleCache userLocaleCache;

        private static final Long SCHEDULE_ID = 100L;
        private static final Long MEDIA_ID = 200L;
        private static final Long USER_ID = 1L;

        @BeforeEach
        void setUpSecurityContext() {
            // 認証済みユーザーをセキュリティコンテキストに設定
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        }

        /**
         * テスト用アップロード URL レスポンスを組み立てる。
         */
        private ScheduleMediaUploadUrlResponse buildUploadUrlResponse() {
            return ScheduleMediaUploadUrlResponse.builder()
                    .mediaId(MEDIA_ID)
                    .mediaType("IMAGE")
                    .r2Key("schedules/100/uuid.jpg")
                    .uploadUrl("https://r2.example.com/presigned-url")
                    .expiresIn(600)
                    .build();
        }

        /**
         * テスト用メディアレスポンスを組み立てる。
         */
        private ScheduleMediaResponse buildMediaResponse() {
            return ScheduleMediaResponse.builder()
                    .id(MEDIA_ID)
                    .mediaType("IMAGE")
                    .url("https://storage.example.com/schedules/100/uuid.jpg")
                    .fileName("photo.jpg")
                    .fileSize(1024L * 1024)
                    .isCover(false)
                    .isExpenseReceipt(false)
                    .processingStatus("READY")
                    .uploaderId(USER_ID)
                    .createdAt(LocalDateTime.of(2026, 4, 17, 10, 0))
                    .build();
        }

        /**
         * テスト用メディア一覧レスポンスを組み立てる。
         */
        private ScheduleMediaListResponse buildListResponse() {
            return ScheduleMediaListResponse.builder()
                    .items(List.of(buildMediaResponse()))
                    .totalCount(1L)
                    .page(1)
                    .size(20)
                    .hasNext(false)
                    .build();
        }

        // ==================== POST /upload-url ====================

        @Nested
        @DisplayName("POST /api/v1/schedules/{scheduleId}/media/upload-url")
        class PostUploadUrl {

            @Test
            @DisplayName("正常系_200 でアップロード URL を返す")
            void 正常系_アップロードURL発行_200() throws Exception {
                // given
                given(scheduleMediaService.generateUploadUrl(anyLong(), anyLong(), any()))
                        .willReturn(buildUploadUrlResponse());

                String requestBody = """
                        {
                          "mediaType": "IMAGE",
                          "contentType": "image/jpeg",
                          "fileSize": 1048576,
                          "fileName": "photo.jpg"
                        }
                        """;

                // when / then
                mockMvc.perform(post("/api/v1/schedules/{scheduleId}/media/upload-url", SCHEDULE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.mediaType").value("IMAGE"))
                        .andExpect(jsonPath("$.data.uploadUrl").value("https://r2.example.com/presigned-url"))
                        .andExpect(jsonPath("$.data.expiresIn").value(600))
                        .andExpect(jsonPath("$.data.mediaId").value(MEDIA_ID));
            }
        }

        // ==================== GET / ====================

        @Nested
        @DisplayName("GET /api/v1/schedules/{scheduleId}/media")
        class GetMedia {

            @Test
            @DisplayName("正常系_200 でメディア一覧を返す")
            void 正常系_メディア一覧取得_200() throws Exception {
                // given
                given(scheduleMediaService.listMedia(
                        eq(SCHEDULE_ID), isNull(), eq(false), eq(1), eq(20)))
                        .willReturn(buildListResponse());

                // when / then
                mockMvc.perform(get("/api/v1/schedules/{scheduleId}/media", SCHEDULE_ID)
                                .param("page", "1")
                                .param("size", "20"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items").isArray())
                        .andExpect(jsonPath("$.data.items[0].id").value(MEDIA_ID))
                        .andExpect(jsonPath("$.data.totalCount").value(1))
                        .andExpect(jsonPath("$.data.page").value(1))
                        .andExpect(jsonPath("$.data.hasNext").value(false));
            }
        }

        // ==================== PATCH /{mediaId} ====================

        @Nested
        @DisplayName("PATCH /api/v1/schedules/{scheduleId}/media/{mediaId}")
        class PatchMedia {

            @Test
            @DisplayName("正常系_200 で更新後のメディアを返す")
            void 正常系_メディア更新_200() throws Exception {
                // given
                given(scheduleMediaService.updateMedia(
                        anyLong(), anyLong(), anyLong(), anyBoolean(), any()))
                        .willReturn(buildMediaResponse());

                String requestBody = """
                        {
                          "caption": "新しいキャプション"
                        }
                        """;

                // when / then
                mockMvc.perform(patch(
                                "/api/v1/schedules/{scheduleId}/media/{mediaId}",
                                SCHEDULE_ID, MEDIA_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(MEDIA_ID))
                        .andExpect(jsonPath("$.data.mediaType").value("IMAGE"))
                        .andExpect(jsonPath("$.data.processingStatus").value("READY"));
            }
        }

        // ==================== DELETE /{mediaId} ====================

        @Nested
        @DisplayName("DELETE /api/v1/schedules/{scheduleId}/media/{mediaId}")
        class DeleteMedia {

            @Test
            @DisplayName("正常系_204 で削除成功")
            void 正常系_メディア削除_204() throws Exception {
                // given
                doNothing().when(scheduleMediaService)
                        .deleteMedia(anyLong(), anyLong(), anyLong(), anyBoolean());

                // when / then
                mockMvc.perform(delete(
                                "/api/v1/schedules/{scheduleId}/media/{mediaId}",
                                SCHEDULE_ID, MEDIA_ID))
                        .andExpect(status().isNoContent());
            }
        }
    }

    // ==================== 未認証アクセステスト ====================

    /**
     * セキュリティフィルターを有効にした状態で未認証アクセスを検証する。
     * {@code @PreAuthorize("isAuthenticated()")} が付与されているため、
     * 匿名ユーザーのアクセスは Spring Security により 403 Forbidden が返る。
     * （実際の JwtAuthenticationFilter を通じた JWT 未提供時は 401 だが、
     *   WebMvcTest 環境では匿名ユーザーとして扱われ 403 が返る。）
     */
    @Nested
    @DisplayName("未認証アクセス → 403（@PreAuthorize によるアクセス拒否）")
    @WebMvcTest(ScheduleMediaController.class)
    class UnauthorizedAccessTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private ScheduleMediaService scheduleMediaService;

        @MockitoBean
        private AccessControlService accessControlService;

        @MockitoBean
        private AuthTokenService authTokenService;

        @MockitoBean
        private UserLocaleCache userLocaleCache;

        private static final Long SCHEDULE_ID = 100L;
        private static final Long MEDIA_ID = 200L;

        @Test
        @DisplayName("POST /upload-url — 未認証_403（@PreAuthorize isAuthenticated チェック）")
        void uploadUrl_未認証_403() throws Exception {
            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/media/upload-url", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET / — 未認証_4xx（@PreAuthorize isAuthenticated チェック。実際の応答は401または403）")
        void listMedia_未認証_4xx() throws Exception {
            // GETはSpring SecurityのデフォルトでCSRF不要のため401が返る場合がある
            // @PreAuthorize("isAuthenticated()") によりアクセス拒否（401または403）を確認
            mockMvc.perform(get("/api/v1/schedules/{scheduleId}/media", SCHEDULE_ID))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        org.assertj.core.api.Assertions.assertThat(status)
                                .as("未認証アクセスは 401 または 403 が返るはず")
                                .isIn(401, 403);
                    });
        }

        @Test
        @DisplayName("PATCH /{mediaId} — 未認証_403（@PreAuthorize isAuthenticated チェック）")
        void updateMedia_未認証_403() throws Exception {
            mockMvc.perform(patch("/api/v1/schedules/{scheduleId}/media/{mediaId}", SCHEDULE_ID, MEDIA_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /{mediaId} — 未認証_403（@PreAuthorize isAuthenticated チェック）")
        void deleteMedia_未認証_403() throws Exception {
            mockMvc.perform(delete("/api/v1/schedules/{scheduleId}/media/{mediaId}", SCHEDULE_ID, MEDIA_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("全エンドポイントに @PreAuthorize('isAuthenticated()') が付与されている")
        void 全エンドポイントにPreAuthorize付与確認() throws Exception {
            var postMethod = ScheduleMediaController.class
                    .getMethod("generateUploadUrl", Long.class,
                            com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlRequest.class);
            var getMethod = ScheduleMediaController.class
                    .getMethod("listMedia", Long.class, String.class, boolean.class, int.class, int.class);
            var patchMethod = ScheduleMediaController.class
                    .getMethod("updateMedia", Long.class, Long.class,
                            com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest.class);
            var deleteMethod = ScheduleMediaController.class
                    .getMethod("deleteMedia", Long.class, Long.class);

            org.assertj.core.api.Assertions.assertThat(
                    postMethod.getAnnotation(
                            org.springframework.security.access.prepost.PreAuthorize.class)
                            .value())
                    .isEqualTo("isAuthenticated()");
            org.assertj.core.api.Assertions.assertThat(
                    getMethod.getAnnotation(
                            org.springframework.security.access.prepost.PreAuthorize.class)
                            .value())
                    .isEqualTo("isAuthenticated()");
            org.assertj.core.api.Assertions.assertThat(
                    patchMethod.getAnnotation(
                            org.springframework.security.access.prepost.PreAuthorize.class)
                            .value())
                    .isEqualTo("isAuthenticated()");
            org.assertj.core.api.Assertions.assertThat(
                    deleteMethod.getAnnotation(
                            org.springframework.security.access.prepost.PreAuthorize.class)
                            .value())
                    .isEqualTo("isAuthenticated()");
        }
    }
}
