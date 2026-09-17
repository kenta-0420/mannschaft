package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractChangeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — C群 3DS の入口（AC-48〜54 / AC-70 / AC-71）の受け入れテスト（試練・red）。
 *
 * <p>cookie の名前・path・期限は {@link BillingPaymentActionCookieRedIT}、
 * clientSecret の露出面は {@link BillingPaymentActionSecretExposureRedIT} が担う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 payment-action API（C群 AC-48〜54/70/71・試練 red）")
class BillingPaymentActionApiRedIT extends AbstractBillingPaymentActionApiIT {

    private UUID operationId;

    @BeforeEach
    void setUp() {
        seedUpgradableContract("action");
        operationId = insertPlanChangeOperation();
        stubPaymentAction(Instant.now(clock).plusSeconds(900));
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    /**
     * 検体の change を1件作る。
     *
     * <p>AC-53 のように<b>1テストで複数の検体</b>を並べるため、change ごとに新しい operation を切る
     * （{@code uk_bcc_operation} により change と operation は 1:1。使い回すと 2件目の INSERT が
     * 一意制約で落ち、assert に到達しない）。</p>
     */
    private UUID change(BillingContractChangeStatus status) {
        return insertChange(insertPlanChangeOperationWithoutPointer(), status, userId,
                Instant.now(clock).plusSeconds(3_600));
    }

    // ═════════ AC-48: 正常系（陽性対照そのもの） ═════════

    @Test
    @DisplayName("AC-48: REQUIRES_ACTION の change は 200 で paymentAction{type,clientSecret,expiresAt} を返す")
    void AC48_REQUIRES_ACTIONは200でpaymentActionを返す() throws Exception {
        UUID changeId = change(BillingContractChangeStatus.REQUIRES_ACTION);

        paymentAction(userId, contractId, changeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentAction.type").value("payment_intent"))
                .andExpect(jsonPath("$.data.paymentAction.clientSecret").value(CLIENT_SECRET))
                .andExpect(jsonPath("$.data.paymentAction.expiresAt").isNotEmpty());

        assertThat(gatewayInvocations())
                .as("陽性対照: 200 のときは実際に Stripe から取りに行く（AC-53 の否定側を空虚な緑にしない）")
                .isGreaterThanOrEqualTo(1L);
    }

    // ═════════ AC-49〜52: 409 の4検体 ═════════

    @Test
    @DisplayName("AC-49: PENDING_PAYMENT の change への payment-action は 409 CHANGE_CONFLICT")
    void AC49_PENDING_PAYMENTは409() throws Exception {
        paymentAction(userId, contractId, change(BillingContractChangeStatus.PENDING_PAYMENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    @Test
    @DisplayName("AC-50: APPLIED の change への payment-action は 409")
    void AC50_APPLIEDは409() throws Exception {
        paymentAction(userId, contractId, change(BillingContractChangeStatus.APPLIED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    @Test
    @DisplayName("AC-51: FAILED の change への payment-action は 409")
    void AC51_FAILEDは409() throws Exception {
        paymentAction(userId, contractId, change(BillingContractChangeStatus.FAILED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    @Test
    @DisplayName("AC-52: change が存在しない（自スコープ・未知 changeId）payment-action は 409")
    void AC52_change不在は409() throws Exception {
        paymentAction(userId, contractId, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    // ═════════ AC-53: 「何も返さない」を陽性対照つきで測る ═════════

    @Test
    @DisplayName("AC-53: 409 の4検体いずれでも clientSecret も cookie も返さず Stripe retrieve は 0 回")
    void AC53_409では秘密もcookieも返さずStripeを呼ばない() throws Exception {
        BillingContractChangeStatus[] conflicting = {
                BillingContractChangeStatus.PENDING_PAYMENT,
                BillingContractChangeStatus.APPLIED,
                BillingContractChangeStatus.FAILED,
        };
        for (BillingContractChangeStatus status : conflicting) {
            MvcResult result = paymentAction(userId, contractId, change(status))
                    .andExpect(status().isConflict()).andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("%s: 本文に clientSecret を載せない", status).doesNotContain(CLIENT_SECRET);
            assertThat(rawSetCookie(result, PAYMENT_ACTION_COOKIE))
                    .as("%s: cookie を発行しない", status).isNull();
        }
        // change 不在も同じ扱い。
        MvcResult missing = paymentAction(userId, contractId, UUID.randomUUID())
                .andExpect(status().isConflict()).andReturn();
        assertThat(missing.getResponse().getContentAsString()).doesNotContain(CLIENT_SECRET);
        assertThat(rawSetCookie(missing, PAYMENT_ACTION_COOKIE)).isNull();

        assertThat(gatewayInvocations())
                .as("409 の間は Stripe を一度も呼ばない").isZero();

        // 陽性対照: 同じ座席で REQUIRES_ACTION なら Stripe を呼び cookie も返る。
        // これが赤いうちは上の 0 件・null を「通過」と読んではならない。
        MvcResult ok = paymentAction(userId, contractId, change(BillingContractChangeStatus.REQUIRES_ACTION))
                .andExpect(status().isOk()).andReturn();
        assertThat(gatewayInvocations()).as("陽性対照: 200 では Stripe を呼ぶ").isGreaterThanOrEqualTo(1L);
        assertThat(rawSetCookie(ok, PAYMENT_ACTION_COOKIE)).as("陽性対照: 200 では cookie を返す").isNotNull();
    }

    // ═════════ AC-54: 都度取得・DB に保存しない ═════════

    @Test
    @DisplayName("AC-54: clientSecret は Stripe から都度取得し change 行のどの列にも保存しない")
    void AC54_clientSecretは都度取得しDBに保存しない() throws Exception {
        UUID changeId = change(BillingContractChangeStatus.REQUIRES_ACTION);

        paymentAction(userId, contractId, changeId).andExpect(status().isOk());
        long afterFirst = gatewayInvocations();

        paymentAction(userId, contractId, changeId).andExpect(status().isOk());
        assertThat(gatewayInvocations())
                .as("二度目も Stripe から取り直す（DB/メモリにキャッシュして返さない）")
                .isGreaterThan(afterFirst);

        assertThat(countBillingColumnsContaining(CLIENT_SECRET))
                .as("billing 系のどの表・どの文字列列にも clientSecret を残さない").isZero();
        assertThat(reloadChange(changeId).getStatus())
                .as("取得は状態を進めない").isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }

    // ═════════ AC-70: E5' 同一スコープの別 actor は 404 ═════════

    @Test
    @DisplayName("AC-70: スコープ権限はあるが change.created_by != actor の要求は 404・Stripe/cookie/冪等台帳はいずれも 0 回")
    void AC70_同一スコープの別actorは404で副作用ゼロ() throws Exception {
        Long otherActor = insertUser("action-other");
        UUID changeId = insertChange(operationId, BillingContractChangeStatus.REQUIRES_ACTION,
                otherActor, Instant.now(clock).plusSeconds(3_600));

        // 本人（USER スコープの持ち主）が要求しても、change を起票したのは別 actor なので 404。
        MvcResult result = paymentAction(userId, contractId, changeId)
                .andExpect(status().isNotFound()).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("404 の本文に clientSecret を載せない").doesNotContain(CLIENT_SECRET);
        assertThat(rawSetCookie(result, PAYMENT_ACTION_COOKIE)).as("cookie を発行しない").isNull();
        assertThat(gatewayInvocations()).as("Stripe retrieve は 0 回").isZero();
        assertThat(countRows("SELECT COUNT(*) FROM billing_api_idempotencies"))
                .as("認可判定より先に冪等台帳へ書かない").isZero();

    }

    // ═════════ AC-71: 別端末・再ログインでの再開 ═════════

    @Test
    @DisplayName("AC-71: 同一 actor の別端末・再ログインでは新しい clientSecret を再発行して再開できる")
    void AC71_別端末でも再発行して再開できる() throws Exception {
        UUID changeId = change(BillingContractChangeStatus.REQUIRES_ACTION);

        MvcResult first = paymentAction(userId, contractId, changeId)
                .andExpect(status().isOk()).andReturn();
        String firstCookie = rawSetCookie(first, PAYMENT_ACTION_COOKIE);

        // 別端末＝cookie を一切持たない要求。それでも 200 で clientSecret と cookie が返る。
        MvcResult second = paymentAction(userId, contractId, changeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentAction.clientSecret").value(CLIENT_SECRET))
                .andReturn();

        assertThat(rawSetCookie(second, PAYMENT_ACTION_COOKIE))
                .as("別端末にも新しい cookie を発行する").isNotNull();
        assertThat(cookieValue(rawSetCookie(second, PAYMENT_ACTION_COOKIE)))
                .as("再開のたびに新しい state（nonce）を切る。使い回すと片方の消費で他方が死ぬ")
                .isNotEqualTo(cookieValue(firstCookie));
        assertThat(reloadChange(changeId).getStatus())
                .as("再開は状態を進めない（確定は webhook だけ）")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }
}
