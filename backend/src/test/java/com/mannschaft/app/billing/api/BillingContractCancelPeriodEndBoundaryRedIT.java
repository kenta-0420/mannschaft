package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — 期末の境界（AC-34 / AC-37 / AC-37b / AC-37c）の受け入れテスト（試練・red）。
 *
 * <h2>第6隊への発注書（この境界群が要求する処理順）</h2>
 * <ol>
 *   <li>tx1（operation 予約・pointer 取得）を commit する。</li>
 *   <li><b>読み取り専用の</b> {@code gateway.retrieveSubscription(ref)} で Stripe 実物の
 *       {@code current_period_end} を引く。これが期末の<b>権威</b>（AC-34）。</li>
 *   <li>Stripe 側が null なら DB の {@code current_period_end} へ fallback。
 *       <b>両方 null なら 409 で DB を一切変更しない</b>（AC-37c。正本 05:334/344 の
 *       {@code endAt} は nullable ではないため、null のまま 200 を返してはならない）。</li>
 *   <li>解決した期末が<b>現在時刻以下</b>なら 409（AC-37 / AC-37b。半開区間の方針に合わせ、
 *       期末ちょうども無効。AC-24 と整合）。ここで Stripe への<b>変更系</b>呼び出しを発生させない。</li>
 *   <li>ここで初めて {@code cancelAtPeriodEnd(ref, operationId)}（AC-39）を送る。</li>
 * </ol>
 *
 * <p>この順序でなければ、AC-37 の 409 を返す前に Stripe へ
 * {@code cancel_at_period_end=true} を送ってしまい「DB は解約されていないのに Stripe だけ解約予約済み」
 * という不整合が残る。</p>
 *
 * <p><b>AC-37b の測定限界（第6隊・第9隊への申し送り）</b>: 「期末が<b>ちょうど</b>現在時刻」は、
 * アプリの {@code Clock} を差し替えないと同一マイクロ秒では作れない。本 IT は
 * 「リクエスト直前に読んだ現在時刻」を期末に置いた検体（経過ゼロに最も近い境界）と、
 * 「現在時刻＋5分」の陽性対照（200）を対で固定して境界の向きだけを一意にする。
 * ちょうど同値そのものは、実装後に Clock を注入した単体テストで別途固定すること。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 解約の期末境界（AC-34/37/37b/37c・試練 red）")
class BillingContractCancelPeriodEndBoundaryRedIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_boundary";

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("boundary");
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    // ═════════ AC-34: Stripe を権威として採る ═════════

    @Test
    @DisplayName("AC-34: StripeとDBの期末が食い違うときStripeを権威として採る（endAt・DB・valid_untilの全てがStripe値）")
    void AC34_期末はStripeが権威() throws Exception {
        LocalDateTime dbPeriodEnd = LocalDateTime.now(clock).plusDays(5).withNano(0);
        LocalDateTime stripePeriodEnd = LocalDateTime.now(clock).plusDays(25).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, dbPeriodEnd, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);
        stubStripeSubscription(SUB_REF, false, stripePeriodEnd);

        MvcResult result = cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk()).andReturn();

        JsonNode data = body(result).path("data");
        assertThat(data.path("endAt").asText())
                .as("endAt は Stripe 側の期末（DB 値を採ってはならない）")
                .startsWith(stripePeriodEnd.toLocalDate().toString());

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getCurrentPeriodEnd())
                .as("DB の current_period_end も Stripe 値へ更新される").isEqualTo(stripePeriodEnd);

        List<EntitlementEntity> rows = reloadEntitlements(contractId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getValidUntil())
                .as("valid_until も Stripe 値（権威が2箇所に分かれない）").isEqualTo(stripePeriodEnd);
    }

    // ═════════ AC-37: D4 — PAST_DUE かつ期末が過去 ═════════

    @Test
    @DisplayName("AC-37: PAST_DUEでcurrent_period_endが過去なら409（valid_untilを過去に置いて即時失効にしない）")
    void AC37_期末が過去なら409() throws Exception {
        LocalDateTime past = LocalDateTime.now(clock).minusDays(3).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.PAST_DUE, PRICE_JPY, SUB_REF, past, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);
        stubStripeSubscription(SUB_REF, false, past);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isConflict());

        assertThat(reloadContract(contractId).getCancelledAt()).as("DB は変えない").isNull();
        assertThat(reloadEntitlements(contractId).get(0).getValidUntil())
                .as("権利を過去で打ち切って即時失効にしない").isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd"))
                .as("409 を返す前に Stripe へ変更を送ってはならない").isZero();
    }

    @Test
    @DisplayName("AC-37: 陽性対照 — PAST_DUEでもcurrent_period_endが未来なら200（AC-36の許可が生きている）")
    void AC37_陽性対照_期末が未来なら200() throws Exception {
        LocalDateTime future = LocalDateTime.now(clock).plusDays(3).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.PAST_DUE, PRICE_JPY, SUB_REF, future, null);
        stubStripeSubscription(SUB_REF, false, future);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());

        assertThat(stripeCalls("cancelAtPeriodEnd")).isEqualTo(1L);
    }

    // ═════════ AC-37b: 期末＝現在時刻（半開区間の向き） ═════════

    @Test
    @DisplayName("AC-37b: current_period_endが現在時刻ちょうど（経過ゼロの境界）なら409でDB無変更")
    void AC37b_期末が現在時刻ちょうどなら409() throws Exception {
        LocalDateTime boundary = LocalDateTime.now(clock);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, boundary, null);
        stubStripeSubscription(SUB_REF, false, boundary);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        assertThat(reloadContract(contractId).getCancelledAt()).isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-37b: 陽性対照 — 期末が現在時刻＋5分なら200（境界の向きを一意にする）")
    void AC37b_陽性対照_期末が5分後なら200() throws Exception {
        LocalDateTime slightlyFuture = LocalDateTime.now(clock).plusMinutes(5).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, slightlyFuture, null);
        stubStripeSubscription(SUB_REF, false, slightlyFuture);

        cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk());
    }

    // ═════════ AC-37c: Stripe も DB も null ═════════

    @Test
    @DisplayName("AC-37c: StripeとDBの期末がともにnullなら409でDBを一切変更しない（nullのまま200にしない）")
    void AC37c_期末が両方nullなら409() throws Exception {
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, null, null);
        insertEntitlement(userId, contractId, FEATURE_KEY);
        stubStripeSubscription(SUB_REF, false, null);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_011"));

        BillingContractEntity after = reloadContract(contractId);
        assertThat(after.getCancelledAt()).as("DB を一切変更しない").isNull();
        assertThat(after.getCurrentPeriodEnd()).isNull();
        assertThat(reloadEntitlements(contractId).get(0).getValidUntil())
                .as("valid_until も触らない").isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    @Test
    @DisplayName("AC-37c: Stripeがnullでも DB に期末があれば fallback して200（null判定が過剰でないこと）")
    void AC37c_陽性対照_DBにあればfallbackして200() throws Exception {
        LocalDateTime dbPeriodEnd = LocalDateTime.now(clock).plusDays(9).withNano(0);
        UUID contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, dbPeriodEnd, null);
        stubStripeSubscription(SUB_REF, false, null);

        MvcResult result = cancel(userId, contractId, 0L, newKey()).andExpect(status().isOk()).andReturn();

        assertThat(body(result).path("data").path("endAt").asText())
                .as("Stripe が持たないときだけ DB へ落ちる")
                .startsWith(dbPeriodEnd.toLocalDate().toString());
    }
}
