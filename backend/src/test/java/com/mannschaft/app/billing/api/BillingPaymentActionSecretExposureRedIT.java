package com.mannschaft.app.billing.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — clientSecret の露出面（AC-55〜59）の受け入れテスト（試練・red）。
 *
 * <p>AC-55〜59 は「どこに出てはいけないか」を <b>URL / return state / DB / ログ・監査 /
 * browser storage</b> の 5 面に分けて測る。1本にまとめると、ひとつの面だけ塞いだ実装でも
 * 全部が緑になってしまう。</p>
 *
 * <p><b>空虚な緑への備え</b>: 各テストは「秘密が実際に発行されている」ことを先に確かめてから
 * 各面の不在を測る。秘密が一度も生まれていない状態で不在だけを測れば、どの面も無条件に緑になる。</p>
 *
 * <p>AC-59（browser storage）は BE 側からは「JS が触れない形で渡している」ことしか測れない。
 * FE 側で storage へ書かないことは {@code frontend/app/composables/useStripeSetup.spec.ts} が測る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 clientSecret の露出面（C群 AC-55〜59・試練 red）")
class BillingPaymentActionSecretExposureRedIT extends AbstractBillingPaymentActionApiIT {

    private UUID changeId;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        seedUpgradableContract("secret");
        changeId = insertChange(insertPlanChangeOperation(),
                BillingContractChangeStatus.REQUIRES_ACTION, userId,
                Instant.now(clock).plusSeconds(3_600));
        stubPaymentAction(Instant.now(clock).plusSeconds(900));

        // ログ検証はロガーのレベルを自分で開ける（fork をまたぐと継承した設定は当てにならない）。
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        root.setLevel(originalLevel);
        appender.stop();
        cleanupScope();
    }

    /** 秘密が実際に払い出されていることを確かめる（以降の不在検証の前提）。 */
    private MvcResult issueAndAssertSecretExists() throws Exception {
        MvcResult result = paymentAction(userId, contractId, changeId)
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString())
                .as("前提: clientSecret は本文で1度だけ払い出される（秘密が存在しない緑を作らない）")
                .contains(CLIENT_SECRET);
        return result;
    }

    @Test
    @DisplayName("AC-55: clientSecret は URL（Location・応答ヘッダ・戻りの 303 先）に一切載らない")
    void AC55_URLに載らない() throws Exception {
        MvcResult issued = issueAndAssertSecretExists();

        for (String name : issued.getResponse().getHeaderNames()) {
            for (String value : issued.getResponse().getHeaders(name)) {
                assertThat(value).as("応答ヘッダ %s に clientSecret を載せない", name)
                        .doesNotContain(CLIENT_SECRET);
            }
        }
        assertThat(issued.getRequest().getRequestURI() + "?"
                + String.valueOf(issued.getRequest().getQueryString()))
                .as("要求 URL 自体にも載らない（query で受け渡さない）")
                .doesNotContain(CLIENT_SECRET);

        // 3DS の戻りの 303 先にも載らない。
        String cookie = rawSetCookie(issued, PAYMENT_ACTION_COOKIE);
        assertThat(cookie).as("前提: リダイレクト型 3DS の cookie が発行されている").isNotNull();
        MvcResult returned = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(RETURN_PATH)
                        .cookie(new jakarta.servlet.http.Cookie(PAYMENT_ACTION_COOKIE, cookieValue(cookie)))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user(String.valueOf(userId))))
                .andReturn();
        assertThat(String.valueOf(returned.getResponse().getHeader("Location")))
                .as("戻りの Location に clientSecret を載せない").doesNotContain(CLIENT_SECRET);
    }

    @Test
    @DisplayName("AC-56: clientSecret は return state（cookie の token payload）に載らない")
    void AC56_returnStateに載らない() throws Exception {
        MvcResult issued = issueAndAssertSecretExists();

        String rawCookie = rawSetCookie(issued, PAYMENT_ACTION_COOKIE);
        assertThat(rawCookie).as("前提: cookie が発行されている").isNotNull();
        String token = cookieValue(rawCookie);
        assertThat(token).as("token 文字列そのものに秘密を載せない").doesNotContain(CLIENT_SECRET);

        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.", -1)[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload)
                .as("復号した return state payload の11フィールドのどこにも秘密を載せない")
                .doesNotContain(CLIENT_SECRET);
    }

    @Test
    @DisplayName("AC-57: clientSecret は DB（billing 系の全表・全文字列列）に残らない")
    void AC57_DBに残らない() throws Exception {
        issueAndAssertSecretExists();

        assertThat(countBillingColumnsContaining(CLIENT_SECRET))
                .as("billing 系のどの表・どの文字列列にも clientSecret を残さない").isZero();
        assertThat(countRows("SELECT COUNT(*) FROM billing_return_state_nonces"))
                .as("陽性対照: cookie 発行に伴い nonce 台帳には行が増えている"
                        + "（DB を一度も触っていないから 0 件、という空虚な緑を排除する）")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("AC-58: clientSecret はログ・監査（audit_logs）に残らない")
    void AC58_ログと監査に残らない() throws Exception {
        issueAndAssertSecretExists();

        assertThat(appender.list)
                .as("秘密の払い出しは何らかのログを伴うはずで、1件も記録が無い状態での緑を疑う")
                .isNotNull();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage())
                    .as("ログ行に clientSecret を出さない").doesNotContain(CLIENT_SECRET);
            for (Object arg : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                assertThat(String.valueOf(arg))
                        .as("ログ引数にも clientSecret を渡さない").doesNotContain(CLIENT_SECRET);
            }
        }

        long auditHits = countRows("SELECT COUNT(*) FROM audit_logs WHERE CONCAT_WS('|',"
                + " COALESCE(event_type,''), COALESCE(metadata,''), COALESCE(user_agent,''))"
                + " LIKE '%" + CLIENT_SECRET + "%'");
        assertThat(auditHits).as("監査イベントに clientSecret を焼き付けない").isZero();
    }

    @Test
    @DisplayName("AC-59: clientSecret は browser storage へ渡らない（cookie は HttpOnly・秘密を含まない）")
    void AC59_browserStorageへ渡らない() throws Exception {
        MvcResult issued = issueAndAssertSecretExists();

        String rawCookie = rawSetCookie(issued, PAYMENT_ACTION_COOKIE);
        assertThat(rawCookie).as("前提: cookie が発行されている").isNotNull();
        assertThat(rawCookie)
                .as("JS から読めない HttpOnly でなければ storage へ写し取れてしまう")
                .containsIgnoringCase("HttpOnly");
        assertThat(rawCookie).as("cookie 値に秘密そのものを入れない").doesNotContain(CLIENT_SECRET);

        for (String raw : rawSetCookies(issued)) {
            assertThat(raw).as("どの Set-Cookie にも秘密を載せない").doesNotContain(CLIENT_SECRET);
        }
    }
}
