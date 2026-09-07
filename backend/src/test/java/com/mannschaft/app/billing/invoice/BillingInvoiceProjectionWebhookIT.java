package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.payment.WebhookProcessStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * F20.1 PR5 試練 A: invoice 投影（AC-1〜AC-14）。
 *
 * <p>実 {@code StripeWebhookController} に実署名で検体を流し、{@code billing_invoices} /
 * {@code billing_invoice_lines} / {@code stripe_webhook_events} の実 DB 行だけを観測する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 PR5: invoice 投影 webhook IT（AC-1〜14）")
class BillingInvoiceProjectionWebhookIT extends AbstractBillingInvoiceWebhookIT {

    /** 税抜の基本検体: 単価1,000円×数量10・税率10%・割引500円 → 税抜9,500 / 税額950 / 税込10,450。 */
    private String standardInvoice(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 10_000L, 500L, 950L, 10_450L, line);
    }

    @Test
    @DisplayName("AC1: invoice.finalized で billing_invoices に1行投影され psp_invoice_ref が一意キーになる")
    void AC1_finalizedで投影される() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac1_finalized", "invoice.finalized", standardInvoice("in_ac1", "open"));

        postSigned(payload);

        BillingInvoiceEntity invoice = requireInvoice("in_ac1");
        assertThat(invoice.getBillingCustomerId()).as("scope 所有 Customer に紐づく").isEqualTo(billingCustomerId);
        assertThat(invoice.getContractId()).as("契約に紐づく").isEqualTo(billingContractId);
        assertThat(invoice.getScopeId()).isEqualTo(BILLING_SCOPE_ID);
        assertThat(invoice.getStatus()).isEqualTo("OPEN");
        assertThat(invoice.getFinalizedAt()).as("finalized_at が入る").isNotNull();
        assertThat(invoiceRepository.count()).as("同一 invoice の投影は 1 行だけ").isEqualTo(1L);
    }

    @Test
    @DisplayName("AC2: invoice.paid で status=PAID と paid_at が入る")
    void AC2_paidでPAIDとpaidAtが入る() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac2_finalized", "invoice.finalized", standardInvoice("in_ac2", "open")));
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac2_paid", "invoice.paid", standardInvoice("in_ac2", "paid")));

        BillingInvoiceEntity invoice = requireInvoice("in_ac2");
        assertThat(invoice.getStatus()).isEqualTo("PAID");
        assertThat(invoice.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("AC3: invoice.voided で status=VOID と voided_at が入る")
    void AC3_voidedでVOIDとvoidedAtが入る() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac3_finalized", "invoice.finalized", standardInvoice("in_ac3", "open")));
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac3_voided", "invoice.voided", standardInvoice("in_ac3", "void")));

        BillingInvoiceEntity invoice = requireInvoice("in_ac3");
        assertThat(invoice.getStatus()).isEqualTo("VOID");
        assertThat(invoice.getVoidedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC4: 同一 event.id の再送は副作用ゼロで 200 を返す")
    void AC4_同一eventIdの再送は副作用ゼロ() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac4_paid", "invoice.paid", standardInvoice("in_ac4", "paid"));

        postSigned(payload);
        BillingInvoiceEntity first = requireInvoice("in_ac4");

        int status = postSigned(payload).getResponse().getStatus();

        assertThat(status).as("再送は 200").isEqualTo(200);
        assertThat(invoiceRepository.count()).as("invoice 行は増えない").isEqualTo(1L);
        assertThat(linesOf("in_ac4")).as("line 行も増えない").hasSize(1);
        assertThat(requireInvoice("in_ac4").getUpdatedAt())
                .as("再送で投影が書き換わらない").isEqualTo(first.getUpdatedAt());
    }

    @Test
    @DisplayName("AC5: subtotal - discount + tax != total なら投影を確定しない（fail-closed）")
    void AC5_金額恒等式が破れたら投影しない() throws Exception {
        // 10000 - 500 + 950 = 10450 であるべきところに total=99999 を入れた壊れた検体。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac5", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        String payload = StripeWebhookPayloadFixture.event("evt_ac5_broken", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac5", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 500L, 950L, 99_999L, line));

        postSigned(payload);

        assertThat(invoiceOf("in_ac5")).as("恒等式が破れた invoice は投影しない").isEmpty();

        // 陽性対照: 恒等式の成り立つ双子は投影されること。これが無いと「何も実装されていないから空」でも
        // 緑になり、fail-closed を検証したことにならない（全壊時の偽 green 回避）。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac5_ok", "invoice.finalized",
                standardInvoice("in_ac5_ok", "open")));
        assertThat(invoiceOf("in_ac5_ok")).as("陽性対照: 恒等式の成り立つ invoice は投影される").isPresent();
    }

    @Test
    @DisplayName("AC6: JPY の line amount を再丸めせず Stripe の値のまま保存する")
    void AC6_JPY金額を再丸めしない() throws Exception {
        // 端数の出る金額をそのまま保存すること（1円単位の再計算・四捨五入をしない）。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac6", "BASIC プラン", 3L, 3_333L, 0L, 333L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac6", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac6", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        3_333L, 0L, 333L, 3_666L, line)));

        BillingInvoiceLineEntity stored = linesOf("in_ac6").get(0);
        assertThat(stored.getAmountExcludingTax()).as("Stripe の line amount そのまま").isEqualTo(3_333L);
        assertThat(stored.getTaxAmount()).isEqualTo(333L);
        assertThat(stored.getAmountIncludingTax()).isEqualTo(3_666L);
    }

    /**
     * AC-7（正本 05:82）: 所有外だけが既存 F08.9 へ <b>event id を消費せず</b> fallthrough し最終 200。
     *
     * <p><b>なぜ「誰も確定させない」ではなく「billing が触っていない」を測るのか</b>:
     * 当初この検証は {@code process_status} が {@code PROCESSED}/{@code IGNORED} でないことを見ていたが、
     * それは<b>行の最終状態しか見ておらず、誰が確定させたかを区別できていなかった</b>。
     * 実際には F08.9 会費側（{@code MembershipSubscriptionWebhookService}）が
     * 「対象 subscription なし（無関係 invoice）」を {@code IGNORED} で確定する。これは
     * <b>会費側の正しい振る舞い</b>である（例外を投げて Stripe に再送させないための no-op であり、
     * 再送しても対象が現れないため確定させてよい）。したがって「誰も確定させない」は過剰な期待だった。</p>
     *
     * <p>正本が定めているのは <b>billing が event id を消費しないこと</b>である。そこで本テストは
     * billing が受信記録に触れた痕跡（V196 の billing 用列）が<b>一つも残っていないこと</b>を直接測る。
     * billing が処理していれば {@code tryBegin} が payload_sha256 / stripe_object_ref /
     * billing_contract_id / billing_customer_id を必ず埋めるため、これらが空であることが
     * 「billing は素通りした」の十分な証拠になる（F08.9 側は 3 引数版を使うのでこれらを埋めない）。</p>
     */
    @Test
    @DisplayName("AC7: billing 所有でない invoice は billing が event id を消費せず F08.9 会費側へ fallthrough し最終 200")
    void AC7_billing非所有はeventId未確定でfallthrough() throws Exception {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac7", "会費", 1L, 1_000L, 0L, 0L, false, null);
        String payload = StripeWebhookPayloadFixture.event("evt_ac7_foreign", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac7", "cus_f089_membership",
                        FOREIGN_SUBSCRIPTION_REF, "paid", "jpy", 1_000L, 0L, 0L, 1_000L, line));

        int status = postSigned(payload).getResponse().getStatus();

        assertThat(status).as("最終的に 200").isEqualTo(200);
        assertThat(invoiceOf("in_ac7")).as("billing の投影を作らない").isEmpty();

        // billing が受信記録に触れていないこと。billing が処理していれば tryBegin が
        // 以下 4 列を必ず埋めるため、すべて空であることが「素通りした」証拠になる。
        var event = webhookEvent("evt_ac7_foreign").orElseThrow(() ->
                new AssertionError("受信記録そのものは残るはず（F08.9 側が記録する）: evt_ac7_foreign"));
        assertThat(event.getBillingContractId()).as("billing 所有として契約に紐付けない").isNull();
        assertThat(event.getBillingCustomerId()).as("billing 所有として Customer に紐付けない").isNull();
        assertThat(event.getStripeObjectRef()).as("billing が対象 object を記録しない").isNull();
        assertThat(event.getPayloadSha256()).as("billing が payload ハッシュを記録しない").isNull();
    }

    @Test
    @DisplayName("AC9: event.created が投影より古ければ巻き戻さない（単調更新）")
    void AC9_古いeventで投影を巻き戻さない() throws Exception {
        long newer = System.currentTimeMillis() / 1000L;
        long older = newer - 3_600L;

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac9_paid", "invoice.paid", standardInvoice("in_ac9", "paid"), newer));
        assertThat(requireInvoice("in_ac9").getStatus()).isEqualTo("PAID");

        // 遅れて届いた「古い」open 状態のイベント。単調更新なら PAID を OPEN へ戻さない。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac9_stale_open", "invoice.finalized", standardInvoice("in_ac9", "open"), older));

        assertThat(requireInvoice("in_ac9").getStatus())
                .as("古い event で PAID を OPEN に巻き戻さない").isEqualTo("PAID");
        assertThat(requireInvoice("in_ac9").getPaidAt()).as("paid_at も消さない").isNotNull();
    }

    @Test
    @DisplayName("AC11: 署名不正は 400 を返し投影も event 記録も作らない")
    void AC11_署名不正は400() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac11_badsig", "invoice.paid", standardInvoice("in_ac11", "paid"));

        int status = postWithSignature(payload, "t=1,v1=deadbeef").getResponse().getStatus();

        assertThat(status).as("署名不正は 400").isEqualTo(400);
        assertThat(invoiceOf("in_ac11")).isEmpty();
        assertThat(webhookEvent("evt_ac11_badsig")).isEmpty();
    }

    @Test
    @DisplayName("AC12: raw payload を DB に永続化せず payload_sha256 だけを残す")
    void AC12_rawPayloadを永続化しない() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac12", "invoice.paid", standardInvoice("in_ac12", "paid"));

        postSigned(payload);

        assertThat(webhookEvent("evt_ac12")).as("受信記録は残る").isPresent();
        assertThat(webhookEvent("evt_ac12").orElseThrow().getPayloadSha256())
                .as("payload_sha256 が 64 桁 hex で入る")
                .isNotNull()
                .matches("[0-9a-f]{64}");

        // どの列にも payload の本文が入っていないこと（列名ではなく実データを走査して確かめる）。
        List<String> rowDump = jdbcTemplate.query(
                "SELECT CONCAT_WS('|', event_id, type, IFNULL(stripe_object_ref,''), "
                        + "IFNULL(payload_sha256,''), process_status) FROM stripe_webhook_events",
                (rs, i) -> rs.getString(1));
        assertThat(rowDump).noneMatch(row -> row.contains("\"object\":\"invoice\""));
        assertThat(rowDump).noneMatch(row -> row.contains("billing-taro@example.com"));
    }

    @Test
    @DisplayName("AC13: 失敗時に attempt_count / failed_at を更新し 5 回超過で FAILED 確定する（新テーブルを作らない）")
    void AC13_失敗時にattemptCountとfailedAtを更新する() throws Exception {
        // scope-owned Customer と照合できない（＝billing 所有だが処理不能な）検体を作るため、
        // subscription は billing のものだが customer が別人という不整合検体を使う。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac13", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        String payload = StripeWebhookPayloadFixture.event("evt_ac13", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac13", "cus_someone_else",
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy", 10_000L, 500L, 950L, 10_450L, line));

        for (int i = 0; i < 6; i++) {
            postSigned(payload);
        }

        assertThat(webhookEvent("evt_ac13")).as("受信記録が残る").isPresent();
        assertThat(webhookEvent("evt_ac13").orElseThrow().getAttemptCount())
                .as("試行回数が加算される").isGreaterThanOrEqualTo(5);
        assertThat(webhookEvent("evt_ac13").orElseThrow().getFailedAt())
                .as("failed_at が記録される").isNotNull();
        assertThat(webhookEvent("evt_ac13").orElseThrow().getProcessStatus())
                .as("5 回超過で FAILED 確定").isEqualTo(WebhookProcessStatus.FAILED);

        // 新テーブルを作らないこと（リトライ台帳を別表に切り出さない）。
        List<String> newTables = jdbcTemplate.query("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND (table_name LIKE '%webhook_retr%' OR table_name LIKE '%webhook_dead%'
                        OR table_name LIKE '%webhook_attempt%' OR table_name LIKE '%webhook_failure%')
                """, (rs, i) -> rs.getString(1));
        assertThat(newTables).as("リトライ用の新テーブルを作らない").isEmpty();
    }

    @Test
    @DisplayName("AC14: billing_invoice_lines は UNIQUE(invoice_id, psp_line_ref) で冪等・再送で重複しない")
    void AC14_lineはinvoiceIdとpspLineRefで冪等() throws Exception {
        String twoLines = StripeWebhookPayloadFixture.lineObject(
                "il_ac14_a", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000)
                + ","
                + StripeWebhookPayloadFixture.lineObject(
                "il_ac14_b", "追加席", 2L, 2_000L, 0L, 200L, false, 1000);
        String invoice = StripeWebhookPayloadFixture.invoiceObject("in_ac14", BILLING_CUSTOMER_REF,
                BILLING_SUBSCRIPTION_REF, "open", "jpy", 12_000L, 500L, 1_150L, 12_650L, twoLines);

        postSigned(StripeWebhookPayloadFixture.event("evt_ac14_finalized", "invoice.finalized", invoice));
        // 別 event id・同じ invoice（Stripe の順不同再送）。line は増えてはならない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14_resend", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy", 12_000L, 500L, 1_150L, 12_650L, twoLines)));

        assertThat(linesOf("in_ac14")).as("line は 2 行のまま").hasSize(2);
        assertThat(linesOf("in_ac14")).extracting(BillingInvoiceLineEntity::getPspLineRef)
                .containsExactlyInAnyOrder("il_ac14_a", "il_ac14_b");
    }

    @Test
    @DisplayName("AC14: 確定前に金額が変わった line は最新値へ更新される（積みっぱなしにしない）")
    void AC14_確定前に変わったlineは最新値へ更新される() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        // draft 段階: 単価1,000円×10・割引500・税950 → 税抜9,500 / 税込10,450。
        String draftLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac14u", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14u_created", "invoice.updated",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14u", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "draft", "jpy",
                        10_000L, 500L, 950L, 10_450L, draftLine), created));

        // finalize までに数量が倍になった（同じ psp_line_ref のまま金額が変わる）。
        String finalLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac14u", "BASIC プラン（改定）", 20L, 20_000L, 0L, 2_000L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14u_finalized", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14u", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        20_000L, 0L, 2_000L, 22_000L, finalLine), created + 60L));

        List<BillingInvoiceLineEntity> lines = linesOf("in_ac14u");
        assertThat(lines).as("同じ psp_line_ref は増えない").hasSize(1);
        BillingInvoiceLineEntity line = lines.get(0);
        assertThat(line.getAmountExcludingTax()).as("税抜額が最新値へ更新される").isEqualTo(20_000L);
        assertThat(line.getTaxAmount()).as("税額が最新値へ更新される").isEqualTo(2_000L);
        assertThat(line.getAmountIncludingTax()).as("税込額が最新値へ更新される").isEqualTo(22_000L);
        assertThat(line.getDiscountAmount()).as("割引が最新値へ更新される").isEqualTo(0L);
        assertThat(line.getDescriptionSnapshot()).as("名称も最新値へ更新される").isEqualTo("BASIC プラン（改定）");
        assertThat(lines.stream().mapToLong(BillingInvoiceLineEntity::getAmountIncludingTax).sum())
                .as("明細の税込合計がヘッダ total と一致する")
                .isEqualTo(requireInvoice("in_ac14u").getTotalAmount());
    }

    @Test
    @DisplayName("AC9/AC14: 確定後に届いた古い event では line が古い値へ巻き戻らない")
    void AC9_確定後のlineは古いeventで巻き戻らない() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        String paidLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac14r", "BASIC プラン", 20L, 20_000L, 0L, 2_000L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14r_paid", "invoice.paid",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14r", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "paid", "jpy",
                        20_000L, 0L, 2_000L, 22_000L, paidLine), created));

        // 遅れて届いた draft 時代の（古い）内容。単調更新なら明細も巻き戻らない。
        String staleLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac14r", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14r_stale", "invoice.updated",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14r", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "draft", "jpy",
                        10_000L, 500L, 950L, 10_450L, staleLine), created - 3_600L));

        BillingInvoiceLineEntity line = linesOf("in_ac14r").get(0);
        assertThat(line.getAmountExcludingTax()).as("古い event で税抜額を巻き戻さない").isEqualTo(20_000L);
        assertThat(line.getAmountIncludingTax()).as("古い event で税込額を巻き戻さない").isEqualTo(22_000L);
        assertThat(requireInvoice("in_ac14r").getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("AC14: 確定までに取り下げられた line は残さない（明細合計とヘッダの食い違いを作らない）")
    void AC14_取り下げられたlineは残さない() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        String twoLines = StripeWebhookPayloadFixture.lineObject(
                "il_ac14d_a", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000)
                + ","
                + StripeWebhookPayloadFixture.lineObject(
                "il_ac14d_b", "追加席", 2L, 2_000L, 0L, 200L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14d_draft", "invoice.updated",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14d", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "draft", "jpy",
                        12_000L, 0L, 1_200L, 13_200L, twoLines), created));
        assertThat(linesOf("in_ac14d")).hasSize(2);

        String oneLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac14d_a", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac14d_finalized", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14d", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 0L, 1_000L, 11_000L, oneLine), created + 60L));

        assertThat(linesOf("in_ac14d")).extracting(BillingInvoiceLineEntity::getPspLineRef)
                .as("取り下げられた line は残らない").containsExactly("il_ac14d_a");
        assertThat(linesOf("in_ac14d").stream()
                .mapToLong(BillingInvoiceLineEntity::getAmountIncludingTax).sum())
                .as("明細の税込合計がヘッダ total と一致する")
                .isEqualTo(requireInvoice("in_ac14d").getTotalAmount());
    }

    @Test
    @DisplayName("AC14: has_more=true の部分 payload でも、全件取得して明細が揃い投影される")
    void AC14_部分payloadでも全件取得して投影される() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        // 本番の Stripe は has_more=true でも subtotal/tax/total は「請求書全体」の値を返す。
        // したがって載っている明細だけでは validate() の「line 税込合計 == total」を決して満たさず、
        // 全件取得の経路が無ければこの請求書は最初から一度も投影できない。
        String bothLines = StripeWebhookPayloadFixture.lineObject(
                "il_ac14p_a", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000)
                + ","
                + StripeWebhookPayloadFixture.lineObject(
                "il_ac14p_b", "追加席", 2L, 2_000L, 0L, 200L, false, 1000);
        String firstPageOnly = StripeWebhookPayloadFixture.lineObject(
                "il_ac14p_a", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000);

        given(stripeInvoiceRetriever.retrieve("in_ac14p")).willReturn(
                payloadParser.parseInvoiceObject(StripeWebhookPayloadFixture.invoiceObject(
                        "in_ac14p", BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        12_000L, 0L, 1_200L, 13_200L, bothLines)));

        postSigned(StripeWebhookPayloadFixture.event("evt_ac14p_truncated", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14p", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        12_000L, 0L, 1_200L, 13_200L, firstPageOnly, true), created));

        assertThat(linesOf("in_ac14p")).extracting(BillingInvoiceLineEntity::getPspLineRef)
                .as("件数上限で切られていても全明細が投影される")
                .containsExactlyInAnyOrder("il_ac14p_a", "il_ac14p_b");
        assertThat(linesOf("in_ac14p").stream()
                .mapToLong(BillingInvoiceLineEntity::getAmountIncludingTax).sum())
                .as("明細の税込合計がヘッダ total と一致する")
                .isEqualTo(requireInvoice("in_ac14p").getTotalAmount());
        assertThat(requireInvoice("in_ac14p").getTotalAmount()).isEqualTo(13_200L);
    }

    @Test
    @DisplayName("AC14: 全件取得できない部分 payload は投影しない（欠けた明細で確定させない）")
    void AC14_全件取得できない部分payloadは投影しない() throws Exception {
        long created = System.currentTimeMillis() / 1000L;
        // 既定スタブ（Optional.empty＝取得失敗）のまま流す。
        String firstPageOnly = StripeWebhookPayloadFixture.lineObject(
                "il_ac14n_a", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000);

        postSigned(StripeWebhookPayloadFixture.event("evt_ac14n", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac14n", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        12_000L, 0L, 1_200L, 13_200L, firstPageOnly, true), created));

        assertThat(invoiceOf("in_ac14n"))
                .as("明細が欠けたまま確定させない（fail-closed）").isEmpty();
    }

    @Test
    @DisplayName("AC9: 同一秒に届いた古い draft では明細が巻き戻らない")
    void AC9_同一秒の古いdraftで明細が巻き戻らない() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        String finalLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac9s", "BASIC プラン", 20L, 20_000L, 0L, 2_000L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac9s_finalized", "invoice.finalized",
                StripeWebhookPayloadFixture.invoiceObject("in_ac9s", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        20_000L, 0L, 2_000L, 22_000L, finalLine), created));

        // Stripe の event.created は秒精度。遅れて届いた draft が finalized と同じ秒を持ちうる。
        String draftLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac9s", "BASIC プラン", 10L, 10_000L, 500L, 950L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac9s_same_second_draft", "invoice.updated",
                StripeWebhookPayloadFixture.invoiceObject("in_ac9s", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "draft", "jpy",
                        10_000L, 500L, 950L, 10_450L, draftLine), created));

        BillingInvoiceLineEntity line = linesOf("in_ac9s").get(0);
        assertThat(line.getAmountExcludingTax())
                .as("同一秒の古い draft で税抜額を巻き戻さない").isEqualTo(20_000L);
        assertThat(line.getAmountIncludingTax())
                .as("同一秒の古い draft で税込額を巻き戻さない").isEqualTo(22_000L);
        assertThat(line.getQuantity().longValue())
                .as("同一秒の古い draft で数量を巻き戻さない").isEqualTo(20L);
        assertThat(requireInvoice("in_ac9s").getStatus())
                .as("ヘッダの状態も巻き戻さない").isEqualTo("OPEN");
        assertThat(requireInvoice("in_ac9s").getTotalAmount()).isEqualTo(22_000L);
    }

    @Test
    @DisplayName("AC9: 同一秒・同一状態で衝突したら Stripe の現在値を正とし、古い payload で巻き戻らない")
    void AC9_同一秒同一状態は取得値を正とする() throws Exception {
        long created = System.currentTimeMillis() / 1000L;

        String newLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac9t", "BASIC プラン", 20L, 20_000L, 0L, 2_000L, false, 1000);
        String newInvoice = StripeWebhookPayloadFixture.invoiceObject("in_ac9t", BILLING_CUSTOMER_REF,
                BILLING_SUBSCRIPTION_REF, "open", "jpy", 20_000L, 0L, 2_000L, 22_000L, newLine);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac9t_first", "invoice.updated",
                newInvoice, created));
        assertThat(requireInvoice("in_ac9t").getTotalAmount()).isEqualTo(22_000L);

        // 同じ秒・同じ status(open) の別イベントが後から届く。時刻でも状態でも先後を決められないので、
        // payload ではなく Stripe の現在値（＝新しいほう）を正とする。
        given(stripeInvoiceRetriever.retrieve("in_ac9t"))
                .willReturn(payloadParser.parseInvoiceObject(newInvoice));

        String staleLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac9t", "BASIC プラン", 10L, 10_000L, 0L, 1_000L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac9t_same_second", "invoice.updated",
                StripeWebhookPayloadFixture.invoiceObject("in_ac9t", BILLING_CUSTOMER_REF,
                        BILLING_SUBSCRIPTION_REF, "open", "jpy",
                        10_000L, 0L, 1_000L, 11_000L, staleLine), created));

        BillingInvoiceLineEntity line = linesOf("in_ac9t").get(0);
        assertThat(requireInvoice("in_ac9t").getTotalAmount())
                .as("同一秒・同一状態の古い payload でヘッダ合計を巻き戻さない").isEqualTo(22_000L);
        assertThat(line.getAmountIncludingTax())
                .as("明細の税込額も巻き戻さない").isEqualTo(22_000L);
        assertThat(line.getQuantity().longValue())
                .as("数量も巻き戻さない").isEqualTo(20L);
    }
}
