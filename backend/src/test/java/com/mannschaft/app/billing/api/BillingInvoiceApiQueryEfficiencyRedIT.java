package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1 Billing Center PR5 — 課金履歴 API の <b>N+1・SQL 本数・応答時間</b>受け入れテスト（試練・red）。
 *
 * <p>対象 AC: AC-57 / AC-58 / AC-59。正本 §8「一覧 P95 は500ms、表示では Stripe 同期呼出しをせず投影を読む」。</p>
 *
 * <p><b>検体の厳密さ（AC-57）</b>: invoice 20件それぞれに lines 3件・adjustments 1件を持たせ、
 * さらに <b>lines も adjustments も持たない invoice を1件</b>混ぜる。
 * 1 invoice 1 line の検体では N+1 実装（invoice ごとに子を引く）が通過してしまうため、
 * 必ず複数持たせる。件数を変えた2走査で業務 SQL 本数が一致することを併せて測る
 * （絶対上限だけでは N+1 を捕捉できないという当リポジトリの定石に従う）。</p>
 *
 * <p><b>計測手法</b>: {@link SqlIntentCounter}（Hibernate {@code StatementInspector}）。
 * {@code application-test.yml} で常時登録済みのため配線不要。テーブル名ヒントで
 * 「クエリ意図数」を数えるので、IN 句バッチ分割で偽陽性にならない。
 * ただし {@code BillingAccessRepository} は {@code JdbcTemplate} 直叩きのため本カウンタには現れない
 * （AC-58 の上限8本は Hibernate 経由 SQL に対する上限として測る）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 課金履歴 API N+1・SQL本数・P95（試練 red）")
class BillingInvoiceApiQueryEfficiencyRedIT extends AbstractMySqlIntegrationTest {

    private static final String INVOICES = "/api/v1/me/billing/invoices";

    /** 20件（子つき）＋子なし1件を持つ actor。 */
    private static final long HEAVY_OWNER_ID = 700_301L;
    /** 5件だけ持つ actor（件数依存を測る対照）。 */
    private static final long LIGHT_OWNER_ID = 700_302L;

    private static final String T_INVOICES = "billing_invoices";
    private static final String T_LINES = "billing_invoice_lines";
    private static final String T_ADJUSTMENTS = "billing_invoice_adjustments";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BillingInvoiceJpaRepository invoiceRepository;
    @Autowired
    private BillingInvoiceLineJpaRepository lineRepository;
    @Autowired
    private BillingInvoiceAdjustmentJpaRepository adjustmentRepository;

    /** 子を3件/1件持つ invoice（AC-57 の主検体）。 */
    private UUID richInvoiceId;
    /** lines も adjustments も持たない invoice（AC-57 で必ず混ぜる検体）。 */
    private UUID barrenInvoiceId;

