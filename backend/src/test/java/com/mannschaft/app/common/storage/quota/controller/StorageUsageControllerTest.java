package com.mannschaft.app.common.storage.quota.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.common.storage.quota.StorageUsageQueryService;
import com.mannschaft.app.common.storage.quota.dto.StorageScopeUsage;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StorageUsageController} の MockMvc 契約テスト（試練先行）。
 *
 * <ul>
 *   <li>AC-1: 未認証は 401</li>
 *   <li>AC-2: 200 で本人 PERSONAL + 所属チーム + 所属組織を返す</li>
 *   <li>AC-3: レスポンス要素の全フィールドを網羅する</li>
 *   <li>AC-5: サーバーが列挙した所属スコープのみが含まれる（クライアントは scopeId を渡さない）</li>
 *   <li>AC-6: PERSONAL の scopeId は本人の userId</li>
 * </ul>
 */
@WebMvcTest(StorageUsageController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("StorageUsageController 契約テスト（試練）")
class StorageUsageControllerTest {

    private static final Long USER_ID = 100L;
    private static final long GB = 1024L * 1024L * 1024L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageUsageQueryService storageUsageQueryService;

    // @WebMvcTest スライスの web 層（フィルタ・i18n・@PreAuthorize ガード）依存解決用のモック群。
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @Test
    @DisplayName("AC-1: 未認証は 401")
    void unauthenticated_returns401() throws Exception {
        // 認証情報をセットしない → SecurityUtils.getCurrentUserId が COMMON_000 を投げ 401。
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/me/storage/usage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-2/AC-3/AC-6: 200 で PERSONAL+TEAM+ORG を全フィールド付きで返す")
    void authenticated_returnsAllScopesWithAllFields() throws Exception {
        authenticate();
        StorageScopeUsage personal = new StorageScopeUsage(
                "PERSONAL", USER_ID, "個人", null, 500L, 3, 1 * GB, 2 * GB, 0.0000466);
        StorageScopeUsage team = new StorageScopeUsage(
                "TEAM", 10L, "サッカー部", "soccer", 5 * GB, 12, 10 * GB, null, 50.0);
        StorageScopeUsage org = new StorageScopeUsage(
                "ORGANIZATION", 20L, "県協会", "kyokai", 0L, 0, 100 * GB, 200 * GB, 0.0);
        given(storageUsageQueryService.getMyStorageUsage(USER_ID))
                .willReturn(List.of(personal, team, org));

        mockMvc.perform(get("/api/v1/me/storage/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                // AC-6: PERSONAL は本人 userId
                .andExpect(jsonPath("$[0].scopeType").value("PERSONAL"))
                .andExpect(jsonPath("$[0].scopeId").value(USER_ID))
                .andExpect(jsonPath("$[0].slug").doesNotExist())
                // AC-3: 全フィールド網羅（TEAM 要素で検証）
                .andExpect(jsonPath("$[1].scopeType").value("TEAM"))
                .andExpect(jsonPath("$[1].scopeId").value(10))
                .andExpect(jsonPath("$[1].scopeName").value("サッカー部"))
                .andExpect(jsonPath("$[1].slug").value("soccer"))
                .andExpect(jsonPath("$[1].usedBytes").value(5 * GB))
                .andExpect(jsonPath("$[1].fileCount").value(12))
                .andExpect(jsonPath("$[1].includedBytes").value(10 * GB))
                .andExpect(jsonPath("$[1].maxBytes").doesNotExist())
                .andExpect(jsonPath("$[1].usagePercent").value(50.0))
                .andExpect(jsonPath("$[2].scopeType").value("ORGANIZATION"))
                .andExpect(jsonPath("$[2].maxBytes").value(200 * GB));
    }

    @Test
    @DisplayName("AC-5: サーバーが列挙した所属スコープのみ含まれる（非所属 ID は混入しない）")
    void onlyServerEnumeratedScopesAreReturned() throws Exception {
        authenticate();
        // サーバーは本人の所属（PERSONAL + team 10 + team 11）だけを返す。team 99 は非所属。
        given(storageUsageQueryService.getMyStorageUsage(USER_ID)).willReturn(List.of(
                new StorageScopeUsage("PERSONAL", USER_ID, "個人", null, 0L, 0, 1 * GB, null, 0.0),
                new StorageScopeUsage("TEAM", 10L, "A", "a", 0L, 0, 1 * GB, null, 0.0),
                new StorageScopeUsage("TEAM", 11L, "B", "b", 0L, 0, 1 * GB, null, 0.0)));

        mockMvc.perform(get("/api/v1/me/storage/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.scopeType=='TEAM' && @.scopeId==10)]").exists())
                .andExpect(jsonPath("$[?(@.scopeType=='TEAM' && @.scopeId==11)]").exists())
                // 非所属チーム 99 は含まれない
                .andExpect(jsonPath("$[?(@.scopeId==99)]").doesNotExist());
    }
}
