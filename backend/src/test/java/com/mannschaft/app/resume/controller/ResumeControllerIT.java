package com.mannschaft.app.resume.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.resume.dto.ResumeDetailResponse;
import com.mannschaft.app.resume.dto.ResumeExportResponse;
import com.mannschaft.app.resume.dto.ResumeSummaryResponse;
import com.mannschaft.app.resume.service.ResumeExportService;
import com.mannschaft.app.resume.service.ResumeExportService.DocumentType;
import com.mannschaft.app.resume.service.ResumeExportService.OutputFormat;
import com.mannschaft.app.resume.service.ResumePhotoService;
import com.mannschaft.app.resume.service.ResumeService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link ResumeController} の MockMvc 結合テスト（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §12.2 IT-01〜IT-09
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service 層は {@link MockitoBean} で差し替える。
 * 認証フィルタは {@code addFilters = false} で無効化し、
 * {@link SecurityContextHolder} に直接テスト用認証情報をセットする。
 *
 * <p>対象テストケース:
 * <ul>
 *   <li>IT-01: CRUD フル往復（POST → PUT → GET → DELETE）</li>
 *   <li>IT-02: IDOR（他ユーザーの resume → 404）</li>
 *   <li>IT-03: レート制限（export 31 回目で 429）</li>
 *   <li>IT-06: 暗号化カラムラウンドトリップ（HTTPレイヤーの確認のみ）</li>
 *   <li>IT-07: 複製（duplicate で新バージョン作成）</li>
 *   <li>IT-09: 楽観ロック（version 不一致で 409）</li>
 * </ul>
 */
@DisplayName("ResumeController 統合テスト（IT-01〜IT-09）")
@WebMvcTest(ResumeController.class)
@AutoConfigureMockMvc(addFilters = false)
class ResumeControllerIT {

    private static final Long USER_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ResumeController の依存
    @MockitoBean
    private ResumeService resumeService;

    @MockitoBean
    private ResumePhotoService resumePhotoService;

    @MockitoBean
    private ResumeExportService resumeExportService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserRepository userRepository;

    // UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // ProxyInputContextFilter の依存解決用
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // IT-01: CRUD フル往復
    // =========================================================================

    @Nested
    @DisplayName("IT-01: CRUD フル往復")
    class IT01FullCrud {