    @BeforeEach
    void setUp() {
        adjustmentRepository.deleteAll();
        lineRepository.deleteAll();
        invoiceRepository.deleteAll();

        List<UUID> heavy = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BillingInvoiceEntity saved = invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                    EntitlementScopeKind.USER,
                    HEAVY_OWNER_ID,
                    UUID.randomUUID(),
                    "in_heavy_" + UUID.randomUUID(),
                    Instant.parse("2026-01-31T15:00:00Z").plusSeconds(86_400L * i),
                    1_000L + i));
            heavy.add(saved.getId());
            // 各 invoice に lines 3件・adjustments 1件（N+1 実装なら本数が invoice 数に比例する）。
            for (int l = 0; l < 3; l++) {
                lineRepository.save(BillingInvoiceApiTestFixtures.line(
                        saved.getId(), "il_" + i + "_" + l, 1_000L * (l + 1)));
            }
            adjustmentRepository.save(BillingInvoiceApiTestFixtures.adjustment(
                    saved.getId(), "re_" + i + "_" + UUID.randomUUID(), 100L));
        }
        richInvoiceId = heavy.get(0);

        // 子を1件も持たない invoice を混ぜる（0 件経路で問合せを省略しても本数が変わらないこと）。
        barrenInvoiceId = invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                        EntitlementScopeKind.USER,
                        HEAVY_OWNER_ID,
                        UUID.randomUUID(),
                        "in_barren_" + UUID.randomUUID(),
                        Instant.parse("2026-02-28T15:00:00Z"),
                        2_000L))
                .getId();

        for (int i = 0; i < 5; i++) {
            BillingInvoiceEntity saved = invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                    EntitlementScopeKind.USER,
                    LIGHT_OWNER_ID,
                    UUID.randomUUID(),
                    "in_light_" + UUID.randomUUID(),
                    Instant.parse("2026-03-31T15:00:00Z").plusSeconds(86_400L * i),
                    3_000L + i));
            for (int l = 0; l < 3; l++) {
                lineRepository.save(BillingInvoiceApiTestFixtures.line(
                        saved.getId(), "ill_" + i + "_" + l, 1_000L * (l + 1)));
            }
            adjustmentRepository.save(BillingInvoiceApiTestFixtures.adjustment(
                    saved.getId(), "rel_" + i + "_" + UUID.randomUUID(), 100L));
        }
    }

    @Test
    @DisplayName("AC57_明細の業務SQLは子の有無に依らず3本固定")
    void AC57_明細の業務SQLは3本固定() throws Exception {
        int rich = businessSqlOfDetail(richInvoiceId);
        int barren = businessSqlOfDetail(barrenInvoiceId);

        assertThat(rich)
                .as("lines 3件・adjustments 1件を持つ invoice の業務 SQL。捕捉=%s",
                        SqlIntentCounter.capturedSqls())
                .isEqualTo(3);
        assertThat(barren)
                .as("lines も adjustments も持たない invoice でも同じ3本であること")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("AC57_一覧の業務SQLは件数に依存しない")
    void AC57_一覧の業務SQLは件数に依存しない() throws Exception {
        int heavy = businessSqlOfList(HEAVY_OWNER_ID, 20);
        int light = businessSqlOfList(LIGHT_OWNER_ID, 20);

        assertThat(heavy)
                .as("21件と5件で業務 SQL 本数が一致すること（N+1 なら比例して増える）。捕捉=%s",
                        SqlIntentCounter.capturedSqls())
                .isEqualTo(light);
        assertThat(heavy).as("業務 SQL は3本以内").isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("AC58_HTTPリクエスト全体のSQLは8本以内")
    void AC58_リクエスト全体のSQLは8本以内() throws Exception {
        // 初回は Hibernate のメタデータ解決等が混ざるため、計測前に一度暖める。
        performList(HEAVY_OWNER_ID, 20);

        SqlIntentCounter.reset();
        performList(HEAVY_OWNER_ID, 20);
        int total = SqlIntentCounter.totalCount();

        assertThat(total)
                .as("業務3 + 認可 + 監査 + 余裕 = 8本以内。捕捉=%s", SqlIntentCounter.capturedSqls())
                .isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("AC59_一覧のP95は500ms以内")
    void AC59_一覧のP95は500ms以内() throws Exception {
        for (int i = 0; i < 3; i++) {
            performList(HEAVY_OWNER_ID, 20);
        }
        List<Long> elapsedMillis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            performList(HEAVY_OWNER_ID, 20);
            elapsedMillis.add((System.nanoTime() - start) / 1_000_000L);
        }
        List<Long> sorted = elapsedMillis.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);

        assertThat(p95)
                .as("一覧 P95。実測列=%s", sorted)
                .isLessThanOrEqualTo(500L);
    }

    // ═════════ helper ═════════

    private void performList(long actorId, int size) throws Exception {
        mockMvc.perform(get(INVOICES)
                        .with(user(String.valueOf(actorId)))
                        .param("scopeKind", "USER")
                        .param("scopeId", String.valueOf(actorId))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk());
    }

    private int businessSqlOfList(long actorId, int size) throws Exception {
        performList(actorId, size);
        SqlIntentCounter.reset();
        performList(actorId, size);
        return businessIntents();
    }

    private int businessSqlOfDetail(UUID invoiceId) throws Exception {
        performDetail(invoiceId);
        SqlIntentCounter.reset();
        performDetail(invoiceId);
        return businessIntents();
    }

    private void performDetail(UUID invoiceId) throws Exception {
        mockMvc.perform(get(INVOICES + "/{id}", invoiceId)
                        .with(user(String.valueOf(HEAVY_OWNER_ID))))
                .andExpect(status().isOk());
    }

    private int businessIntents() {
        return SqlIntentCounter.intentCount(T_INVOICES)
                + SqlIntentCounter.intentCount(T_LINES)
                + SqlIntentCounter.intentCount(T_ADJUSTMENTS);
    }
}
