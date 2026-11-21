package com.mannschaft.app.auth;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth ログイン API（実 Controller → 実 Service → AFTER_COMMIT・@Async の監査リスナー → 実 MySQL）の
 * 監査ログ契約テスト（CMP-261020-1530）。
 *
 * <p><b>保証する内容</b>: {@code POST /api/v1/auth/oauth/{provider}} で送った IP と User-Agent が
 * {@code audit_logs.ip_address / user_agent} にそのまま記録される（{@code LoginSuccessEvent} の引数順の取り違えにより
 * ip_address に "OAUTH_GOOGLE"、user_agent に IP が入っていた不具合の回帰固定）。新規ユーザー登録経路と
 * 既存連携経路の両方を通す。</p>
 *
 * <p><b>外部プロバイダ</b>: Google の token / userinfo は統合試験の基底が設定するループバック HTTP サーバで偽装する。</p>
 *
 * <p><b>context 分割について</b>: 監査ログに範囲を絞るため {@code addFilters=false} で実行し、試験終了時に閉じる。
 * 本テストは {@code @Transactional} にしない
 * （AFTER_COMMIT リスナーはコミットされないと動かないため）。作成した行は {@code @AfterEach} で自分の分だけ削除する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("OAuth ログイン監査ログ契約テスト")
class AuthOAuthLoginAuditContractIT extends AbstractMySqlIntegrationTest {

    private static final String SEND_IP = "203.0.113.45";
    private static final String UA_FIRST = "OAuthAuditIT/1.0 (first)";
    private static final String UA_SECOND = "OAuthAuditIT/1.0 (second)";

    private static final String SUB = "it-sub-" + UUID.randomUUID();
    private static final String EMAIL = "oauth-audit-it-" + UUID.randomUUID() + "@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUpOwnRows() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        for (Long id : ids) {
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM oauth_accounts WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private void loginWith(String userAgent) throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/google")
                        .param("code", SUB + "~" + EMAIL)
                        .header("User-Agent", userAgent)
                        .with(req -> {
                            req.setRemoteAddr(SEND_IP);
                            return req;
                        }))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> awaitLoginAuditRows(Long userId, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS'",
                        Integer.class, userId)).isEqualTo(expected));
        return jdbc.queryForList(
                "SELECT ip_address, user_agent FROM audit_logs WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS' "
                        + "ORDER BY id", userId);
    }

    @Test
    @DisplayName("新規登録・既存連携ログインとも audit_logs の ip_address / user_agent が送信値になる")
    void oauthLogin_auditLogRecordsSentIpAndUserAgent() throws Exception {
        // 新規ユーザー登録経路
        loginWith(UA_FIRST);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        List<Map<String, Object>> afterRegister = awaitLoginAuditRows(userId, 1);
        assertThat(afterRegister.get(0).get("ip_address")).isEqualTo(SEND_IP);
        assertThat(afterRegister.get(0).get("user_agent")).isEqualTo(UA_FIRST);

        // 既存連携経路
        loginWith(UA_SECOND);
        List<Map<String, Object>> afterRelogin = awaitLoginAuditRows(userId, 2);
        assertThat(afterRelogin.get(1).get("ip_address")).isEqualTo(SEND_IP);
        assertThat(afterRelogin.get(1).get("user_agent")).isEqualTo(UA_SECOND);
    }
}
