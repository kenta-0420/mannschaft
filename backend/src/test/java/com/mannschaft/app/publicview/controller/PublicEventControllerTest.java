package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicEventResponse;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicEventQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicEventController} の MockMvc 結合テスト (F19.1 Phase 7)。
 *
 * <p>テスト ID: EVENT-001〜006</p>
 *
 * <p>設計書 §6.2 Phase 7 のステータスコード網羅:</p>
 * <ul>
 *   <li>EVENT-001: 200 — public_events_enabled=true のチームのイベント一覧取得成功</li>
 *   <li>EVENT-002: 404 — public_events_enabled=false / PRIVATE チーム</li>
 *   <li>EVENT-003: 200 — 組織の公開イベント一覧取得成功</li>
 *   <li>EVENT-004: 404 — public_events_enabled=false / PRIVATE 組織</li>
 *   <li>EVENT-005: ページネーションパラメータが正しく適用されること</li>
 *   <li>EVENT-006: レスポンス JSON に PII / 内部情報が含まれないこと</li>
 * </ul>
 */
@WebMvcTest(PublicEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicEventController 結合テスト (F19.1 Phase 7)")
class PublicEventControllerTest {

    /** レスポンスに含まれてはならない PII / 内部情報フィールド名。 */
    private static final String[] FORBIDDEN_FIELDS = {
            "createdBy", "userId", "authorId",
            "preSurveyId", "postSurveyId", "workflowRequestId",
            "deletedAt", "version",
            "authorRealNameSnapshot"
    };

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long EVENT_ID = 7001L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicEventQueryService publicEventQueryService;

    // WebMvcTest で起動される SecurityConfig 関連の Bean
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
    }

    /**
     * EVENT-001: public_events_enabled=true のチームのイベント一覧取得成功。
     */
    @Test
    @DisplayName("EVENT-001: GET /public/teams/{id}/events 200 — public_events_enabled=true のチーム")
    void listTeamEvents_returns200() throws Exception {
        Page<PublicEventResponse> page = new PageImpl<>(
                List.of(sampleTeamEvent()),
                PageRequest.of(0, 20),
                1);
        given(publicEventQueryService.getTeamEvents(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/events", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(EVENT_ID))
                .andExpect(jsonPath("$.content[0].slug").value("autumn-festival-2026"))
                .andExpect(jsonPath("$.content[0].status").value("REGISTRATION_OPEN"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeType").value("TEAM"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeId").value(TEAM_ID))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * EVENT-002: public_events_enabled=false / PRIVATE チームは 404。
     */
    @Test
    @DisplayName("EVENT-002: GET /public/teams/{id}/events 404 — public_events_enabled=false / PRIVATE チーム")
    void listTeamEvents_privateOrFlagOff_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicEventQueryService)
                .getTeamEvents(eq(TEAM_ID), any(Pageable.class));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/events", TEAM_ID))
                .andExpect(status().isNotFound());
    }

    /**
     * EVENT-003: 組織の公開イベント一覧取得成功。
     */
    @Test
    @DisplayName("EVENT-003: GET /public/organizations/{id}/events 200 — public_events_enabled=true の組織")
    void listOrganizationEvents_returns200() throws Exception {
        Page<PublicEventResponse> page = new PageImpl<>(
                List.of(sampleOrgEvent()),
                PageRequest.of(0, 20),
                1);
        given(publicEventQueryService.getOrganizationEvents(eq(ORG_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/events", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(EVENT_ID))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeId").value(ORG_ID));
    }

    /**
     * EVENT-004: public_events_enabled=false / PRIVATE 組織は 404。
     */
    @Test
    @DisplayName("EVENT-004: GET /public/organizations/{id}/events 404 — public_events_enabled=false / PRIVATE 組織")
    void listOrganizationEvents_privateOrFlagOff_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicEventQueryService)
                .getOrganizationEvents(eq(ORG_ID), any(Pageable.class));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/events", ORG_ID))
                .andExpect(status().isNotFound());
    }

    /**
     * EVENT-005: ページネーションパラメータが正しく適用されること。
     */
    @Test
    @DisplayName("EVENT-005: GET /public/teams/{id}/events ページネーション — page=2&size=10")
    void listTeamEvents_pagination_appliesParameters() throws Exception {
        Page<PublicEventResponse> emptyPage = new PageImpl<>(
                List.of(),
                PageRequest.of(2, 10),
                0);
        given(publicEventQueryService.getTeamEvents(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/events", TEAM_ID)
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(10));
    }

    /**
     * EVENT-006: レスポンス JSON に PII / 内部情報が含まれないこと。
     */
    @Test
    @DisplayName("EVENT-006: 公開イベントレスポンス JSON に PII / 内部情報が含まれないこと")
    void listTeamEvents_doesNotLeakSensitiveFields() throws Exception {
        Page<PublicEventResponse> page = new PageImpl<>(
                List.of(sampleTeamEvent()),
                PageRequest.of(0, 20),
                1);
        given(publicEventQueryService.getTeamEvents(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(page);

        String json = mockMvc.perform(get("/api/v1/public/teams/{teamId}/events", TEAM_ID))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String forbidden : FORBIDDEN_FIELDS) {
            org.assertj.core.api.Assertions.assertThat(json)
                    .as("公開イベント JSON に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    // ────────────────────────────────────────────────────────────
    // テストデータファクトリ
    // ────────────────────────────────────────────────────────────

    private PublicEventResponse sampleTeamEvent() {
        return new PublicEventResponse(
                EVENT_ID,
                "autumn-festival-2026",
                "秋の文化祭 2026",
                "毎年恒例の秋の文化祭を開催します。今年も盛りだくさんの内容でお届けします...",
                "REGISTRATION_OPEN",
                "市民ホール",
                "東京都渋谷区1-1-1",
                200,
                45,
                PublicScopeRef.ofTeam(TEAM_ID, "サンプルチーム"),
                OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    private PublicEventResponse sampleOrgEvent() {
        return new PublicEventResponse(
                EVENT_ID,
                "org-conference-2026",
                "年次総会 2026",
                "組織の年次総会を開催します...",
                "PUBLISHED",
                "組織本部",
                "東京都千代田区1-1-1",
                null,
                0,
                PublicScopeRef.ofOrganization(ORG_ID, "サンプル組織"),
                OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC));
    }
}
