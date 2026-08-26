package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicOrganizationResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicOrganizationQueryService;
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
 * {@link PublicOrganizationController} の MockMvc 結合テスト（F19.1 Phase 1）。
 *
 * <p>設計書 §6.1 / §10.4 のステータスコード網羅:</p>
 * <ul>
 *   <li>200: PUBLIC かつ未 archive / 未削除の組織</li>
 *   <li>404: 不在 / 削除済 / archived / PRIVATE（一律 404, IDOR 対策）</li>
 *   <li>未ログインで叩ける（Security フィルタ通過）</li>
 *   <li>レスポンス JSON に禁則ワードが含まれない（抑制 DTO 検証）</li>
 * </ul>
 */
@WebMvcTest(PublicOrganizationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicOrganizationController 結合テスト (F19.1 Phase 1)")
class PublicOrganizationControllerTest {

    /** PII / 内部状態に該当する禁則フィールド名（CI で漏洩検出）。 */
    static final String[] FORBIDDEN_FIELDS = {
            "members", "memberList", "users", "userList",
            "email", "emails", "phone", "phoneNumber", "phones",
            "firstName", "lastName", "lastNameKana", "firstNameKana",
            "birthday", "passwordHash", "refreshToken",
            "addressLine", "streetAddress",
            "supporterEnabled", "archivedAt", "deletedAt", "version",
            "parentOrganizationId", "profileVisibility",
            "memberRoster", "userRoster"
    };

    private static final Long ORG_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicOrganizationQueryService publicOrganizationQueryService;

    // WebMvcTest が要求する依存の最小モック注入
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
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /public/organizations/{id} 200: PUBLIC 組織で抑制 DTO が返る")
    void getPublicOrganization_public_returns200() throws Exception {
        given(publicOrganizationQueryService.getPublicOrganization(eq(ORG_ID)))
                .willReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORG_ID))
                .andExpect(jsonPath("$.name").value("公開組織 A"))
                .andExpect(jsonPath("$.orgType").value("COMPANY"))
                .andExpect(jsonPath("$.philosophy").value("理念テキスト"))
                .andExpect(jsonPath("$.mapEmbedUrl")
                        .value("https://www.google.com/maps/embed?pb=xxx"));
    }

    @Test
    @DisplayName("GET /public/organizations/{id} 404: 不在組織")
    void getPublicOrganization_notFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicOrganizationQueryService).getPublicOrganization(eq(ORG_ID));

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/organizations/{id} 404: 論理削除済み（PUBLIC_001 にマッピング）")
    void getPublicOrganization_deleted_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicOrganizationQueryService).getPublicOrganization(eq(ORG_ID));

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/organizations/{id} 404: archived 組織（マスター裁可: 一律 404）")
    void getPublicOrganization_archived_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicOrganizationQueryService).getPublicOrganization(eq(ORG_ID));

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/organizations/{id} 404: visibility=PRIVATE")
    void getPublicOrganization_private_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicOrganizationQueryService).getPublicOrganization(eq(ORG_ID));

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未ログインでも Controller に到達できる")
    void getPublicOrganization_anonymous_canReachController() throws Exception {
        SecurityContextHolder.clearContext();
        given(publicOrganizationQueryService.getPublicOrganization(eq(ORG_ID)))
                .willReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("抑制 DTO に禁則ワードが漏洩していないこと（個人情報 / 内部状態 / 楽観ロック token）")
    void publicOrganizationResponse_doesNotLeakSensitiveFields() throws Exception {
        given(publicOrganizationQueryService.getPublicOrganization(eq(ORG_ID)))
                .willReturn(sampleResponse());

        MvcResult result = mockMvc.perform(get("/api/v1/public/organizations/{id}", ORG_ID))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(json)
                    .as("公開組織 DTO に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    private PublicOrganizationResponse sampleResponse() {
        return new PublicOrganizationResponse(
                ORG_ID,
                "公開組織 A",
                "こうかいそしきえー",
                "ニックネーム1",
                "ニックネーム2",
                "COMPANY",
                "東京都",
                "渋谷区",
                "https://cdn/icon.png",
                "https://cdn/banner.png",
                "https://example.com",
                LocalDate.of(2018, 4, 1),
                "DAY",
                "理念テキスト",
                "https://www.google.com/maps/embed?pb=xxx"
        );
    }
}
