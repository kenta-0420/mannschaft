package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.3 {@link SystemAdminSpotlightBatchController} の認可テスト（正本 §16 AC-3.3/3.7）。
 *
 * <p>クラス {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")}（多層防御の 1 層）を AOP プロキシ経由で検証する。
 * 非 SYSTEM_ADMIN は {@link AccessDeniedException}（HTTP では 403 相当）、SYSTEM_ADMIN は 200 を返す。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.3 spotlight バッチ手動トリガー認可テスト")
class SystemAdminSpotlightBatchControllerIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private SystemAdminSpotlightBatchController controller;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester", null, authorities));
    }

    @Test
    @DisplayName("ac3_3: 非 SYSTEM_ADMIN は日次集計トリガーで AccessDeniedException（403）")
    void ac3_3_dailyStats_非管理者は403() {
        authenticateAs("MEMBER");
        assertThatThrownBy(() -> controller.runDailyStats(LocalDate.now().minusDays(1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("ac3_3: SYSTEM_ADMIN は日次集計トリガーで 200")
    void ac3_3_dailyStats_管理者は200() {
        authenticateAs("SYSTEM_ADMIN");
        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.runDailyStats(LocalDate.now().minusDays(1));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData()).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("ac3_7: 非 SYSTEM_ADMIN は月次請求トリガーで AccessDeniedException（403）")
    void ac3_7_invoices_非管理者は403() {
        authenticateAs("MEMBER");
        assertThatThrownBy(() -> controller.runInvoices(YearMonth.now().minusMonths(1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("ac3_7: SYSTEM_ADMIN は月次請求トリガーで 200")
    void ac3_7_invoices_管理者は200() {
        authenticateAs("SYSTEM_ADMIN");
        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.runInvoices(YearMonth.now().minusMonths(1));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData()).containsEntry("status", "COMPLETED");
    }
}