        @Test
        @DisplayName("POST /resumes → 201 Created が返り、履歴書が作成される")
        void testCreate_returns201() throws Exception {
            UUID resumeId = UUID.randomUUID();
            ResumeDetailResponse response = buildDetailResponse(resumeId, "テスト履歴書");
            given(resumeService.createResume(eq(USER_ID), any())).willReturn(response);

            mockMvc.perform(post("/api/v1/resumes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"テスト履歴書\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(resumeId.toString()))
                    .andExpect(jsonPath("$.data.title").value("テスト履歴書"));
        }

        @Test
        @DisplayName("GET /resumes/{id} → 200 OK が返る")
        void testGet_returns200() throws Exception {
            UUID resumeId = UUID.randomUUID();
            ResumeDetailResponse response = buildDetailResponse(resumeId, "取得テスト");
            given(resumeService.getResume(eq(resumeId), eq(USER_ID))).willReturn(response);

            mockMvc.perform(get("/api/v1/resumes/{id}", resumeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(resumeId.toString()));
        }

        @Test
        @DisplayName("PUT /resumes/{id} → 200 OK が返る（一括保存）")
        void testSave_returns200() throws Exception {
            UUID resumeId = UUID.randomUUID();
            ResumeDetailResponse response = buildDetailResponse(resumeId, "保存テスト");
            given(resumeService.saveResume(eq(resumeId), eq(USER_ID), any())).willReturn(response);

            String body = """
                    {
                      "title": "保存テスト",
                      "eraFormat": "WESTERN",
                      "version": 0,
                      "educations": [],
                      "careers": [],
                      "qualifications": [],
                      "skills": []
                    }
                    """;

            mockMvc.perform(put("/api/v1/resumes/{id}", resumeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(resumeId.toString()));
        }

        @Test
        @DisplayName("DELETE /resumes/{id} → 204 No Content が返る")
        void testDelete_returns204() throws Exception {
            UUID resumeId = UUID.randomUUID();

            mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("GET /resumes → 一覧取得 200 OK が返る")
        void testList_returns200() throws Exception {
            UUID resumeId = UUID.randomUUID();
            ResumeSummaryResponse summary = ResumeSummaryResponse.builder()
                    .id(resumeId.toString())
                    .title("一覧テスト")
                    .hasPhoto(false)
                    .eraFormat("WESTERN")
                    .updatedAt(null)
                    .build();
            given(resumeService.listResumes(USER_ID)).willReturn(List.of(summary));

            mockMvc.perform(get("/api/v1/resumes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].title").value("一覧テスト"));
        }
    }

    // =========================================================================
    // IT-02: IDOR（他ユーザーの resume → 404）
    // =========================================================================

    @Nested
    @DisplayName("IT-02: IDOR — 他ユーザーのリソースは 404 を返す")
    class IT02Idor {

        @Test
        @DisplayName("他ユーザーの resume を GET → 404（RESUME_001）")
        void testIDOR_getOtherUserResume_returns404() throws Exception {
            UUID resumeId = UUID.randomUUID();
            given(resumeService.getResume(eq(resumeId), eq(USER_ID)))
                    .willThrow(new BusinessException(ResumeErrorCode.RESUME_001));

            mockMvc.perform(get("/api/v1/resumes/{id}", resumeId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESUME_001"));
        }

        @Test
        @DisplayName("他ユーザーの resume を PUT → 404（RESUME_001）")
        void testIDOR_putOtherUserResume_returns404() throws Exception {
            UUID resumeId = UUID.randomUUID();
            given(resumeService.saveResume(eq(resumeId), eq(USER_ID), any()))
                    .willThrow(new BusinessException(ResumeErrorCode.RESUME_001));

            String body = """
                    {
                      "title": "他人の履歴書",
                      "eraFormat": "WESTERN",
                      "version": 0,
                      "educations": [],
                      "careers": [],
                      "qualifications": [],
                      "skills": []
                    }
                    """;

            mockMvc.perform(put("/api/v1/resumes/{id}", resumeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESUME_001"));
        }

        @Test
        @DisplayName("他ユーザーの resume を DELETE → 404（RESUME_001）")
        void testIDOR_deleteOtherUserResume_returns404() throws Exception {
            UUID resumeId = UUID.randomUUID();
            willThrow(new BusinessException(ResumeErrorCode.RESUME_001))
                    .given(resumeService).deleteResume(eq(resumeId), eq(USER_ID));

            mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESUME_001"));
        }
    }

    // =========================================================================
    // IT-03: レート制限（export 31 回目で 429）
    // =========================================================================

    @Nested
    @DisplayName("IT-03: レート制限 — export 超過で 429")
    class IT03RateLimit {

        @Test
        @DisplayName("export がレート制限に達すると RESUME_008（429）が返る")
        void testRateLimit_export_returns429() throws Exception {
            UUID resumeId = UUID.randomUUID();
            given(resumeExportService.exportResume(eq(resumeId), eq(USER_ID),
                    any(DocumentType.class), any(OutputFormat.class)))
                    .willThrow(new BusinessException(ResumeErrorCode.RESUME_008));

            mockMvc.perform(get("/api/v1/resumes/{id}/export", resumeId)
                            .param("type", "rirekisho")
                            .param("format", "pdf"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("RESUME_008"));
        }
    }

    // =========================================================================
    // IT-06: 暗号化カラム（HTTPレイヤー確認）
    // =========================================================================

    @Nested
    @DisplayName("IT-06: 暗号化カラムのラウンドトリップ（HTTPレイヤー確認）")
    class IT06EncryptedColumns {

        @Test
        @DisplayName("住所・電話・メールを保存 → GET で復号されて返る（HTTPレイヤーで値が存在すること）")
        void testEncryptedColumnsRoundTrip() throws Exception {
            UUID resumeId = UUID.randomUUID();
            // Service 層から復号済みの値が返ることを前提にモックする
            ResumeDetailResponse response = ResumeDetailResponse.builder()
                    .id(resumeId.toString())
                    .title("暗号化テスト")
                    .eraFormat("WESTERN")
                    .photoUrl(null)
                    .currentAddress("東京都千代田区1-1")
                    .contactPhone("090-1234-5678")
                    .contactEmail("test@example.com")
                    .version(0L)
                    .educations(List.of())
                    .careers(List.of())
                    .qualifications(List.of())
                    .skills(List.of())
                    .build();
            given(resumeService.getResume(eq(resumeId), eq(USER_ID))).willReturn(response);

            mockMvc.perform(get("/api/v1/resumes/{id}", resumeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentAddress").value("東京都千代田区1-1"))
                    .andExpect(jsonPath("$.data.contactPhone").value("090-1234-5678"))
                    .andExpect(jsonPath("$.data.contactEmail").value("test@example.com"));
        }
    }

    // =========================================================================
    // IT-07: 複製
    // =========================================================================

    @Nested
    @DisplayName("IT-07: 複製（duplicate）")
    class IT07Duplicate {

        @Test
        @DisplayName("POST /resumes/{id}/duplicate → 201 Created が返り、新バージョンが作成される")
        void testDuplicate_createsNewVersion() throws Exception {
            UUID sourceId = UUID.randomUUID();
            UUID copyId = UUID.randomUUID();
            ResumeDetailResponse copyResponse = buildDetailResponse(copyId, "元のタイトル (コピー)");
            given(resumeService.duplicateResume(eq(sourceId), eq(USER_ID))).willReturn(copyResponse);

            mockMvc.perform(post("/api/v1/resumes/{id}/duplicate", sourceId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(copyId.toString()))
                    .andExpect(jsonPath("$.data.title").value("元のタイトル (コピー)"));
        }
    }

    // =========================================================================
    // IT-09: 楽観ロック（version 不一致で 409）
    // =========================================================================

    @Nested
    @DisplayName("IT-09: 楽観ロック競合 — 古い version で PUT → 409")
    class IT09OptimisticLock {

        @Test
        @DisplayName("古い version で PUT → RESUME_010（409）が返る")
        void testOptimisticLock_conflict_returns409() throws Exception {
            UUID resumeId = UUID.randomUUID();
            given(resumeService.saveResume(eq(resumeId), eq(USER_ID), any()))
                    .willThrow(new BusinessException(ResumeErrorCode.RESUME_010));

            // version=0（古い）でリクエストを送信
            String body = """
                    {
                      "title": "競合テスト",
                      "eraFormat": "WESTERN",
                      "version": 0,
                      "educations": [],
                      "careers": [],
                      "qualifications": [],
                      "skills": []
                    }
                    """;

            mockMvc.perform(put("/api/v1/resumes/{id}", resumeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("RESUME_010"));
        }
    }

    // =========================================================================
    // テストデータビルダー
    // =========================================================================

    /**
     * テスト用の {@link ResumeDetailResponse} を生成する。
     */
    private ResumeDetailResponse buildDetailResponse(UUID id, String title) {
        return ResumeDetailResponse.builder()
                .id(id.toString())
                .title(title)
                .eraFormat("WESTERN")
                .photoUrl(null)
                .currentAddress(null)
                .currentAddressKana(null)
                .contactAddress(null)
                .contactAddressKana(null)
                .contactPhone(null)
                .contactEmail(null)
                .motivation(null)
                .selfPr(null)
                .personalRequest(null)
                .commuteMinutes(null)
                .dependentsCount(null)
                .hasSpouse(null)
                .spouseSupport(null)
                .careerSummary(null)
                .skillsSummary(null)
                .version(0L)
                .educations(List.of())
                .careers(List.of())
                .qualifications(List.of())
                .skills(List.of())
                .build();
    }
}
