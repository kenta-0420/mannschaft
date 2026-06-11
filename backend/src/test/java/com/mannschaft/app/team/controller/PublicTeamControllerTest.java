package com.mannschaft.app.team.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.dto.TeamPublicDetailResponse;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicTeamController} の MockMvc 結合テスト（F15.4 Phase 5-α）。
 *
 * <p>設計書 {@code docs/features/F15.4_phase5_team_public_detail.md §9.1} のステータスコード網羅:</p>
 * <ul>
 *   <li>200: PUBLIC かつ未 archive / 未削除のチーム</li>
 *   <li>404: 不在 / 削除済 / archived / visibility != PUBLIC（一律 404, IDOR 対策）</li>
 *   <li>未ログインで叩ける（Security フィルタ通過）</li>
 *   <li>レスポンス JSON に禁則ワードが含まれない（抑制 DTO 検証）</li>
 * </ul>
 *
 * <p>{@link AutoConfigureMockMvc#addFilters() addFilters=false} で
 * Security フィルタを bypass し、Controller のロジック単体を検証する。
 * permitAll の動作確認は {@code PublicTeamApiRateLimitFilterTest} 側で別途網羅する。
 */
@WebMvcTest(PublicTeamController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicTeamController 結合テスト (F15.4 Phase 5-α)")
class PublicTeamControllerTest {

    /**
     * 抑制 DTO に <strong>絶対に</strong>含まれてはならないフィールド名一覧。
     *
     * <p>抑制 DTO {@link TeamPublicDetailResponse} の仕様変更時、
     * 誤って禁則フィールドを追加してしまった場合に CI で気付けるよう
     * 共有定数として宣言する（設計書 §3.2 / §9.1）。
     *
     * <p>将来 DTO にフィールドを追加する際は、禁則ワードに該当しないか
     * 必ずこの配列と照らし合わせてレビューすること。
     */
    static final String[] FORBIDDEN_FIELDS = {
            // 個人情報（メンバー一覧 / 連絡先）
            "members", "memberList", "users", "userList",
            "email", "emails", "phone", "phoneNumber", "phones",
            "address", "addressLine", "streetAddress",
            // 内部状態 / 楽観ロックトークン（設計書 §3.2）
            "supporterEnabled", "archivedAt", "deletedAt", "version",
            // 関連エンティティ（チャット / 告知 / ファイル / 出席）
            "chatMessages", "chatHistory",
            "announcements", "announcementList",
            "files", "documents", "attachments",
            "attendances", "attendanceRecords",
            // 統計値の生データ（memberCount のみ公開 OK だが、それ以外の統計は伏せる）
            "memberRoster", "userRoster"
    };

    /** URL に使うスラッグ（列挙攻撃対策で URL 用スラッグを採用）*/
    private static final String TEAM_SLUG = "test-team-100";
    /** 内部 BIGINT ID（Service 呼び出しに使用）*/
    private static final Long TEAM_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamService teamService;

    // 以下は WebMvcTest が要求する依存（Security / Proxy 周り）の最小モック注入。
    // 既存 OrganizationTeamSearchControllerTest と同形。
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        // Controller が resolveTeamId を先に呼ぶため、全テストで共通 mock を設定
        given(teamService.resolveTeamId(eq(TEAM_SLUG))).willReturn(TEAM_ID);
    }

    // ════════════════════════════════════════════════════════════
    // 200: 正常系
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /public/teams/{publicId} 200: PUBLIC チームで抑制 DTO が返る")
    void getPublicTeam_public_returns200WithSuppressedDto() throws Exception {
        TeamPublicDetailResponse dto = sampleResponse();
        given(teamService.getPublicTeam(eq(TEAM_ID))).willReturn(dto);

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TEAM_SLUG))
                .andExpect(jsonPath("$.name").value("公開店舗 A"))
                .andExpect(jsonPath("$.nameKana").value("こうかいてんぽえー"))
                .andExpect(jsonPath("$.prefecture").value("東京都"))
                .andExpect(jsonPath("$.city").value("渋谷区"))
                .andExpect(jsonPath("$.template").value("salon"))
                .andExpect(jsonPath("$.iconUrl").value("https://cdn/icon.png"))
                .andExpect(jsonPath("$.bannerUrl").value("https://cdn/banner.png"))
                .andExpect(jsonPath("$.homepageUrl").value("https://example.com"))
                .andExpect(jsonPath("$.philosophy").value("理念テキスト"))
                .andExpect(jsonPath("$.memberCount").value(42))
                .andExpect(jsonPath("$.mapEmbedUrl")
                        .value("https://www.google.com/maps/embed?pb=xxx"));
    }

    // ════════════════════════════════════════════════════════════
    // 404: BusinessException(TEAM_001) → GlobalExceptionHandler で 404
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /public/teams/{publicId} 404: チーム不在")
    void getPublicTeam_notFound_returns404() throws Exception {
        willThrow(new BusinessException(TeamErrorCode.TEAM_001))
                .given(teamService).getPublicTeam(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{publicId} 404: 論理削除済み（TEAM_001 にマッピング）")
    void getPublicTeam_deleted_returns404() throws Exception {
        willThrow(new BusinessException(TeamErrorCode.TEAM_001))
                .given(teamService).getPublicTeam(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{publicId} 404: archived チーム（マスター裁可: 一律 404）")
    void getPublicTeam_archived_returns404() throws Exception {
        willThrow(new BusinessException(TeamErrorCode.TEAM_001))
                .given(teamService).getPublicTeam(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{publicId} 404: visibility=GUESTS_AND_ABOVE（非公開チームは404）")
    void getPublicTeam_guestsAndAbove_returns404() throws Exception {
        willThrow(new BusinessException(TeamErrorCode.TEAM_001))
                .given(teamService).getPublicTeam(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{publicId} 404: visibility=MEMBERS_AND_ABOVE（非公開チームは404）")
    void getPublicTeam_membersAndAbove_returns404() throws Exception {
        willThrow(new BusinessException(TeamErrorCode.TEAM_001))
                .given(teamService).getPublicTeam(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isNotFound());
    }

    // ════════════════════════════════════════════════════════════
    // 未ログイン到達確認
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未ログインでも Controller に到達できる（SecurityContext 空のまま 200）")
    void getPublicTeam_anonymous_canReachController() throws Exception {
        SecurityContextHolder.clearContext();
        given(teamService.getPublicTeam(eq(TEAM_ID))).willReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════
    // 抑制 DTO 禁則フィールド検出（CI 必須・最重要）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("抑制 DTO に禁則ワードが漏洩していないこと（個人情報 / 内部状態 / 楽観ロック token）")
    void publicTeamResponse_doesNotLeakSensitiveFields() throws Exception {
        given(teamService.getPublicTeam(eq(TEAM_ID))).willReturn(sampleResponse());

        MvcResult result = mockMvc.perform(get("/api/v1/public/teams/{publicId}", TEAM_SLUG))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(json)
                    .as("抑制 DTO に禁則ワード '%s' が含まれてはならない（個人情報・内部状態漏洩防止）",
                            forbidden)
                    .doesNotContain(forbidden);
        }
    }

    // ────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────

    private TeamPublicDetailResponse sampleResponse() {
        return new TeamPublicDetailResponse(
                TEAM_SLUG,
                "公開店舗 A",
                "こうかいてんぽえー",
                "ニックネーム1",
                "ニックネーム2",
                "salon",
                "東京都",
                "渋谷区",
                "13",
                "13113",
                "https://cdn/icon.png",
                "https://cdn/banner.png",
                "https://example.com",
                LocalDate.of(2020, 1, 1),
                "DAY",
                "理念テキスト",
                42,
                "https://www.google.com/maps/embed?pb=xxx"
        );
    }
}
