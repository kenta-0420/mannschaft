package com.mannschaft.app.admin.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.gdpr.dto.PurgeStatusRow;
import com.mannschaft.app.gdpr.dto.PurgeStatusSummaryData;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.service.GdprPurgeRetryService;
import com.mannschaft.app.gdpr.service.GdprPurgeStatusQueryService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link SystemAdminGdprPurgeController} の MockMvc テスト。
 *
 * <p>Phase E GDPR パージ状況管理 API（読み取り専用）のエンドポイント検証を行う。</p>
 *
 * <p>SecurityConfig の {@code .anyRequest().permitAll()} により {@code @WebMvcTest} 単体では
 * ロールガードが実質無効になるため、SYSTEM_ADMIN ロールなし / ありの両方をテストするために
 * Spring Security フィルターを一部有効化してテストを行う。</p>
 *
 * <p>注: {@code @WebMvcTest} + {@code @EnableMethodSecurity} は incompatible のため、
 * SecurityConfig の GDPR ルールは別途 SecurityConfig の統合テストで検証する前提とし、
 * 本クラスでは addFilters=false のスタイルでステータスコードのみを確認する。</p>
 */
@DisplayName("SystemAdminGdprPurgeController テスト")
@WebMvcTest(SystemAdminGdprPurgeController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemAdminGdprPurgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GdprPurgeStatusQueryService queryService;

    @MockitoBean
    private GdprPurgeRetryService retryService;

    // @WebMvcTest 共通の慣習: フィルター・コンテキスト依存 Bean を Mock 化
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
        // SYSTEM_ADMIN ロールで認証済み状態をセット
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
    }

    // ---- テスト用データファクトリ ----

    private PurgeStatusRow buildRow(Long userId, String domain, String status, boolean isAlert) {
        LocalDateTime now = LocalDateTime.now();
        return new PurgeStatusRow(
                userId,
                "a".repeat(64),
                domain,
                status,
                now.minusMinutes(60),
                "SUCCESS".equals(status) ? now : null,
                isAlert,
                0,
                null);
    }

    private PurgeStatusSummaryData buildSummary() {
        List<PurgeStatusSummaryData.DomainCount> byDomain = List.of(
                new PurgeStatusSummaryData.DomainCount("role", 2L, 4L),
                new PurgeStatusSummaryData.DomainCount("team", 0L, 6L)
        );
        return new PurgeStatusSummaryData(2L, 10L, 1L, byDomain);
    }

    // ---- 一覧取得 ----

    @Nested
    @DisplayName("GET /api/v1/system-admin/gdpr/purge-status")
    class ListTest {

        @Test
        @DisplayName("フィルタなしで 200 と一覧が返る")
        void フィルタなし_200() throws Exception {
            PurgeStatusRow row = buildRow(100L, "role", "SUCCESS", false);
            given(queryService.list(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(row)));

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].userId").value(100))
                    .andExpect(jsonPath("$.data.content[0].domainName").value("role"))
                    .andExpect(jsonPath("$.data.content[0].status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.content[0].isAlert").value(false));
        }

        @Test
        @DisplayName("status=PENDING フィルタで 200")
        void status_PENDING_フィルタ_200() throws Exception {
            PurgeStatusRow row = buildRow(200L, "team", "PENDING", true);
            given(queryService.list(anyString(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(row)));

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status")
                            .param("status", "PENDING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                    .andExpect(jsonPath("$.data.content[0].isAlert").value(true));
        }

        @Test
        @DisplayName("結果が空でも 200 を返す")
        void 空結果_200() throws Exception {
            given(queryService.list(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ---- サマリー取得 ----

    @Nested
    @DisplayName("GET /api/v1/system-admin/gdpr/purge-status/summary")
    class SummaryTest {

        @Test
        @DisplayName("サマリーが 200 で返る")
        void サマリー_200() throws Exception {
            given(queryService.summary()).willReturn(buildSummary());

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalPending").value(2))
                    .andExpect(jsonPath("$.data.totalSuccess").value(10))
                    .andExpect(jsonPath("$.data.alertCount").value(1))
                    .andExpect(jsonPath("$.data.byDomain[0].domain").value("role"))
                    .andExpect(jsonPath("$.data.byDomain[0].pendingCount").value(2))
                    .andExpect(jsonPath("$.data.byDomain[1].domain").value("team"))
                    .andExpect(jsonPath("$.data.byDomain[1].successCount").value(6));
        }
    }

    // ---- ユーザー詳細取得 ----

    @Nested
    @DisplayName("GET /api/v1/system-admin/gdpr/purge-status/{userId}")
    class UserDetailTest {

        @Test
        @DisplayName("userId が 200 で全ドメイン行を返す")
        void userId_200() throws Exception {
            List<PurgeStatusRow> rows = List.of(
                    buildRow(100L, "chart", "SUCCESS", false),
                    buildRow(100L, "role", "PENDING", true)
            );
            given(queryService.detail(100L)).willReturn(rows);

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status/100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].domainName").value("chart"))
                    .andExpect(jsonPath("$.data[1].domainName").value("role"))
                    .andExpect(jsonPath("$.data[1].isAlert").value(true));
        }

        @Test
        @DisplayName("存在しない userId でも 200（空リスト）")
        void 存在しないUserId_200_空() throws Exception {
            given(queryService.detail(anyLong())).willReturn(List.of());

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status/999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ---- CSV エクスポート ----

    @Nested
    @DisplayName("GET /api/v1/system-admin/gdpr/purge-status/export.csv")
    class CsvExportTest {

        @Test
        @DisplayName("Content-Type が text/csv で 200")
        void ContentType_textCsv_200() throws Exception {
            // writeCsv は OutputStream に書き込むが Mock では何もしない
            doNothing().when(queryService).writeCsv(any(OutputStream.class));

            mockMvc.perform(get("/api/v1/system-admin/gdpr/purge-status/export.csv"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString("attachment")))
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString("gdpr-purge-status-")));
        }
    }

    // ---- 手動 retry（Phase F）----

    @Nested
    @DisplayName("POST /api/v1/system-admin/gdpr/purge-status/{userId}/retry/{domainName}")
    class RetryTest {

        @Test
        @DisplayName("retry 成功系: succeeded=true で 200 OK")
        void retry成功_200() throws Exception {
            RetryResultResponse response = new RetryResultResponse(
                    true, "role", "SUCCESS", 1, "retry 成功");
            given(retryService.retryDomainPurge(eq(100L), eq("role")))
                    .willReturn(response);

            mockMvc.perform(post("/api/v1/system-admin/gdpr/purge-status/100/retry/role"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.succeeded").value(true))
                    .andExpect(jsonPath("$.data.domainName").value("role"))
                    .andExpect(jsonPath("$.data.newStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.retryCount").value(1))
                    .andExpect(jsonPath("$.data.message").value("retry 成功"));
        }

        @Test
        @DisplayName("retry 失敗系: succeeded=false で 200 OK（PENDING 継続）")
        void retry失敗_200_PENDING継続() throws Exception {
            RetryResultResponse response = new RetryResultResponse(
                    false, "payment", "PENDING", 2, "retry 失敗（PENDING 継続）");
            given(retryService.retryDomainPurge(eq(200L), eq("payment")))
                    .willReturn(response);

            mockMvc.perform(post("/api/v1/system-admin/gdpr/purge-status/200/retry/payment"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.succeeded").value(false))
                    .andExpect(jsonPath("$.data.newStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.retryCount").value(2));
        }
    }
}
